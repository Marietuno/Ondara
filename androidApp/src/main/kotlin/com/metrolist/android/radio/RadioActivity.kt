package com.metrolist.android.radio

import com.metrolist.shared.radio.RadioStation
import com.metrolist.shared.radio.RadioCountry
import com.metrolist.shared.radio.RadioTag
import com.metrolist.shared.radio.RadioQuery
import com.metrolist.shared.radio.RadioBrowser
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import com.metrolist.android.ui.theme.MetrolistTheme
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.radio_add_favorite
import com.metrolist.shared.generated.resources.radio_behind_live
import com.metrolist.shared.generated.resources.radio_go_live
import com.metrolist.shared.generated.resources.radio_live
import com.metrolist.shared.generated.resources.radio_car_tuner
import com.metrolist.shared.generated.resources.radio_car_tuner_access
import com.metrolist.shared.generated.resources.radio_car_tuner_grant
import com.metrolist.shared.generated.resources.radio_car_tuner_none
import com.metrolist.shared.generated.resources.radio_car_tuner_open
import com.metrolist.shared.generated.resources.radio_car_tuner_waiting
import com.metrolist.shared.generated.resources.radio_frequency
import com.metrolist.shared.generated.resources.radio_seek_down
import com.metrolist.shared.generated.resources.radio_seek_up
import com.metrolist.shared.generated.resources.radio_tune
import com.metrolist.shared.generated.resources.radio_tune_hint
import com.metrolist.shared.generated.resources.radio_all_countries
import com.metrolist.shared.generated.resources.radio_battery_saver
import com.metrolist.shared.generated.resources.radio_connecting
import com.metrolist.shared.generated.resources.radio_country
import com.metrolist.shared.generated.resources.radio_explore
import com.metrolist.shared.generated.resources.radio_favorites
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
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

data class RadioBrowseState(
    val query: RadioQuery = RadioQuery(),
    val stations: List<RadioStation> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
    val endReached: Boolean = false,
)

data class RadioNowPlaying(
    val station: RadioStation? = null,
    val streamTitle: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val behindLiveMs: Long = 0L,
    val pausedAtMs: Long? = null,
) {
    // Delay behind the live broadcast, growing while paused because the stream keeps recording.
    fun delayAt(nowMs: Long): Long = behindLiveMs + (pausedAtMs?.let { nowMs - it } ?: 0L)
}

