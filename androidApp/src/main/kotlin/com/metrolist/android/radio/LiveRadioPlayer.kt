package com.metrolist.android.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Player for live internet radio, shared by the phone radio screen and Android Auto.
 *
 * Pause keeps recording the stream for a while so Play continues where it stopped (timeshift),
 * dropped connections are retried with backoff, and battery saver enables audio offload.
 */
class LiveRadioPlayer(
    context: Context,
    private val onLiveStateChanged: () -> Unit = {},
) : ForwardingPlayer(buildExoPlayer(context)) {
    private val store = RadioStore.get(context)
    private val handler = Handler(Looper.getMainLooper())
    private var retryAttempt = 0
    private val retry = Runnable { reconnect() }
    private val releaseTimeshift = Runnable { dropTimeshift() }

    var behindLiveMs = 0L
        private set
    var pausedAtMs: Long? = null
        private set

    init {
        applyBatterySaver()
        wrappedPlayer.addListener(
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
    }

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
            onLiveStateChanged()
        }
        super.play()
    }

    override fun pause() {
        handler.removeCallbacks(retry)
        super.pause()
        if (playbackState == Player.STATE_IDLE) return
        if (pausedAtMs == null) pausedAtMs = SystemClock.elapsedRealtime()
        onLiveStateChanged()
        handler.removeCallbacks(releaseTimeshift)
        handler.postDelayed(releaseTimeshift, MAX_TIMESHIFT_MS - behindLiveMs.coerceAtMost(MAX_TIMESHIFT_MS - 60_000L))
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun release() {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(releaseTimeshift)
        super.release()
    }

    val isBehindLive: Boolean get() = behindLiveMs > 0L || pausedAtMs != null

    // Reconnects at the live edge, discarding any audio recorded while paused.
    fun goLive() {
        if (mediaItemCount == 0) return
        handler.removeCallbacks(releaseTimeshift)
        resetTimeshift()
        applyBatterySaver()
        wrappedPlayer.stop()
        wrappedPlayer.seekToDefaultPosition()
        wrappedPlayer.prepare()
        wrappedPlayer.play()
    }

    fun applyBatterySaver() {
        val mode =
            if (store.batterySaver) {
                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
            } else {
                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
            }
        wrappedPlayer.trackSelectionParameters =
            wrappedPlayer.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(AudioOffloadPreferences.Builder().setAudioOffloadMode(mode).build())
                .build()
    }

    // Live streams drop when coverage disappears; keep retrying with backoff while the user wants it playing.
    private fun scheduleReconnect() {
        if (!wrappedPlayer.playWhenReady || wrappedPlayer.mediaItemCount == 0) return
        handler.removeCallbacks(retry)
        val delayMs = (RETRY_BASE_MS shl retryAttempt.coerceAtMost(4)).coerceAtMost(RETRY_MAX_MS)
        retryAttempt++
        handler.postDelayed(retry, delayMs)
    }

    private fun reconnect() {
        if (!wrappedPlayer.playWhenReady || wrappedPlayer.mediaItemCount == 0) return
        resetTimeshift()
        wrappedPlayer.seekToDefaultPosition()
        wrappedPlayer.prepare()
    }

    // A long pause stops recording so the connection and the modem can rest; Play then resumes live.
    private fun dropTimeshift() {
        wrappedPlayer.stop()
        resetTimeshift()
    }

    private fun resetTimeshift() {
        behindLiveMs = 0L
        pausedAtMs = null
        onLiveStateChanged()
    }

    companion object {
        const val MAX_TIMESHIFT_MS = 300_000L
        internal const val USER_AGENT = "MetrolistRadio/1.0"
        private const val RETRY_BASE_MS = 2_000L
        private const val RETRY_MAX_MS = 30_000L

        private fun buildExoPlayer(context: Context): ExoPlayer {
            val dataSourceFactory =
                DefaultHttpDataSource
                    .Factory()
                    .setUserAgent(USER_AGENT)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(10_000)
                    .setReadTimeoutMs(20_000)
            return ExoPlayer
                .Builder(context)
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
        }
    }
}
