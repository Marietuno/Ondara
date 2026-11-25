package com.metrolist.desktop.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.radio_add_favorite
import com.metrolist.shared.generated.resources.radio_all_countries
import com.metrolist.shared.generated.resources.radio_connecting
import com.metrolist.shared.generated.resources.radio_country
import com.metrolist.shared.generated.resources.radio_explore
import com.metrolist.shared.generated.resources.radio_favorites
import com.metrolist.shared.generated.resources.radio_live
import com.metrolist.shared.generated.resources.radio_load_error
import com.metrolist.shared.generated.resources.radio_load_more
import com.metrolist.shared.generated.resources.radio_no_favorites
import com.metrolist.shared.generated.resources.radio_no_stations
import com.metrolist.shared.generated.resources.radio_online
import com.metrolist.shared.generated.resources.radio_play
import com.metrolist.shared.generated.resources.radio_remove_favorite
import com.metrolist.shared.generated.resources.radio_retry
import com.metrolist.shared.generated.resources.radio_search_hint
import com.metrolist.shared.generated.resources.radio_stop
import com.metrolist.shared.radio.RadioBrowser
import com.metrolist.shared.radio.RadioCountry
import com.metrolist.shared.radio.RadioQuery
import com.metrolist.shared.radio.RadioStation
import com.metrolist.shared.radio.RadioTag
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

@Composable
fun DesktopRadioScreen() {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(DesktopRadio.initialQuery) }
    var stations by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var countries by remember { mutableStateOf<List<RadioCountry>>(emptyList()) }
    var tags by remember { mutableStateOf<List<RadioTag>>(emptyList()) }
    var tab by remember { mutableStateOf(if (DesktopRadio.favorites.isEmpty()) 1 else 0) }
    var search by remember { mutableStateOf("") }
    var loadJob by remember { mutableStateOf<Job?>(null) }

    fun load(reset: Boolean) {
        loadJob?.cancel()
        if (reset) {
            stations = emptyList()
            endReached = false
        }
        loadJob =
            scope.launch {
                loading = true
                error = false
                try {
                    val page = RadioBrowser.search(query, if (reset) 0 else stations.size)
                    stations = (stations + page).distinctBy { it.id }
                    endReached = page.size < RadioBrowser.PAGE_SIZE
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = true
                } finally {
                    loading = false
                }
            }
    }

    fun updateQuery(transform: (RadioQuery) -> RadioQuery) {
        val next = transform(query)
        if (next == query) return
        query = next
        DesktopRadio.rememberQuery(next)
        load(reset = true)
    }

    LaunchedEffect(Unit) {
        load(reset = true)
        launch { runCatching { RadioBrowser.topTags() }.onSuccess { tags = it } }
        launch { runCatching { RadioBrowser.countries() }.onSuccess { countries = it } }
    }
    LaunchedEffect(search) {
        delay(400)
        updateQuery { it.copy(name = search) }
        if (search.isNotBlank()) tab = 1
    }
    LaunchedEffect(PlayerState.isPlaying) {
        if (PlayerState.isPlaying) DesktopRadio.onMusicPlaying()
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            singleLine = true,
            leadingIcon = { Icon(MetrolistIcons.Outlined.Search, contentDescription = null) },
            placeholder = { Text(stringResource(Res.string.radio_search_hint)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(Res.string.radio_favorites)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(Res.string.radio_explore)) })
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) {
                StationList(DesktopRadio.favorites, stringResource(Res.string.radio_no_favorites))
            } else {
                Column {
                    Filters(query, countries, tags, ::updateQuery)
                    StationList(stations, if (loading) null else stringResource(Res.string.radio_no_stations)) {
                        when {
                            loading -> {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            }

                            error -> {
                                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(Res.string.radio_load_error))
                                    TextButton(onClick = { load(reset = true) }) { Text(stringResource(Res.string.radio_retry)) }
                                }
                            }

                            !endReached && stations.isNotEmpty() -> {
                                TextButton(onClick = { load(reset = false) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(Res.string.radio_load_more))
                                }
                            }
                        }
                    }
                }
            }
        }
        NowPlayingBar()
    }
}

