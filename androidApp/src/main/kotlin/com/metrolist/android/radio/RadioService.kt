package com.metrolist.android.radio

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

// Live internet radio, separate from the YouTube Music player so each keeps its own queue and session.
class RadioService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { RadioStore.get(this) }
    private var retryAttempt = 0
    private val retry = Runnable { reconnect() }
    private val releaseTimeshift = Runnable { dropTimeshift() }
    private var behindLiveMs = 0L
    private var pausedAtMs: Long? = null

    override fun onCreate() {
        super.onCreate()
        val dataSourceFactory =
            DefaultHttpDataSource
                .Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
        player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                ).setHandleAudioBecomingNoisy(true)
                // Keeps Wi-Fi and the CPU awake only while the stream plays with the screen off.
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setLoadControl(
                    DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(20_000, MAX_TIMESHIFT_MS.toInt(), 1_500, 4_000)
                        .build(),
                ).build()
        applyBatterySaver()
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    scheduleReconnect()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        retryAttempt = 0
                        handler.removeCallbacks(retry)
                    }
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    resetTimeshift()
                }
            },
        )

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider
                .Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.radio_online)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )

        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, RadioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        session =
            MediaSession
                .Builder(this, LiveStreamPlayer(player))
                .setId(SESSION_ID)
                .setSessionActivity(openIntent)
                .setCallback(SessionCallback())
                .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(releaseTimeshift)
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun applyBatterySaver() {
        val mode =
            if (store.batterySaver) {
                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
            } else {
                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
            }
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(AudioOffloadPreferences.Builder().setAudioOffloadMode(mode).build())
                .build()
    }

    // Live streams drop when coverage disappears; keep retrying with backoff while the user wants it playing.
    private fun scheduleReconnect() {
        if (!player.playWhenReady || player.mediaItemCount == 0) return
        handler.removeCallbacks(retry)
        val delayMs = (RETRY_BASE_MS shl retryAttempt.coerceAtMost(4)).coerceAtMost(RETRY_MAX_MS)
        retryAttempt++
        handler.postDelayed(retry, delayMs)
    }

    private fun reconnect() {
        if (!player.playWhenReady || player.mediaItemCount == 0) return
        resetTimeshift()
        player.seekToDefaultPosition()
        player.prepare()
    }

    // Reconnects at the live edge, discarding any audio recorded while paused.
    private fun goLive() {
        if (player.mediaItemCount == 0) return
        handler.removeCallbacks(releaseTimeshift)
        resetTimeshift()
        applyBatterySaver()
        player.stop()
        player.seekToDefaultPosition()
        player.prepare()
        player.play()
    }

    // A long pause stops recording so the connection and the modem can rest; Play then resumes live.
    private fun dropTimeshift() {
        player.stop()
        resetTimeshift()
    }

    private fun resetTimeshift() {
        behindLiveMs = 0L
        pausedAtMs = null
        publishLiveState()
    }

    private fun publishLiveState() {
        session?.setSessionExtras(
            Bundle().apply {
                putLong(EXTRA_BEHIND_LIVE_MS, behindLiveMs)
                pausedAtMs?.let { putLong(EXTRA_PAUSED_AT_MS, it) }
            },
        )
    }

    // Pause keeps recording the live stream for a while so Play continues where it stopped (timeshift).
    private inner class LiveStreamPlayer(
        player: Player,
    ) : ForwardingPlayer(player) {
        override fun play() {
            handler.removeCallbacks(releaseTimeshift)
            applyBatterySaver()
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                resetTimeshift()
                seekToDefaultPosition()
                prepare()
            } else {
                pausedAtMs?.let { behindLiveMs += SystemClock.elapsedRealtime() - it }
                pausedAtMs = null
                publishLiveState()
            }
            super.play()
        }

        override fun pause() {
            handler.removeCallbacks(retry)
            super.pause()
            if (playbackState == Player.STATE_IDLE) return
            if (pausedAtMs == null) pausedAtMs = SystemClock.elapsedRealtime()
            publishLiveState()
            handler.removeCallbacks(releaseTimeshift)
            handler.postDelayed(releaseTimeshift, MAX_TIMESHIFT_MS - behindLiveMs.coerceAtMost(MAX_TIMESHIFT_MS - 60_000L))
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) play() else pause()
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult
                .AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(COMMAND_GO_LIVE, Bundle.EMPTY))
                        .build(),
                ).build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_GO_LIVE) goLive()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val resolved =
                mediaItems.mapNotNull { item ->
                    val station = item.requestMetadata.extras?.getString(EXTRA_STATION)?.let(::decodeStation) ?: return@mapNotNull null
                    store.lastStation = station
                    scope.launch { RadioBrowser.reportClick(station.id) }
                    station.toMediaItem()
                }
            return Futures.immediateFuture(resolved)
        }
    }

    companion object {
        const val EXTRA_STATION = "radio_station"
        const val COMMAND_GO_LIVE = "radio_go_live"
        const val EXTRA_BEHIND_LIVE_MS = "behind_live_ms"
        const val EXTRA_PAUSED_AT_MS = "paused_at_ms"
        const val MAX_TIMESHIFT_MS = 300_000L
        private const val SESSION_ID = "radio"
        private const val CHANNEL_ID = "radio_playback"
        private const val NOTIFICATION_ID = 0x5241
        private const val USER_AGENT = "MetrolistRadio/1.0"
        private const val RETRY_BASE_MS = 2_000L
        private const val RETRY_MAX_MS = 30_000L
        private val json = Json { ignoreUnknownKeys = true }

        private fun decodeStation(value: String): RadioStation? = runCatching { json.decodeFromString<RadioStation>(value) }.getOrNull()

        // Item sent by the controller; the service rebuilds the playable URI from the station itself.
        fun requestItem(station: RadioStation): MediaItem =
            MediaItem
                .Builder()
                .setMediaId(station.id)
                .setMediaMetadata(station.metadata())
                .setRequestMetadata(
                    MediaItem.RequestMetadata
                        .Builder()
                        .setMediaUri(station.streamUrl.toUri())
                        .setExtras(Bundle().apply { putString(EXTRA_STATION, json.encodeToString(station)) })
                        .build(),
                ).build()

        private fun RadioStation.toMediaItem(): MediaItem =
            MediaItem
                .Builder()
                .setMediaId(id)
                .setUri(streamUrl)
                .apply { if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .setMediaMetadata(metadata())
                .setRequestMetadata(
                    MediaItem.RequestMetadata
                        .Builder()
                        .setExtras(Bundle().apply { putString(EXTRA_STATION, json.encodeToString(this@toMediaItem)) })
                        .build(),
                ).build()

        private fun RadioStation.metadata(): MediaMetadata =
            MediaMetadata
                .Builder()
                .setTitle(displayName)
                .setArtist(displayName)
                .setStation(displayName)
                .setArtworkUri(favicon.takeIf { it.isNotBlank() }?.toUri())
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
    }
}
