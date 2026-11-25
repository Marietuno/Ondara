package com.metrolist.android.spotifyimport

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// One-shot request from the Library shortcut to open the Spotify import as soon as Backup & restore shows.
object SpotifyImportShortcut {
    var pending by mutableStateOf(false)
}