class RadioViewModel(
    application: android.app.Application,
) : AndroidViewModel(application) {
    val store = RadioStore.get(application)
    private val _browse =
        MutableStateFlow(
            RadioBrowseState(
                query =
                    RadioQuery(
                        countryCode = store.countryCode ?: Locale.getDefault().country,
                        batterySaver = store.batterySaver,
                    ),
            ),
        )
    val browse: StateFlow<RadioBrowseState> = _browse.asStateFlow()
    private val _countries = MutableStateFlow<List<RadioCountry>>(emptyList())
    val countries: StateFlow<List<RadioCountry>> = _countries.asStateFlow()
    private val _tags = MutableStateFlow<List<RadioTag>>(emptyList())
    val tags: StateFlow<List<RadioTag>> = _tags.asStateFlow()
    private var loadJob: Job? = null

    init {
        reload()
        viewModelScope.launch { runCatching { RadioBrowser.topTags() }.onSuccess { _tags.value = it } }
        viewModelScope.launch { runCatching { RadioBrowser.countries() }.onSuccess { _countries.value = it } }
    }

    fun updateQuery(
        debounceMs: Long = 0,
        transform: (RadioQuery) -> RadioQuery,
    ) {
        val query = transform(_browse.value.query)
        if (query == _browse.value.query) return
        store.countryCode = query.countryCode
        store.batterySaver = query.batterySaver
        _browse.update { it.copy(query = query) }
        reload(debounceMs)
    }

    fun reload(debounceMs: Long = 0) {
        loadJob?.cancel()
        _browse.update { it.copy(stations = emptyList(), endReached = false, error = false) }
        loadJob =
            viewModelScope.launch {
                if (debounceMs > 0) delay(debounceMs)
                load()
            }
    }

    fun loadMore() {
        val state = _browse.value
        if (state.loading || state.endReached) return
        loadJob = viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val state = _browse.value
        _browse.update { it.copy(loading = true, error = false) }
        try {
            val page = RadioBrowser.search(state.query, state.stations.size)
            _browse.update {
                it.copy(
                    stations = (it.stations + page).distinctBy { station -> station.id },
                    loading = false,
                    endReached = page.size < RadioBrowser.PAGE_SIZE,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _browse.update { it.copy(loading = false, error = true) }
        }
    }
}

class RadioActivity : ComponentActivity() {
    private val viewModel: RadioViewModel by viewModels()
    private val controller = MutableStateFlow<MediaController?>(null)
    private val nowPlaying = MutableStateFlow(RadioNowPlaying())
    private val carTuner by lazy { CarTuner(this) }

    private val listener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                publish(player)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MetrolistTheme {
                RadioScreen(
                    viewModel = viewModel,
                    nowPlaying = nowPlaying.collectAsState().value,
                    onPlay = ::play,
                    onToggle = ::toggle,
                    onGoLive = ::goLive,
                    onSkip = ::skip,
                    onBack = ::finish,
                    carTuner = carTuner,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        carTuner.start()
        lifecycleScopeLaunch {
            val token = SessionToken(this, ComponentName(this, RadioService::class.java))
            val built =
                MediaController
                    .Builder(this, token)
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
            controller.value = built
            publish(built)
        }
    }

    override fun onStop() {
        carTuner.stop()
        // No UI updates while hidden: the service keeps playing without waking this screen.
        controller.value?.run {
            removeListener(listener)
            release()
        }
        controller.value = null
        super.onStop()
    }

    private fun lifecycleScopeLaunch(block: suspend () -> Unit) {
        viewModel.viewModelScope.launch { runCatching { block() } }
    }

    private fun publish(player: Player) {
        val station = viewModel.store.lastStation?.takeIf { it.id == player.currentMediaItem?.mediaId }
        val title = player.mediaMetadata.title?.toString()
        val extras = (player as? MediaController)?.sessionExtras
        nowPlaying.value =
            RadioNowPlaying(
                station = station ?: viewModel.store.lastStation,
                streamTitle = title?.takeIf { it.isNotBlank() && it != station?.displayName },
                isPlaying = player.isPlaying,
                isBuffering = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING,
                behindLiveMs = extras?.getLong(RadioService.EXTRA_BEHIND_LIVE_MS) ?: 0L,
                pausedAtMs =
                    extras
                        ?.takeIf { it.containsKey(RadioService.EXTRA_PAUSED_AT_MS) }
                        ?.getLong(RadioService.EXTRA_PAUSED_AT_MS),
            )
    }

    private fun play(station: RadioStation) {
        viewModel.store.lastStation = station
        nowPlaying.update { RadioNowPlaying(station = station, isBuffering = true) }
        val player = controller.value ?: return
        player.setMediaItem(RadioService.requestItem(station))
        player.prepare()
        player.play()
    }

    private fun goLive() {
        controller.value?.sendCustomCommand(SessionCommand(RadioService.COMMAND_GO_LIVE, Bundle.EMPTY), Bundle.EMPTY)
    }

    // Steps through favorites when the station is one, otherwise through the list being browsed.
    private fun skip(step: Int) {
        val current = nowPlaying.value.station ?: return
        val favorites = viewModel.store.favorites.value
        val list = if (favorites.any { it.id == current.id }) favorites else viewModel.browse.value.stations
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == current.id }
        val next = if (index < 0) list.first() else list[(index + step).mod(list.size)]
        play(next)
    }

    private fun toggle() {
        val player = controller.value ?: return
        if (player.playWhenReady) {
            player.pause()
        } else if (player.mediaItemCount == 0) {
            viewModel.store.lastStation?.let(::play)
        } else {
            player.play()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun RadioScreen(
    viewModel: RadioViewModel,
    nowPlaying: RadioNowPlaying,
    onPlay: (RadioStation) -> Unit,
    onToggle: () -> Unit,
    onGoLive: () -> Unit,
    onSkip: (Int) -> Unit,
    onBack: () -> Unit,
    carTuner: CarTuner,
) {
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    val browse by viewModel.browse.collectAsState()
    val tunerState by carTuner.state.collectAsState()
    val favorites by viewModel.store.favorites.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val countries by viewModel.countries.collectAsState()
    var tab by rememberSaveable { mutableStateOf(if (favorites.isEmpty()) 1 else 0) }
    var search by rememberSaveable { mutableStateOf(browse.query.name) }

    LaunchedEffect(search) {
        viewModel.updateQuery(debounceMs = 400) { it.copy(name = search) }
        if (search.isNotBlank()) tab = 1
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.radio_online)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        bottomBar = {
            if (nowPlaying.station != null) NowPlayingBar(nowPlaying, onToggle, onOpen = { showPlayer = true })
        },
    ) { padding ->
        if (showPlayer && nowPlaying.station != null) {
            ModalBottomSheet(
                onDismissRequest = { showPlayer = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                FullPlayer(
                    nowPlaying = nowPlaying,
                    isFavorite = favorites.any { it.id == nowPlaying.station.id },
                    onToggle = onToggle,
                    onGoLive = onGoLive,
                    onSkip = onSkip,
                    onFavorite = { viewModel.store.toggleFavorite(nowPlaying.station) },
                )
            }
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                leadingIcon = { Icon(MetrolistIcons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { search = "" }) { Icon(MetrolistIcons.Outlined.Close, contentDescription = null) }
                    }
                },
                placeholder = { Text(stringResource(Res.string.radio_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(Res.string.radio_favorites)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(Res.string.radio_explore)) })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(Res.string.radio_car_tuner)) })
            }
            if (tab == 2) {
                CarTunerPanel(tunerState, carTuner)
            } else if (tab == 0) {
                StationList(
                    stations = favorites,
                    favorites = favorites,
                    nowPlaying = nowPlaying,
                    emptyText = stringResource(Res.string.radio_no_favorites),
                    onPlay = onPlay,
                    onFavorite = viewModel.store::toggleFavorite,
                )
            } else {
                Filters(
                    query = browse.query,
                    countries = countries,
                    tags = tags,
                    onQuery = { transform -> viewModel.updateQuery(transform = transform) },
                )
                StationList(
                    stations = browse.stations,
                    favorites = favorites,
                    nowPlaying = nowPlaying,
                    emptyText = if (browse.loading) null else stringResource(Res.string.radio_no_stations),
                    onPlay = onPlay,
                    onFavorite = viewModel.store::toggleFavorite,
                    footer = {
                        when {
                            browse.loading -> {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }

                            browse.error -> {
                                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(Res.string.radio_load_error))
                                    TextButton(onClick = { viewModel.reload() }) { Text(stringResource(Res.string.radio_retry)) }
                                }
                            }

                            !browse.endReached && browse.stations.isNotEmpty() -> {
                                TextButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(Res.string.radio_load_more))
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Filters(
    query: RadioQuery,
    countries: List<RadioCountry>,
    tags: List<RadioTag>,
    onQuery: ((RadioQuery) -> RadioQuery) -> Unit,
) {
    var countryMenu by remember { mutableStateOf(false) }
    val countryName =
        if (query.countryCode.isBlank()) {
            stringResource(Res.string.radio_all_countries)
        } else {
            Locale("", query.countryCode).displayCountry.ifBlank { query.countryCode }
        }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box {
                FilterChip(
                    selected = query.countryCode.isNotBlank(),
                    onClick = { countryMenu = true },
                    label = { Text("${stringResource(Res.string.radio_country)}: $countryName") },
                )
                DropdownMenu(expanded = countryMenu, onDismissRequest = { countryMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.radio_all_countries)) },
                        onClick = {
                            countryMenu = false
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
                                    countryMenu = false
                                    onQuery { it.copy(countryCode = country.code) }
                                },
                            )
                        }
                }
            }
        }
        item {
            FilterChip(
                selected = query.batterySaver,
                onClick = { onQuery { it.copy(batterySaver = !it.batterySaver) } },
                label = { Text(stringResource(Res.string.radio_battery_saver)) },
            )
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
    favorites: List<RadioStation>,
    nowPlaying: RadioNowPlaying,
    emptyText: String?,
    onPlay: (RadioStation) -> Unit,
    onFavorite: (RadioStation) -> Unit,
    footer: @Composable () -> Unit = {},
) {
    val favoriteIds = remember(favorites) { favorites.mapTo(hashSetOf()) { it.id } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (stations.isEmpty() && emptyText != null) {
            item {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        items(stations, key = { it.id }) { station ->
            StationRow(
                station = station,
                isFavorite = station.id in favoriteIds,
                isCurrent = nowPlaying.station?.id == station.id,
                onPlay = { onPlay(station) },
                onFavorite = { onFavorite(station) },
            )
        }
        item { footer() }
    }
}

@Composable
private fun StationRow(
    station: RadioStation,
    isFavorite: Boolean,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onPlay)
                .background(if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        StationLogo(station, 48)
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
        IconButton(onClick = onFavorite) {
            Icon(
                if (isFavorite) MetrolistIcons.Filled.Star else MetrolistIcons.Outlined.Star,
                contentDescription =
                    stringResource(if (isFavorite) Res.string.radio_remove_favorite else Res.string.radio_add_favorite),
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
        if (station.favicon.isNotBlank()) {
            AsyncImage(model = station.favicon, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun NowPlayingBar(
    nowPlaying: RadioNowPlaying,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val station = nowPlaying.station ?: return
    Surface(tonalElevation = 3.dp, onClick = onOpen) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            StationLogo(station, 44)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(station.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail =
                    when {
                        nowPlaying.isBuffering -> stringResource(Res.string.radio_connecting)
                        else -> nowPlaying.streamTitle ?: station.qualityLabel
                    }
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FilledIconButton(onClick = onToggle) {
                val active = nowPlaying.isPlaying || nowPlaying.isBuffering
                Icon(
                    if (active) MetrolistIcons.Filled.Pause else MetrolistIcons.Filled.PlayArrow,
                    contentDescription = stringResource(if (active) Res.string.radio_stop else Res.string.radio_play),
                )
            }
        }
    }
}

@Composable
private fun CarTunerPanel(
    state: CarTunerState,
    tuner: CarTuner,
) {
    val context = LocalContext.current
    var frequency by rememberSaveable { mutableStateOf("") }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().padding(24.dp),
    ) {
        when {
            !state.hasAccess -> {
                Text(stringResource(Res.string.radio_car_tuner_access), textAlign = TextAlign.Center)
                Button(onClick = { context.startActivity(tuner.accessSettingsIntent()) }) {
                    Text(stringResource(Res.string.radio_car_tuner_grant))
                }
            }

            state.tunerPackage == null -> {
                Text(stringResource(Res.string.radio_car_tuner_none), textAlign = TextAlign.Center)
            }

            else -> {
                Text(
                    state.tunerLabel ?: state.tunerPackage,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    state.title ?: stringResource(Res.string.radio_car_tuner),
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                state.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!state.connected) {
                    Text(
                        stringResource(Res.string.radio_car_tuner_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = tuner::seekDown, modifier = Modifier.size(64.dp)) {
                        Icon(MetrolistIcons.Rounded.SkipPrevious, contentDescription = stringResource(Res.string.radio_seek_down))
                    }
                    FilledIconButton(onClick = tuner::togglePlay, modifier = Modifier.size(80.dp)) {
                        Icon(
                            if (state.isPlaying) MetrolistIcons.Filled.Pause else MetrolistIcons.Filled.PlayArrow,
                            contentDescription = stringResource(if (state.isPlaying) Res.string.radio_stop else Res.string.radio_play),
                        )
                    }
                    FilledIconButton(onClick = tuner::seekUp, modifier = Modifier.size(64.dp)) {
                        Icon(MetrolistIcons.Rounded.SkipNext, contentDescription = stringResource(Res.string.radio_seek_up))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = frequency,
                        onValueChange = { value -> frequency = value.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.radio_frequency)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { tuner.tuneTo(frequency) }, enabled = state.connected && frequency.isNotBlank()) {
                        Text(stringResource(Res.string.radio_tune))
                    }
                }
                Text(
                    stringResource(Res.string.radio_tune_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = tuner::openTunerApp) { Text(stringResource(Res.string.radio_car_tuner_open)) }
            }
        }
    }
}

private fun formatDelay(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "-%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun FullPlayer(
    nowPlaying: RadioNowPlaying,
    isFavorite: Boolean,
    onToggle: () -> Unit,
    onGoLive: () -> Unit,
    onSkip: (Int) -> Unit,
    onFavorite: () -> Unit,
) {
    val station = nowPlaying.station ?: return
    var nowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    val ticking = nowPlaying.pausedAtMs != null
    LaunchedEffect(ticking) {
        while (ticking) {
            nowMs = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val delayMs = nowPlaying.delayAt(if (ticking) nowMs else SystemClock.elapsedRealtime())
    val isLive = delayMs < 2_000L
    val active = nowPlaying.isPlaying || nowPlaying.isBuffering

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
    ) {
        StationLogo(station, 220)
        Text(
            station.displayName,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val detail =
            when {
                nowPlaying.isBuffering -> stringResource(Res.string.radio_connecting)
                else -> nowPlaying.streamTitle
            }
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        listOf(station.subtitle, station.qualityLabel).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isLive) {
                AssistChip(onClick = {}, label = { Text("● " + stringResource(Res.string.radio_live)) })
            } else {
                Text(
                    stringResource(Res.string.radio_behind_live, formatDelay(delayMs)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Button(onClick = onGoLive) { Text(stringResource(Res.string.radio_go_live)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            IconButton(onClick = onFavorite) {
                Icon(
                    if (isFavorite) MetrolistIcons.Filled.Star else MetrolistIcons.Outlined.Star,
                    contentDescription =
                        stringResource(if (isFavorite) Res.string.radio_remove_favorite else Res.string.radio_add_favorite),
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(onClick = { onSkip(-1) }, modifier = Modifier.size(56.dp)) {
                Icon(MetrolistIcons.Rounded.SkipPrevious, contentDescription = stringResource(Res.string.radio_seek_down))
            }
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(80.dp)) {
                Icon(
                    if (active) MetrolistIcons.Filled.Pause else MetrolistIcons.Filled.PlayArrow,
                    contentDescription = stringResource(if (active) Res.string.radio_stop else Res.string.radio_play),
                    modifier = Modifier.size(40.dp),
                )
            }
            FilledIconButton(onClick = { onSkip(1) }, modifier = Modifier.size(56.dp)) {
                Icon(MetrolistIcons.Rounded.SkipNext, contentDescription = stringResource(Res.string.radio_seek_up))
            }
            Spacer(Modifier.size(48.dp))
        }
    }
}
