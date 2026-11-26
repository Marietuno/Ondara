package com.metrolist.android.radio

import com.metrolist.shared.radio.RadioStation
import com.metrolist.shared.radio.RadioBrowser
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
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
    private lateinit var player: LiveRadioPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store by lazy { RadioStore.get(this) }

    override fun onCreate() {
        super.onCreate()
        player = LiveRadioPlayer(this, ::publishLiveState)

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
                .Builder(this, player)
                .setId(SESSION_ID)
                .setSessionActivity(openIntent)
                .setCallback(SessionCallback())
                .build()
        running = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        if (running === this) running = null
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun publishLiveState() {
        session?.setSessionExtras(
            Bundle().apply {
                putLong(EXTRA_BEHIND_LIVE_MS, player.behindLiveMs)
                player.pausedAtMs?.let { putLong(EXTRA_PAUSED_AT_MS, it) }
            },
        )
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
            if (customCommand.customAction == COMMAND_GO_LIVE) player.goLive()
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
        const val MAX_TIMESHIFT_MS = LiveRadioPlayer.MAX_TIMESHIFT_MS
        private const val SESSION_ID = "radio"
        private const val CHANNEL_ID = "radio_playback"
        private const val NOTIFICATION_ID = 0x5241
        private val json = Json { ignoreUnknownKeys = true }

        @Volatile
        private var running: RadioService? = null

        /** Pauses the phone radio when another player (Android Auto) takes over. */
        fun pauseRunning() {
            running?.player?.takeIf { it.playWhenReady }?.pause()
        }

        fun encodeStation(station: RadioStation): String = json.encodeToString(station)

        fun decodeStation(value: String): RadioStation? = runCatching { json.decodeFromString<RadioStation>(value) }.getOrNull()

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

        fun RadioStation.toMediaItem(artworkUri: android.net.Uri? = favicon.takeIf { it.isNotBlank() }?.toUri()): MediaItem =
            MediaItem
                .Builder()
                .setMediaId(id)
                .setUri(streamUrl)
                .apply { if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .setMediaMetadata(metadata(artworkUri))
                .setRequestMetadata(
                    MediaItem.RequestMetadata
                        .Builder()
                        .setExtras(Bundle().apply { putString(EXTRA_STATION, json.encodeToString(this@toMediaItem)) })
                        .build(),
                ).build()

        private fun RadioStation.metadata(artworkUri: android.net.Uri? = favicon.takeIf { it.isNotBlank() }?.toUri()): MediaMetadata =
            MediaMetadata
                .Builder()
                .setTitle(displayName)
                .setArtist(displayName)
                .setStation(displayName)
                .setArtworkUri(artworkUri)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
    }
}
