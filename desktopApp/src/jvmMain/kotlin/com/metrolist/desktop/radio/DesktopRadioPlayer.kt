package com.metrolist.desktop.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.constants.BottomPlayerHeight
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.radio_add_favorite
import com.metrolist.shared.generated.resources.radio_connecting
import com.metrolist.shared.generated.resources.radio_live
import com.metrolist.shared.generated.resources.radio_load_error
import com.metrolist.shared.generated.resources.radio_remove_favorite
import com.metrolist.shared.generated.resources.volume
import com.metrolist.shared.radio.RadioStation
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.PlayerPrimaryControls
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.desktop.ui.components.LiveProgressBar
import org.jetbrains.compose.resources.stringResource

// The app's bottom player while a station is the active source: same controls, same volume as the music player.
@Composable
fun RadioBottomPlayer(colorScheme: ColorScheme) {
    val station = DesktopRadio.station ?: return
    Column(Modifier.fillMaxWidth().background(colorScheme.surfaceContainer)) {
        LiveProgressBar(Modifier.fillMaxWidth(), color = colorScheme.primary)
        Row(
            modifier = Modifier.fillMaxWidth().height(BottomPlayerHeight).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StationInfo(station, colorScheme, Modifier.weight(1f), logoSize = 48.dp)
            Spacer(Modifier.width(16.dp))
            PlayerPrimaryControls(
                isPlaying = DesktopRadio.isPlaying,
                isLoading = DesktopRadio.status == DesktopRadioStatus.CONNECTING,
                playText = stringResource(Res.string.play),
                pauseText = stringResource(Res.string.pause),
                onSkipPrevious = if (DesktopRadio.canSkip) DesktopRadio::previous else null,
                onTogglePlay = DesktopRadio::toggle,
                onSkipNext = if (DesktopRadio.canSkip) DesktopRadio::next else null,
                modifier = Modifier.widthIn(max = 280.dp),
                buttonHeight = 52.dp,
                showLabels = false,
                sideButtonContainerColor = colorScheme.surfaceContainerHighest,
                sideButtonContentColor = colorScheme.onSurfaceVariant,
                centerButtonContainerColor = colorScheme.primaryContainer,
                centerButtonContentColor = colorScheme.onPrimaryContainer,
                useRoundedRect = Settings.shared.useRoundedRectPlayButton,
            )
            Spacer(Modifier.width(16.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                FavoriteButton(station)
                RadioVolume(sliderWidth = 120.dp)
                CloseButton()
            }
        }
    }
}

// Compact version for the layout that keeps the player in the title bar.
@Composable
fun RadioTopBarPlayer(
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
) {
    val station = DesktopRadio.station ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = DesktopRadio::previous, enabled = DesktopRadio.canSkip, shapes = IconButtonDefaults.shapes()) {
            Icon(MetrolistIcons.Rounded.SkipPrevious, contentDescription = stringResource(Res.string.previous))
        }
        IconButton(onClick = DesktopRadio::toggle, shapes = IconButtonDefaults.shapes()) {
            Icon(
                if (DesktopRadio.isPlaying) MetrolistIcons.Rounded.Pause else MetrolistIcons.Rounded.PlayArrow,
                contentDescription = stringResource(if (DesktopRadio.isPlaying) Res.string.pause else Res.string.play),
            )
        }
        IconButton(onClick = DesktopRadio::next, enabled = DesktopRadio.canSkip, shapes = IconButtonDefaults.shapes()) {
            Icon(MetrolistIcons.Rounded.SkipNext, contentDescription = stringResource(Res.string.next))
        }
        StationInfo(station, colorScheme, Modifier.weight(1f), logoSize = 36.dp)
        FavoriteButton(station)
        RadioVolume(sliderWidth = 96.dp)
        CloseButton()
    }
}

@Composable
private fun StationInfo(
    station: RadioStation,
    colorScheme: ColorScheme,
    modifier: Modifier,
    logoSize: Dp,
) {
    Row(
        modifier = modifier.fillMaxHeight().clickable { DesktopNavigation.navigate(Screen.Radio) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(logoSize)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colorScheme.surfaceVariant),
        ) {
            Icon(MetrolistIcons.Outlined.Radio, contentDescription = null, tint = colorScheme.onSurfaceVariant)
            if (station.favicon.isNotBlank()) AsyncImage(url = station.favicon, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                station.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val live = stringResource(Res.string.radio_live)
            val detail =
                when (DesktopRadio.status) {
                    DesktopRadioStatus.CONNECTING -> stringResource(Res.string.radio_connecting)
                    DesktopRadioStatus.ERROR -> stringResource(Res.string.radio_load_error)
                    DesktopRadioStatus.PLAYING -> listOf("● $live", station.qualityLabel).filter { it.isNotBlank() }.joinToString(" · ")
                    DesktopRadioStatus.IDLE -> station.subtitle
                }
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = if (DesktopRadio.status == DesktopRadioStatus.PLAYING) colorScheme.primary else colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FavoriteButton(station: RadioStation) {
    val favorite = DesktopRadio.isFavorite(station.id)
    IconButton(onClick = { DesktopRadio.toggleFavorite(station) }, shapes = IconButtonDefaults.shapes()) {
        Icon(
            if (favorite) MetrolistIcons.Filled.Star else MetrolistIcons.Outlined.Star,
            contentDescription = stringResource(if (favorite) Res.string.radio_remove_favorite else Res.string.radio_add_favorite),
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CloseButton() {
    IconButton(onClick = DesktopRadio::close, shapes = IconButtonDefaults.shapes()) {
        Icon(MetrolistIcons.Default.Close, contentDescription = stringResource(Res.string.close))
    }
}

// Same volume as the music player: PlayerState.volume is applied to the station too.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RadioVolume(sliderWidth: Dp) {
    val volume = PlayerState.volume
    var beforeMute by remember { mutableFloatStateOf(0.5f) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                PlayerState.setVolumeLevel((PlayerState.volume - delta * 0.05f).coerceIn(0f, 1f))
                event.changes.forEach { it.consume() }
            },
    ) {
        IconButton(
            onClick = {
                if (volume > 0f) {
                    beforeMute = volume
                    PlayerState.setVolumeLevel(0f)
                } else {
                    PlayerState.setVolumeLevel(beforeMute)
                }
            },
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(
                when {
                    volume > 0.66f -> MetrolistIcons.AutoMirrored.Filled.VolumeUp
                    volume > 0f -> MetrolistIcons.AutoMirrored.Filled.VolumeDown
                    else -> MetrolistIcons.AutoMirrored.Filled.VolumeOff
                },
                contentDescription = stringResource(Res.string.volume),
            )
        }
        Slider(value = volume, onValueChange = PlayerState::setVolumeLevel, modifier = Modifier.width(sliderWidth))
    }
}
