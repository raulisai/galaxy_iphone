package dev.wristbridge.watch.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wristbridge.notification.AccessibilityText
import dev.wristbridge.notification.AccessibilityText.StatusKind

/**
 * TalkBack glue (P3-01/P3-02, UI03). The Spanish strings live in the pure, unit-tested
 * [AccessibilityText] (core:notification-domain); this file only maps UI types onto it and wraps the
 * Compose semantics modifiers so call sites stay short.
 */

/** Minimum touch target recommended for Wear OS (48 dp). */
internal val MinTouchTarget: Dp = 48.dp

/** Grows a scaled size up to the minimum touch target; sizes already at or above it are unchanged. */
internal fun Dp.atLeastTouchTarget(): Dp = if (this < MinTouchTarget) MinTouchTarget else this

/** Status text that changes over time: announced politely, without interrupting the current speech. */
internal fun Modifier.politeLiveRegion(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** Purely decorative element (glyph, dot, badge, artwork): invisible to screen readers. */
internal fun Modifier.decorative(): Modifier = clearAndSetSemantics { }

/** A status line read as one item with an explicit text, never relying on a colour. */
internal fun Modifier.statusSemantics(h: Headline): Modifier = semantics(mergeDescendants = true) {
    contentDescription = describeStatus(h)
    liveRegion = LiveRegionMode.Polite
}

/** Progress of the current track as a range (0..1) plus a spoken "1 minuto y 5 segundos de 3 minutos". */
internal fun Modifier.progressSemantics(elapsed: Double?, duration: Double?): Modifier = clearAndSetSemantics {
    contentDescription = "Progreso de reproducción"
    stateDescription = AccessibilityText.describeProgress(elapsed, duration)
    progressBarRangeInfo = ProgressBarRangeInfo(AccessibilityText.progressFraction(elapsed, duration), 0f..1f)
}

/** iPhone volume level bar (0..1) described as a percentage. */
internal fun Modifier.volumeSemantics(volume: Double?): Modifier = clearAndSetSemantics {
    contentDescription = "Volumen"
    stateDescription = AccessibilityText.describeVolume(volume)
    progressBarRangeInfo = ProgressBarRangeInfo((volume ?: 0.0).coerceIn(0.0, 1.0).toFloat(), 0f..1f)
}

internal fun describeStatus(h: Headline): String = AccessibilityText.describeStatus(
    h.text,
    when (h.tone) {
        Tone.OK -> StatusKind.OK
        Tone.NEUTRAL -> StatusKind.NEUTRAL
        Tone.ERROR -> StatusKind.ERROR
    },
)
