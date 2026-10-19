package dev.wristbridge.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import dev.wristbridge.protocol.ams.MediaState
import dev.wristbridge.protocol.ams.RemoteCommand
import dev.wristbridge.protocol.ams.formatMediaTime
import dev.wristbridge.watch.R
import dev.wristbridge.watch.bridge.AmsState
import kotlinx.coroutines.delay

/**
 * Now playing, laid out like the Spotify watch player: progress as a ring around the bezel, the app's color
 * as an ambient glow, title and artist, then flat previous/next around one big play button. The first screen
 * holds only that; volume sits one swipe below and on the crown. The screen is a plain scrolling column
 * (not a ScalingLazyColumn) so the crown is free to drive the iPhone volume instead of scrolling.
 */
@Composable
internal fun MusicScreen(model: UiModel, onCommand: (RemoteCommand) -> Unit) {
    val music = model.music
    val media = music.media
    val ready = music.ams == AmsState.READY
    val resume = if (ready) resumeCommand(media) else null
    val crown = rememberCrownVolume(music, onCommand)
    val scroll = rememberScrollState()
    val accent = playerAccent(media.playerName)

    // The button flips on tap and stays flipped until the iPhone reports a playback change (or 3 s pass),
    // so a slow Entity Update never makes a second tap send the opposite command.
    var optimistic by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(media.playback, media.playerName) { optimistic = null }
    LaunchedEffect(optimistic) {
        if (optimistic != null) {
            delay(OPTIMISTIC_MS)
            optimistic = null
        }
    }
    val playing = optimistic ?: media.isPlaying
    val send: (RemoteCommand) -> Unit = { command ->
        when (command) {
            RemoteCommand.PLAY -> optimistic = true
            RemoteCommand.PAUSE -> optimistic = false
            RemoteCommand.TOGGLE_PLAY_PAUSE -> optimistic = !playing
            else -> Unit
        }
        onCommand(command)
    }

    ScreenScaffold(scrollState = scroll) { _ ->
        BoxWithConstraints(
            Modifier.fillMaxSize().background(WbColors.Black).then(crown.modifier).semantics { paneTitle = "Música" },
        ) {
            val screen = maxHeight
            AmbientGlow(accent)
            when {
                ready && media.hasPlayer -> {
                    val elapsed = rememberElapsedSeconds(media)
                    ProgressRing(elapsed, media.durationSeconds, accent)
                    Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                        NowPlaying(model, media, elapsed, playing, accent, send, Modifier.fillMaxWidth().height(screen))
                        if (showVolume(media)) VolumeSection(media, onCommand)
                        Spacer(Modifier.height(screen * 0.2f))
                    }
                }
                resume != null -> ResumeScreen(resume, playing, send)
                else -> UnavailableScreen(music)
            }
            VolumeOverlay(crown, Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * 0.12f))
        }
    }
}

/** Main viewport: exactly one screen tall so the transport stays centred whatever sits below it. */
@Composable
private fun NowPlaying(
    model: UiModel,
    media: MediaState,
    elapsed: Double?,
    playing: Boolean,
    accent: Color,
    send: (RemoteCommand) -> Unit,
    modifier: Modifier,
) {
    val m = LocalMetrics.current
    val hiddenText = stringResource(R.string.card_hidden)
    BoxWithConstraints(modifier) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = maxWidth * 0.13f, vertical = maxHeight * 0.1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(m.dp(6), Alignment.CenterVertically),
        ) {
            PlayerChip(media.playerName, accent)
            // Title, artist and player read as one item.
            Column(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(m.dp(1)),
            ) {
                val title = if (model.hidePreview) hiddenText else media.title.ifBlank { media.playerName }
                WbText(
                    title,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_500),
                    style = MaterialTheme.typography.titleMedium, weight = FontWeight.Bold, align = TextAlign.Center, maxLines = 1,
                )
                if (!model.hidePreview && media.artist.isNotBlank()) {
                    WbText(media.artist, style = MaterialTheme.typography.bodySmall, color = WbColors.Secondary, align = TextAlign.Center, maxLines = 1)
                }
            }
            Transport(media, playing, accent, send)
            TimeLabel(elapsed, media.durationSeconds)
        }
    }
}

