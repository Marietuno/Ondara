package com.metrolist.desktop.radio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.playback.ffmpeg.AudioPlayer
import com.metrolist.shared.radio.RadioBrowser
import com.metrolist.shared.radio.RadioQuery
import com.metrolist.shared.radio.RadioStation
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

// Live internet radio for the desktop app; it pauses the music player while a station plays.
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
    var volume by mutableStateOf(1f)
        private set

    val initialQuery: RadioQuery
        get() = RadioQuery(countryCode = prefs.countryCode ?: Locale.getDefault().country, batterySaver = prefs.batterySaver)

    private var player: AudioPlayer? = null
    private var generation = 0L

    fun rememberQuery(query: RadioQuery) {
        save(prefs.copy(countryCode = query.countryCode, batterySaver = query.batterySaver))
    }

    fun isFavorite(id: String): Boolean = favorites.any { it.id == id }

    fun toggleFavorite(station: RadioStation) {
        favorites = if (isFavorite(station.id)) favorites.filterNot { it.id == station.id } else favorites + station
        save(prefs.copy(favorites = favorites))
    }

    fun play(target: RadioStation) {
        if (PlayerState.isPlaying) PlayerState.pause()
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
                ).apply { setVolume(volume) }
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
        if (status == DesktopRadioStatus.PLAYING || status == DesktopRadioStatus.CONNECTING) stop() else play(current)
    }

    fun changeVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        player?.setVolume(volume)
    }

    // The music player takes over audio when it starts playing.
    fun onMusicPlaying() {
        if (status == DesktopRadioStatus.PLAYING || status == DesktopRadioStatus.CONNECTING) stop()
    }

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
