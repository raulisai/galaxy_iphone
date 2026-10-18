package dev.wristbridge.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.MaterialTheme
import dev.wristbridge.protocol.ams.MediaState
import dev.wristbridge.protocol.ams.RemoteCommand
import dev.wristbridge.protocol.ams.formatMediaTime
import dev.wristbridge.watch.R
import dev.wristbridge.watch.bridge.AmsState

/** Now playing: artwork-like badge, title/artist, progress, transport and volume. */
@Composable
internal fun MusicScreen(model: UiModel, onCommand: (RemoteCommand) -> Unit) {
    val m = LocalMetrics.current
    val music = model.music
    val media = music.media
    val playerVisible = music.ams == AmsState.READY && media.hasPlayer
    val hiddenText = stringResource(R.string.card_hidden)

    ListScreen {
        if (!playerVisible) {
            item(key = "empty") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = m.dp(10)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(m.dp(8)),
                ) {
                    Glyph(GlyphKind.NOTE, WbColors.Fill, Modifier.size(m.dp(44)))
                    WbText("Música", style = MaterialTheme.typography.titleSmall, weight = FontWeight.SemiBold, align = TextAlign.Center)
                    Caption(musicUnavailableLine(music))
                }
            }
            return@ListScreen
        }

        item(key = "art") { Artwork() }
        item(key = "info") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (model.hidePreview) {
                    WbText(hiddenText, style = MaterialTheme.typography.titleSmall, weight = FontWeight.Bold, align = TextAlign.Center, maxLines = 2)
                } else {
                    if (media.title.isNotBlank()) {
                        WbText(media.title, style = MaterialTheme.typography.titleSmall, weight = FontWeight.Bold, align = TextAlign.Center, maxLines = 2)
                    }
                    if (media.artist.isNotBlank()) {
                        WbText(media.artist, style = MaterialTheme.typography.bodySmall, color = WbColors.Pink, align = TextAlign.Center, maxLines = 1)
                    }
                }
                WbText(media.playerName, style = MaterialTheme.typography.labelSmall, color = WbColors.Secondary, align = TextAlign.Center, maxLines = 1)
            }
        }
        item(key = "progress") { Progress(media) }
        item(key = "transport") { Transport(media, onCommand) }
        item(key = "volume") {
            if (showVolume(media)) {
                VolumeBar(media, onCommand)
            } else {
                media.volume?.let { Caption("Volumen ${volumePercent(it)}") }
            }
        }
    }
}

@Composable
private fun Artwork() {
    val m = LocalMetrics.current
    Box(
        modifier = Modifier.size(m.dp(72)).clip(RoundedCornerShape(m.dp(18)))
            .background(Brush.linearGradient(listOf(WbColors.Pink, Color(0xFF7D2AE8)))),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(GlyphKind.NOTE, Color.White, Modifier.size(m.dp(34)))
    }
}

/** Thin rounded bar; [fraction] is clamped to 0..1. */
@Composable
private fun LevelBar(fraction: Float, fill: Color, modifier: Modifier = Modifier) {
    val m = LocalMetrics.current
    Box(modifier.fillMaxWidth().height(m.dp(4)).clip(CircleShape).background(WbColors.Fill)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(fill))
    }
}

/** Progress bar and times, ticking once per second while playing (rememberElapsedSeconds). */
@Composable
private fun Progress(media: MediaState) {
    val m = LocalMetrics.current
    val elapsed = rememberElapsedSeconds(media)
    val duration = media.durationSeconds
    val fraction = if (elapsed != null && duration != null && duration > 0.0) (elapsed / duration).toFloat() else 0f
    Column(Modifier.fillMaxWidth().padding(horizontal = m.dp(8)), verticalArrangement = Arrangement.spacedBy(m.dp(3))) {
        LevelBar(fraction, Color.White)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            WbText(formatMediaTime(elapsed), style = MaterialTheme.typography.labelSmall, color = WbColors.Secondary, maxLines = 1)
            if (elapsed != null && duration != null) {
                WbText("-" + formatMediaTime((duration - elapsed).coerceAtLeast(0.0)), style = MaterialTheme.typography.labelSmall, color = WbColors.Secondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Transport(media: MediaState, onCommand: (RemoteCommand) -> Unit) {
    val m = LocalMetrics.current
    val playPause = playPauseCommand(media)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(
            GlyphKind.PREVIOUS, { onCommand(RemoteCommand.PREVIOUS_TRACK) }, m.dp(46), "Anterior",
            enabled = canSend(media, RemoteCommand.PREVIOUS_TRACK),
        )
        CircleButton(
            if (media.isPlaying) GlyphKind.PAUSE else GlyphKind.PLAY,
            { playPause?.let(onCommand) },
            m.dp(60),
            if (media.isPlaying) "Pausar" else "Reproducir",
            container = Color.White, pressed = WbColors.Secondary, tint = Color.Black,
            enabled = playPause != null,
        )
        CircleButton(
            GlyphKind.NEXT, { onCommand(RemoteCommand.NEXT_TRACK) }, m.dp(46), "Siguiente",
            enabled = canSend(media, RemoteCommand.NEXT_TRACK),
        )
    }
}

@Composable
private fun VolumeBar(media: MediaState, onCommand: (RemoteCommand) -> Unit) {
    val m = LocalMetrics.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(m.dp(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(
            GlyphKind.MINUS, { onCommand(RemoteCommand.VOLUME_DOWN) }, m.dp(40), "Bajar volumen",
            enabled = media.supports(RemoteCommand.VOLUME_DOWN),
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.dp(3))) {
            LevelBar((media.volume ?: 0.0).toFloat(), WbColors.Label)
            media.volume?.let {
                WbText(volumePercent(it), style = MaterialTheme.typography.labelSmall, color = WbColors.Secondary, align = TextAlign.Center, maxLines = 1)
            }
        }
        CircleButton(
            GlyphKind.PLUS, { onCommand(RemoteCommand.VOLUME_UP) }, m.dp(40), "Subir volumen",
            enabled = media.supports(RemoteCommand.VOLUME_UP),
        )
    }
}
