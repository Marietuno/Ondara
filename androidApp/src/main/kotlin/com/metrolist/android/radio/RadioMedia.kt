package com.metrolist.android.radio

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.metrolist.shared.radio.RadioStation
import kotlinx.serialization.json.Json

// Online radio stations as Media3 items; MusicService plays them through the same session as music.
object RadioMedia {
    const val EXTRA_STATION = "radio_station"
    const val COMMAND_GO_LIVE = "radio_go_live"
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeStation(station: RadioStation): String = json.encodeToString(station)

    fun decodeStation(value: String): RadioStation? = runCatching { json.decodeFromString<RadioStation>(value) }.getOrNull()
}

fun RadioStation.toMediaItem(artworkUri: Uri? = favicon.takeIf { it.isNotBlank() }?.toUri()): MediaItem =
    MediaItem
        .Builder()
        .setMediaId(id)
        .setUri(streamUrl)
        .apply { if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(displayName)
                .setArtist(displayName)
                .setStation(displayName)
                .setArtworkUri(artworkUri)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        ).setRequestMetadata(
            MediaItem.RequestMetadata
                .Builder()
                .setExtras(Bundle().apply { putString(RadioMedia.EXTRA_STATION, RadioMedia.encodeStation(this@toMediaItem)) })
                .build(),
        ).build()
