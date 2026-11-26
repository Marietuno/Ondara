package com.metrolist.android.radio

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.metrolist.shared.radio.RadioStation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Connects a screen to [RadioService]. Every command waits for the connection instead of being
 * dropped, and connection or stream failures are published so the screen can show them.
 */
class RadioPlaybackClient(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val store = RadioStore.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectLock = Mutex()
    private var controller: MediaController? = null
    private val _nowPlaying = MutableStateFlow(RadioNowPlaying(station = store.lastStation))
    val nowPlaying: StateFlow<RadioNowPlaying> = _nowPlaying.asStateFlow()

    private val listener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                publish(player)
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.tag(TAG).w(error, "Radio stream failed")
                _nowPlaying.update { it.copy(error = RadioError.STREAM) }
            }
        }

    fun connect() {
        scope.launch { runCatching { controller() } }
    }

    fun release() {
        controller?.run {
            removeListener(listener)
            release()
        }
        controller = null
        scope.cancel()
    }

    fun play(station: RadioStation) {
        store.lastStation = station
        _nowPlaying.value = RadioNowPlaying(station = station, isBuffering = true)
        command { player ->
            player.setMediaItem(RadioService.requestItem(station))
            player.prepare()
            player.play()
        }
    }

    fun toggle() {
        command { player ->
            when {
                player.playWhenReady -> player.pause()
                player.mediaItemCount == 0 -> store.lastStation?.let(::play)
                else -> player.play()
            }
        }
    }

    fun goLive() {
        command { player -> player.sendCustomCommand(SessionCommand(RadioService.COMMAND_GO_LIVE, Bundle.EMPTY), Bundle.EMPTY) }
    }

    private fun command(block: (MediaController) -> Unit) {
        scope.launch {
            try {
                block(controller())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Radio service connection failed")
                _nowPlaying.update { it.copy(isBuffering = false, error = RadioError.SERVICE) }
            }
        }
    }

    private suspend fun controller(): MediaController =
        connectLock.withLock {
            controller?.takeIf { it.isConnected }?.let { return it }
            controller?.release()
            val token = SessionToken(appContext, ComponentName(appContext, RadioService::class.java))
            val built =
                MediaController
                    .Builder(appContext, token)
                    .setListener(
                        object : MediaController.Listener {
                            override fun onExtrasChanged(
                                controller: MediaController,
                                extras: Bundle,
                            ) {
                                publish(controller)
                            }
                        },
                    ).buildAsync()
                    .await()
            built.addListener(listener)
            controller = built
            publish(built)
            built
        }

    private fun publish(player: Player) {
        val current = store.lastStation
        val station = current?.takeIf { it.id == player.currentMediaItem?.mediaId } ?: current
        val title = player.mediaMetadata.title?.toString()
        val extras = (player as? MediaController)?.sessionExtras
        val previousError = _nowPlaying.value.error
        _nowPlaying.value =
            RadioNowPlaying(
                station = station,
                streamTitle = title?.takeIf { it.isNotBlank() && it != station?.displayName },
                isPlaying = player.isPlaying,
                isBuffering = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING,
                behindLiveMs = extras?.getLong(RadioService.EXTRA_BEHIND_LIVE_MS) ?: 0L,
                pausedAtMs =
                    extras
                        ?.takeIf { it.containsKey(RadioService.EXTRA_PAUSED_AT_MS) }
                        ?.getLong(RadioService.EXTRA_PAUSED_AT_MS),
                error = previousError.takeIf { !player.isPlaying && player.playerError != null },
            )
    }

    private companion object {
        const val TAG = "RadioPlayback"
    }
}

enum class RadioError { SERVICE, STREAM }
