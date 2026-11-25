package com.metrolist.android.radio

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

// Favorite stations and radio preferences, kept apart from the music library.
class RadioStore private constructor(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _favorites = MutableStateFlow(readFavorites())
    val favorites: StateFlow<List<RadioStation>> = _favorites.asStateFlow()

    var batterySaver: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_SAVER, true)
        set(value) = prefs.edit { putBoolean(KEY_BATTERY_SAVER, value) }

    var countryCode: String?
        get() = prefs.getString(KEY_COUNTRY, null)
        set(value) = prefs.edit { putString(KEY_COUNTRY, value) }

    var lastStation: RadioStation?
        get() = prefs.getString(KEY_LAST_STATION, null)?.let { runCatching { json.decodeFromString<RadioStation>(it) }.getOrNull() }
        set(value) = prefs.edit { putString(KEY_LAST_STATION, value?.let { json.encodeToString(it) }) }

    fun isFavorite(id: String): Boolean = _favorites.value.any { it.id == id }

    fun toggleFavorite(station: RadioStation) {
        val current = _favorites.value
        val updated = if (current.any { it.id == station.id }) current.filterNot { it.id == station.id } else current + station
        _favorites.value = updated
        prefs.edit { putString(KEY_FAVORITES, json.encodeToString(updated)) }
    }

    private fun readFavorites(): List<RadioStation> =
        prefs.getString(KEY_FAVORITES, null)?.let { runCatching { json.decodeFromString<List<RadioStation>>(it) }.getOrNull() }.orEmpty()

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_BATTERY_SAVER = "battery_saver"
        private const val KEY_COUNTRY = "country"
        private const val KEY_LAST_STATION = "last_station"

        @Volatile
        private var instance: RadioStore? = null

        fun get(context: Context): RadioStore =
            instance ?: synchronized(this) {
                instance ?: RadioStore(context.applicationContext).also { instance = it }
            }
    }
}
