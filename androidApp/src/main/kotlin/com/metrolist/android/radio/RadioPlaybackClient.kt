package com.metrolist.android.radio

import android.content.Context
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.shared.radio.RadioStation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.context.GlobalContext

/**
 * Online radio controls for the radio screens. Stations play through the music service and session, so the app's
 * player, notification, headset buttons and Android Auto all show and control whichever is playing.
 */
class RadioPlaybackClient(
    context: Context,
) {
    private val store = RadioStore.get(context.applicationContext)
    private val connection: PlayerConnection = GlobalContext.get().get()

    // The current station while the radio owns the player; otherwise the last one played, idle.
    val nowPlaying: Flow<RadioNowPlaying> =
        connection.radioNowPlaying.map { it ?: RadioNowPlaying(station = store.lastStation) }

    val isActive: Flow<Boolean> = connection.radioNowPlaying.map { it != null }

    fun connect() = connection.ensureConnected()

    fun play(
        station: RadioStation,
        list: List<RadioStation> = listOf(station),
    ) {
        store.lastStation = station
        val stations = if (list.any { it.id == station.id }) list else listOf(station)
        connection.playRadio(stations, stations.indexOfFirst { it.id == station.id })
    }

    fun toggle(active: Boolean) {
        if (active) connection.toggleRadio() else store.lastStation?.let(::play)
    }

    fun skip(step: Int) = connection.skipRadio(step)

    fun goLive() = connection.goLiveRadio()
}

enum class RadioError { SERVICE, STREAM }