@Composable
private fun Filters(
    query: RadioQuery,
    countries: List<RadioCountry>,
    tags: List<RadioTag>,
    onQuery: ((RadioQuery) -> RadioQuery) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val countryName =
        if (query.countryCode.isBlank()) {
            stringResource(Res.string.radio_all_countries)
        } else {
            Locale("", query.countryCode).displayCountry.ifBlank { query.countryCode }
        }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Box {
                FilterChip(
                    selected = query.countryCode.isNotBlank(),
                    onClick = { menu = true },
                    label = { Text("${stringResource(Res.string.radio_country)}: $countryName") },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.radio_all_countries)) },
                        onClick = {
                            menu = false
                            onQuery { it.copy(countryCode = "") }
                        },
                    )
                    countries
                        .map { it to Locale("", it.code).displayCountry.ifBlank { it.name } }
                        .sortedBy { it.second }
                        .forEach { (country, name) ->
                            DropdownMenuItem(
                                text = { Text("$name (${country.stationCount})") },
                                onClick = {
                                    menu = false
                                    onQuery { it.copy(countryCode = country.code) }
                                },
                            )
                        }
                }
            }
        }
        items(tags, key = { it.name }) { tag ->
            FilterChip(
                selected = query.tag == tag.name,
                onClick = { onQuery { it.copy(tag = if (it.tag == tag.name) "" else tag.name) } },
                label = { Text(tag.name) },
            )
        }
    }
}

@Composable
private fun StationList(
    stations: List<RadioStation>,
    emptyText: String?,
    footer: @Composable () -> Unit = {},
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        if (stations.isEmpty() && emptyText != null) {
            item {
                Text(
                    emptyText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        items(stations, key = { it.id }) { station -> StationRow(station) }
        item { footer() }
    }
}

@Composable
private fun StationRow(station: RadioStation) {
    val isCurrent = DesktopRadio.station?.id == station.id
    val isFavorite = DesktopRadio.isFavorite(station.id)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { DesktopRadio.play(station) }
                .background(if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        StationLogo(station, 44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(station.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (station.subtitle.isNotBlank()) {
                Text(
                    station.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (station.qualityLabel.isNotBlank()) {
                Text(station.qualityLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        IconButton(onClick = { DesktopRadio.toggleFavorite(station) }) {
            Icon(
                if (isFavorite) MetrolistIcons.Filled.Star else MetrolistIcons.Outlined.Star,
                contentDescription = stringResource(if (isFavorite) Res.string.radio_remove_favorite else Res.string.radio_add_favorite),
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StationLogo(
    station: RadioStation,
    sizeDp: Int,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(MetrolistIcons.Outlined.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (station.favicon.isNotBlank()) AsyncImage(url = station.favicon, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun NowPlayingBar() {
    val station = DesktopRadio.station ?: return
    val status = DesktopRadio.status
    val active = status == DesktopRadioStatus.PLAYING || status == DesktopRadioStatus.CONNECTING
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StationLogo(station, 52)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(station.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val detail =
                        when (status) {
                            DesktopRadioStatus.CONNECTING -> stringResource(Res.string.radio_connecting)
                            DesktopRadioStatus.PLAYING -> "● " + stringResource(Res.string.radio_live) + " · " + station.qualityLabel
                            DesktopRadioStatus.ERROR -> stringResource(Res.string.radio_load_error)
                            DesktopRadioStatus.IDLE -> station.qualityLabel
                        }
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledIconButton(onClick = DesktopRadio::toggle, modifier = Modifier.size(52.dp)) {
                    Icon(
                        if (active) MetrolistIcons.Filled.Pause else MetrolistIcons.Filled.PlayArrow,
                        contentDescription = stringResource(if (active) Res.string.radio_stop else Res.string.radio_play),
                    )
                }
            }
            Slider(value = DesktopRadio.volume, onValueChange = DesktopRadio::changeVolume, modifier = Modifier.fillMaxWidth())
        }
    }
}

// Compact control in the top bar so a playing station can be stopped or reopened from any screen.
@Composable
fun DesktopRadioTopBarControl() {
    val station = DesktopRadio.station ?: return
    val status = DesktopRadio.status
    if (status != DesktopRadioStatus.PLAYING && status != DesktopRadioStatus.CONNECTING) return
    Surface(
        onClick = { DesktopNavigation.navigate(Screen.Radio) },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
            Icon(MetrolistIcons.Outlined.Radio, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                station.displayName,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
            IconButton(onClick = DesktopRadio::stop, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(32.dp)) {
                Icon(MetrolistIcons.Filled.Pause, contentDescription = stringResource(Res.string.radio_stop), modifier = Modifier.size(16.dp))
            }
        }
    }
}