/** Colored dot plus the iPhone app's name, like Spotify's "playing on" line. */
@Composable
private fun PlayerChip(name: String, accent: Color) {
    val m = LocalMetrics.current
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = m.dp(8), vertical = m.dp(2)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(5)),
    ) {
        StatusDot(accent, m.dp(6))
        WbText(name, style = MaterialTheme.typography.labelSmall, color = WbColors.Label, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Flat previous/next and one filled play button in the app's color. */
@Composable
private fun Transport(media: MediaState, playing: Boolean, accent: Color, send: (RemoteCommand) -> Unit) {
    val m = LocalMetrics.current
    val playPause = playPauseCommand(media, playing)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlatButton(GlyphKind.PREVIOUS, "Anterior", canSend(media, RemoteCommand.PREVIOUS_TRACK)) { send(RemoteCommand.PREVIOUS_TRACK) }
        PlayButton(playing, accent, m.dp(62), enabled = playPause != null) { playPause?.let(send) }
        FlatButton(GlyphKind.NEXT, "Siguiente", canSend(media, RemoteCommand.NEXT_TRACK)) { send(RemoteCommand.NEXT_TRACK) }
    }
}

@Composable
private fun PlayButton(playing: Boolean, accent: Color, diameter: androidx.compose.ui.unit.Dp, enabled: Boolean = true, onClick: () -> Unit) {
    CircleButton(
        if (playing) GlyphKind.PAUSE else GlyphKind.PLAY,
        onClick,
        diameter,
        if (playing) "Pausar" else "Reproducir",
        container = accent, pressed = accent.copy(alpha = 0.7f), tint = contentOn(accent),
        enabled = enabled,
    )
}

/** Icon-only control (no disc), the Spotify look for secondary transport buttons. */
@Composable
private fun FlatButton(kind: GlyphKind, description: String, enabled: Boolean, size: Int = 44, onClick: () -> Unit) {
    val m = LocalMetrics.current
    Tappable(
        onClick = onClick,
        modifier = Modifier.size(m.dp(size).atLeastTouchTarget()),
        container = Color.Transparent,
        pressed = Color.White.copy(alpha = 0.14f),
        enabled = enabled,
        description = description,
    ) {
        Glyph(kind, WbColors.Label, Modifier.size(m.dp(size) * 0.58f))
    }
}

/** "1:05 · -2:10"; one progress node for TalkBack. */
@Composable
private fun TimeLabel(elapsed: Double?, duration: Double?) {
    val text = if (elapsed != null && duration != null) {
        formatMediaTime(elapsed) + "  ·  -" + formatMediaTime((duration - elapsed).coerceAtLeast(0.0))
    } else {
        formatMediaTime(elapsed)
    }
    WbText(
        text,
        modifier = Modifier.progressSemantics(elapsed, duration),
        style = MaterialTheme.typography.labelSmall, color = WbColors.Secondary, align = TextAlign.Center, maxLines = 1,
    )
}

/** Track progress drawn around the round bezel. */
@Composable
private fun ProgressRing(elapsed: Double?, duration: Double?, accent: Color) {
    val m = LocalMetrics.current
    val fraction = if (elapsed != null && duration != null && duration > 0.0) (elapsed / duration).toFloat().coerceIn(0f, 1f) else 0f
    Canvas(Modifier.fillMaxSize().padding(m.dp(3)).decorative()) {
        val stroke = m.dp(4).toPx()
        val topLeft = Offset(stroke / 2, stroke / 2)
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(Color.White.copy(alpha = 0.16f), -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        if (fraction > 0f) {
            drawArc(accent, -90f, 360f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** Soft wash of the app's color from the top, fading to black. */
@Composable
private fun AmbientGlow(accent: Color) {
    Box(
        Modifier.fillMaxSize().decorative().background(
            Brush.verticalGradient(
                0f to accent.copy(alpha = 0.45f),
                0.55f to accent.copy(alpha = 0.1f),
                1f to Color.Black,
            ),
        ),
    )
}

/** Below the fold: flat minus/plus around the iPhone volume level. The crown does the same. */
@Composable
private fun VolumeSection(media: MediaState, onCommand: (RemoteCommand) -> Unit) {
    val m = LocalMetrics.current
    Column(
        Modifier.fillMaxWidth(0.78f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.dp(4)),
    ) {
        WbText("Volumen del iPhone", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.labelSmall, color = WbColors.Secondary, align = TextAlign.Center, maxLines = 1)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FlatButton(GlyphKind.MINUS, "Bajar volumen", media.supports(RemoteCommand.VOLUME_DOWN), size = 32) { onCommand(RemoteCommand.VOLUME_DOWN) }
            Box(Modifier.weight(1f).volumeSemantics(media.volume)) {
                LevelBar((media.volume ?: 0.0).toFloat(), WbColors.Label, Modifier.fillMaxWidth())
            }
            FlatButton(GlyphKind.PLUS, "Subir volumen", media.supports(RemoteCommand.VOLUME_UP), size = 32) { onCommand(RemoteCommand.VOLUME_UP) }
        }
        media.volume?.let { WbText(volumePercent(it), style = MaterialTheme.typography.labelSmall, color = WbColors.Secondary, align = TextAlign.Center, maxLines = 1) }
    }
}

/** Nothing owns now-playing but the iPhone still accepts PLAY: offer to resume the last app. */
@Composable
private fun ResumeScreen(command: RemoteCommand, playing: Boolean, send: (RemoteCommand) -> Unit) {
    val m = LocalMetrics.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = m.dp(24)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.dp(8), Alignment.CenterVertically),
    ) {
        WbText("Nada en reproducción", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, weight = FontWeight.Bold, align = TextAlign.Center, maxLines = 2)
        PlayButton(playing, WbColors.Label, m.dp(62)) { send(command) }
        Caption("Reanuda la última app de música del iPhone", maxLines = 2)
    }
}

@Composable
private fun UnavailableScreen(music: dev.wristbridge.watch.bridge.MediaUiState) {
    val m = LocalMetrics.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = m.dp(24)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.dp(8), Alignment.CenterVertically),
    ) {
        Glyph(GlyphKind.NOTE, WbColors.Fill, Modifier.size(m.dp(44)))
        WbText("Música", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, weight = FontWeight.SemiBold, align = TextAlign.Center)
        Caption(musicUnavailableLine(music))
    }
}

/** Thin rounded bar; [fraction] is clamped to 0..1. */
@Composable
internal fun LevelBar(fraction: Float, fill: Color, modifier: Modifier = Modifier) {
    val m = LocalMetrics.current
    Box(modifier.height(m.dp(5)).clip(CircleShape).background(WbColors.Fill)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(fill))
    }
}

/** Brand color of the iPhone's now-playing app (AMS only gives its display name); white otherwise. */
internal fun playerAccent(playerName: String): Color {
    val n = playerName.lowercase()
    return when {
        "spotify" in n -> Color(0xFF1DB954)
        "netflix" in n -> Color(0xFFE50914)
        "youtube" in n -> Color(0xFFFF0033)
        "podcast" in n -> Color(0xFF9B5CFF)
        "music" in n || "música" in n -> WbColors.Pink
        else -> WbColors.Label
    }
}

/** Black glyph on light fills (Spotify green, white), white on dark ones (Netflix red). */
private fun contentOn(fill: Color): Color = if (fill.luminance() > 0.3f) Color.Black else Color.White

private const val OPTIMISTIC_MS = 3_000L
