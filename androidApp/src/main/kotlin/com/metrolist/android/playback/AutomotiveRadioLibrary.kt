package com.metrolist.android.playback

import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.metrolist.android.R
import com.metrolist.android.radio.RadioService.Companion.toMediaItem
import com.metrolist.android.radio.RadioStore
import com.metrolist.shared.radio.RadioBrowser
import com.metrolist.shared.radio.RadioQuery
import com.metrolist.shared.radio.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal object AutomotiveRadioIds {
    const val ROOT = "auto:radio"
    const val FAVORITES = "auto:radio:favorites"
    const val COUNTRY = "auto:radio:country"
    const val WORLD = "auto:radio:world"
    const val GENRES = "auto:radio:genres"
    private const val GENRE_PREFIX = "auto:radio:genre:"
    private const val STATION_PREFIX = "auto:radio:station:"

    fun isRadio(mediaId: String): Boolean = mediaId == ROOT || mediaId.startsWith("$ROOT:")

    fun genre(tag: String): String = GENRE_PREFIX + URLEncoder.encode(tag, StandardCharsets.UTF_8.name())

    fun genreTag(mediaId: String): String? =
        mediaId.takeIf { it.startsWith(GENRE_PREFIX) }?.removePrefix(GENRE_PREFIX)?.let {
            URLDecoder.decode(it, StandardCharsets.UTF_8.name())
        }

    fun station(id: String): String = STATION_PREFIX + id

    fun stationId(mediaId: String): String? = mediaId.takeIf { it.startsWith(STATION_PREFIX) }?.removePrefix(STATION_PREFIX)
}

/** Online radio section of the Android Auto library: favorites, popular stations and genres. */
internal class AutomotiveRadioLibrary(
    private val context: Context,
) {
    private val store by lazy { RadioStore.get(context) }

    // Last list each station was browsed in, so Android Auto's next/previous moves through that list.
    private val lists = ConcurrentHashMap<String, List<RadioStation>>()
    private val stationParents = ConcurrentHashMap<String, String>()

    data class Queue(
        val items: List<MediaItem>,
        val startIndex: Int,
        val station: RadioStation,
    )

    val rootFolder: MediaItem
        get() =
            folder(
                mediaId = AutomotiveRadioIds.ROOT,
                title = context.getString(R.string.radio),
                artwork = "android.resource://${context.packageName}/${R.drawable.ic_notification_radio}",
            )

    suspend fun children(parentId: String): List<MediaItem> =
        when (parentId) {
            AutomotiveRadioIds.ROOT -> {
                val favorites = remember(AutomotiveRadioIds.FAVORITES, store.favorites.value)
                val groupTitle = context.getString(R.string.radio_favorites)
                favorites.map { stationItem(it, groupTitle) } +
                    listOfNotNull(
                        countryCode().takeIf { it.isNotBlank() }?.let {
                            folder(AutomotiveRadioIds.COUNTRY, context.getString(R.string.radio_auto_popular_country, countryName()))
                        },
                        folder(AutomotiveRadioIds.WORLD, context.getString(R.string.radio_auto_popular_world)),
                        folder(AutomotiveRadioIds.GENRES, context.getString(R.string.radio_auto_genres)),
                    )
            }

            AutomotiveRadioIds.FAVORITES -> {
                remember(parentId, store.favorites.value).map { stationItem(it) }
            }

            AutomotiveRadioIds.COUNTRY -> {
                stations(parentId, RadioQuery(countryCode = countryCode(), batterySaver = store.batterySaver))
            }

            AutomotiveRadioIds.WORLD -> {
                stations(parentId, RadioQuery(batterySaver = store.batterySaver))
            }

            AutomotiveRadioIds.GENRES -> {
                withContext(Dispatchers.IO) { RadioBrowser.topTags() }
                    .map { tag -> folder(AutomotiveRadioIds.genre(tag.name), tag.name.replaceFirstChar { it.titlecase() }) }
            }

            else -> {
                val tag = AutomotiveRadioIds.genreTag(parentId) ?: return emptyList()
                val local = stations(parentId, RadioQuery(countryCode = countryCode(), tag = tag, batterySaver = store.batterySaver))
                // Small countries have few stations per genre; fall back to the worldwide list.
                local.ifEmpty { stations(parentId, RadioQuery(tag = tag, batterySaver = store.batterySaver)) }
            }
        }

    suspend fun item(mediaId: String): MediaItem? {
        if (mediaId == AutomotiveRadioIds.ROOT) return rootFolder
        val stationId = AutomotiveRadioIds.stationId(mediaId)
        if (stationId != null) return findStation(stationId)?.let { stationItem(it) }
        return children(AutomotiveRadioIds.ROOT).firstOrNull { it.mediaId == mediaId }
    }

    /** Stations to load into the radio player when [mediaId] is picked in Android Auto. */
    suspend fun queueFor(mediaId: String): Queue? {
        val stationId = AutomotiveRadioIds.stationId(mediaId) ?: return null
        val list =
            stationParents[stationId]?.let(lists::get)?.takeIf { stations -> stations.any { it.id == stationId } }
                ?: listOfNotNull(findStation(stationId))
        val index = list.indexOfFirst { it.id == stationId }.takeIf { it >= 0 } ?: return null
        store.lastStation = list[index]
        return Queue(list.map(::playableItem), index, list[index])
    }

    private fun playableItem(station: RadioStation): MediaItem = station.toMediaItem(mediaArtworkUri(context, station.favicon.ifBlank { null }))

    private suspend fun findStation(id: String): RadioStation? =
        lists.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == id } }
            ?: store.favorites.value.firstOrNull { it.id == id }
            ?: store.lastStation?.takeIf { it.id == id }
            ?: withContext(Dispatchers.IO) { RadioBrowser.station(id) }

    private suspend fun stations(
        parentId: String,
        query: RadioQuery,
    ): List<MediaItem> {
        val stations = withContext(Dispatchers.IO) { RadioBrowser.search(query, offset = 0) }
        return remember(parentId, stations).map { stationItem(it) }
    }

    private fun remember(
        parentId: String,
        stations: List<RadioStation>,
    ): List<RadioStation> {
        lists[parentId] = stations
        stations.forEach { stationParents[it.id] = parentId }
        return stations
    }

    private fun countryCode(): String = store.countryCode?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country

    private fun countryName(): String {
        val code = countryCode()
        return Locale("", code).displayCountry.ifBlank { code }
    }

    private fun stationItem(
        station: RadioStation,
        groupTitle: String? = null,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(AutomotiveRadioIds.station(station.id))
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(station.displayName)
                    .setDisplayTitle(station.displayName)
                    .setSubtitle(station.subtitle)
                    .setStation(station.displayName)
                    .setArtworkUri(mediaArtworkUri(context, station.favicon.ifBlank { null }))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setExtras(
                        Bundle().apply {
                            putInt(
                                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
                                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                            )
                            groupTitle?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
                        },
                    ).build(),
            ).build()

    private fun folder(
        mediaId: String,
        title: String,
        artwork: String? = null,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .setArtworkUri(artwork?.toUri())
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setExtras(
                        Bundle().apply {
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                        },
                    ).build(),
            ).build()
}
