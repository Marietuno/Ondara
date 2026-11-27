package com.metrolist.desktop.radio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.playback.ffmpeg.AudioPlayer
import com.metrolist.shared.playback.playbackOutputGain
import com.metrolist.shared.radio.RadioBrowser
import com.metrolist.shared.radio.RadioQuery
import com.metrolist.shared.radio.RadioStation
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

@Serializable
private data class DesktopRadioPrefs(
    val favorites: List<RadioStation> = emptyList(),
    val countryCode: String? = null,
    val batterySaver: Boolean = false,
    val lastStation: RadioStation? = null,
)

enum class DesktopRadioStatus { IDLE, CONNECTING, PLAYING, ERROR }

// Live internet radio for the desktop app. While a station is the active source the bottom player shows it and
// its play/pause, previous/next, media keys and volume control the station instead of the music queue.
object DesktopRadio {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val prefsFile by lazy { File(XdgUtils.getXdgConfigHome(), "ondara/radio.json") }
    private var prefs = runCatching { json.decodeFromString<DesktopRadioPrefs>(prefsFile.readText()) }.getOrDefault(DesktopRadioPrefs())

    var favorites by mutableStateOf(prefs.favorites)
        private set
    var station by mutableStateOf(prefs.lastStation)
        private set
    var status by mutableStateOf(DesktopRadioStatus.IDLE)
        private set

    // True from picking a station until music playback starts again or the radio is closed.
    var active by mutableStateOf(false)
        private set

    val isPlaying: Boolean
        get() = status == DesktopRadioStatus.PLAYING || status == DesktopRadioStatus.CONNECTING

    val initialQuery: RadioQuery
        get() = RadioQuery(countryCode = prefs.countryCode ?: Locale.getDefault().country, batterySaver = prefs.batterySaver)

    private var player: AudioPlayer? = null
    private var generation = 0L
    private var playlist: List<RadioStation> = emptyList()

    init {
        scope.launch {
            PlayerState.volumeState.collect { level -> synchronized(this@DesktopRadio) { player }?.setVolume(outputVolume(level)) }
        }
        scope.launch {
            // Music taking over (a song picked, music resumed elsewhere) hands the player bar back to it.
            snapshotFlow { PlayerState.isPlaying }.drop(1).filter { it }.collect { if (active) close() }
        }
    }

    fun rememberQuery(query: RadioQuery) {
        save(prefs.copy(countryCode = query.countryCode, batterySaver = query.batterySaver))
    }

    fun isFavorite(id: String): Boolean = favorites.any { it.id == id }

    fun toggleFavorite(station: RadioStation) {
        favorites = if (isFavorite(station.id)) favorites.filterNot { it.id == station.id } else favorites + station
        save(prefs.copy(favorites = favorites))
    }

    // [list] is the list the station was picked from; previous/next move through it.
    fun play(
        target: RadioStation,
        list: List<RadioStation> = playlist,
    ) {
        if (list.any { it.id == target.id }) playlist = list
        if (PlayerState.isPlaying) PlayerState.pause()
        active = true
        station = target
        status = DesktopRadioStatus.CONNECTING
        save(prefs.copy(lastStation = target))
        val current = ++generation
        scope.launch {
            RadioBrowser.reportClick(target.id)
            val previous = synchronized(this@DesktopRadio) { player.also { player = null } }
            previous?.closeAsync()
            val next =
                AudioPlayer(
                    onPlaybackFailed = { _, _ -> if (generation == current) status = DesktopRadioStatus.ERROR },
                    onPlaybackCompleted = { if (generation == current) status = DesktopRadioStatus.IDLE },
                ).apply { setVolume(outputVolume(PlayerState.volume)) }
            val started = runCatching { next.loadFile(target.streamUrl, mapOf("User-Agent" to "MetrolistRadio/1.0")) }.getOrDefault(false)
            if (generation != current) {
                next.closeAsync()
                return@launch
            }
            synchronized(this@DesktopRadio) { player = next }
            status = if (started) DesktopRadioStatus.PLAYING else DesktopRadioStatus.ERROR
        }
    }

    // A live stream is not paused: stopping closes the connection, and Play reconnects at the live edge.
    fun stop() {
        generation++
        val previous = synchronized(this) { player.also { player = null } }
        previous?.closeAsync()
        status = DesktopRadioStatus.IDLE
    }

    fun toggle() {
        val current = station ?: return
        if (isPlaying) stop() else play(current)
    }

    fun resume() {
        if (!isPlaying) station?.let { play(it) }
    }

    val canSkip: Boolean
        get() = playlist.size > 1

    fun next() = skip(1)

    fun previous() = skip(-1)

    private fun skip(offset: Int) {
        val current = station ?: return
        if (playlist.size < 2) return
        val index = playlist.indexOfFirst { it.id == current.id }
        play(playlist[Math.floorMod(index + offset, playlist.size)])
    }

    // Stops the station and gives the player bar back to the music queue.
    fun close() {
        stop()
        active = false
    }

    private fun outputVolume(level: Float) = playbackOutputGain(level, 1f, 1f)

    private fun save(value: DesktopRadioPrefs) {
        prefs = value
        scope.launch {
            runCatching {
                prefsFile.parentFile.mkdirs()
                prefsFile.writeText(json.encodeToString(value))
            }
        }
    }
}
