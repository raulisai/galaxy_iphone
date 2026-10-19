package dev.wristbridge.watch.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.wristbridge.notification.AccessibilityText
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.MaterialTheme
import dev.wristbridge.protocol.ams.RemoteCommand
import dev.wristbridge.watch.bridge.AmsState
import dev.wristbridge.watch.bridge.MediaUiState
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val CROWN_STEP_PX = 40f
private const val CROWN_MIN_INTERVAL_MS = 125L
private const val INDICATOR_MS = 1_800L

/** Result of [rememberCrownVolume]: apply [modifier] to the screen root and draw [VolumeOverlay] on top. */
internal class CrownVolume(
    val modifier: Modifier,
    /** True when the crown can currently change the iPhone volume (AMS ready, volume commands usable). */
    val active: Boolean,
    val indicatorVisible: Boolean,
    val volume: Double?,
)

private class CrownAccumulator {
    var pixels = 0f
    var lastSentMs = 0L
}

private fun volumeUsable(music: MediaUiState, command: RemoteCommand): Boolean =
    music.ams == AmsState.READY &&
        (music.media.supportedCommands.isEmpty() || music.media.supports(command))

/**
 * Rotary crown to iPhone volume (AMS VOLUME_UP/VOLUME_DOWN). Scroll pixels are accumulated and one
 * command is emitted per [CROWN_STEP_PX], at most about 8 per second. The indicator is shown briefly
 * after every crown tick and whenever the reported volume changes.
 */
@Composable
internal fun rememberCrownVolume(music: MediaUiState, onCommand: (RemoteCommand) -> Unit): CrownVolume {
    val haptic = LocalHapticFeedback.current
    val focus = remember { FocusRequester() }
    val acc = remember { CrownAccumulator() }
    val currentMusic by rememberUpdatedState(music)
    val currentSend by rememberUpdatedState(onCommand)
    var bump by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    val initialVolume = remember { music.media.volume }
    val volume = music.media.volume

    LaunchedEffect(Unit) {
        // The node may not be attached yet on the very first frame; the crown simply stays inert then.
        runCatching { focus.requestFocus() }
    }
    LaunchedEffect(volume, bump) {
        if (bump == 0 && volume == initialVolume) return@LaunchedEffect
        visible = true
        delay(INDICATOR_MS)
        visible = false
    }

    val modifier = Modifier
        .onRotaryScrollEvent { event ->
            val state = currentMusic
            if (!volumeUsable(state, RemoteCommand.VOLUME_UP) && !volumeUsable(state, RemoteCommand.VOLUME_DOWN)) {
                return@onRotaryScrollEvent false
            }
            val delta = event.verticalScrollPixels
            if (acc.pixels != 0f && (delta > 0f) != (acc.pixels > 0f)) acc.pixels = 0f
            acc.pixels += delta
            if (abs(acc.pixels) >= CROWN_STEP_PX) {
                val command = if (acc.pixels > 0f) RemoteCommand.VOLUME_UP else RemoteCommand.VOLUME_DOWN
                // Keep only the remainder; a fast spin collapses into one command per interval.
                acc.pixels %= CROWN_STEP_PX
                val now = SystemClock.elapsedRealtime()
                if (volumeUsable(state, command) && now - acc.lastSentMs >= CROWN_MIN_INTERVAL_MS) {
                    acc.lastSentMs = now
                    currentSend(command)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    bump++
                }
            }
            true
        }
        .focusRequester(focus)
        .focusable()

    val active = volumeUsable(music, RemoteCommand.VOLUME_UP) || volumeUsable(music, RemoteCommand.VOLUME_DOWN)
    return CrownVolume(modifier, active, visible, volume)
}

/** Brief "Volumen NN %" pill with a level bar; place it at the bottom of a Box above the content. */
@Composable
internal fun VolumeOverlay(crown: CrownVolume, modifier: Modifier = Modifier) {
    if (!crown.indicatorVisible) return
    val m = LocalMetrics.current
    Column(
        modifier = modifier.clip(RoundedCornerShape(m.dp(18))).background(WbColors.CardHigh)
            .padding(horizontal = m.dp(14), vertical = m.dp(8))
            // Appears for ~2 s after every crown tick: announced politely as "Volumen 50 por ciento".
            .semantics(mergeDescendants = true) {
                contentDescription = AccessibilityText.describeVolume(crown.volume)
                liveRegion = LiveRegionMode.Polite
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.dp(4)),
    ) {
        WbText(
            text = crown.volume?.let { "Volumen ${volumePercent(it)}" } ?: "Volumen",
            style = MaterialTheme.typography.labelMedium,
            weight = FontWeight.SemiBold,
            align = TextAlign.Center,
            maxLines = 1,
        )
        crown.volume?.let { LevelBar(it.toFloat(), WbColors.Label, Modifier.width(m.dp(96))) }
    }
}
