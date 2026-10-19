package dev.wristbridge.watch.ui

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.stateDescription
import dev.wristbridge.notification.AccessibilityText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold

/** Pressable surface with an Apple-like pressed tint instead of a ripple. */
@Composable
internal fun Tappable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    container: Color = WbColors.Card,
    pressed: Color = WbColors.CardHigh,
    enabled: Boolean = true,
    description: String? = null,
    stateDescription: String? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (isPressed && enabled) pressed else container)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .then(
                if (description != null || stateDescription != null) {
                    Modifier.semantics {
                        if (description != null) contentDescription = description
                        if (stateDescription != null) this.stateDescription = stateDescription
                    }
                } else Modifier,
            ),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** Colored circle with a white glyph, like an app icon. */
@Composable
internal fun IconBadge(kind: GlyphKind, accent: Color, diameter: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier.size(diameter).clip(CircleShape).background(accent).decorative(),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(kind, Color.White, Modifier.size(diameter * 0.56f))
    }
}

/** Circle with a text monogram (notification app badge). */
@Composable
internal fun MonogramBadge(text: String, accent: Color, diameter: androidx.compose.ui.unit.Dp) {
    // The card already reads the app name; the 1-3 letter monogram would only be spelled out noise.
    Box(
        modifier = Modifier.size(diameter).clip(CircleShape).background(accent).decorative(),
        contentAlignment = Alignment.Center,
    ) {
        WbText(
            text = text.take(3).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            weight = FontWeight.Bold,
            align = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
internal fun StatusDot(color: Color, diameter: androidx.compose.ui.unit.Dp) {
    // Colour is never the only carrier: every dot sits next to a sentence that states the same status.
    Box(Modifier.size(diameter).clip(CircleShape).background(color).decorative())
}

/** Hub row of the Home screen: icon badge, title, secondary line and optional trailing content. */
@Composable
internal fun Tile(
    onClick: () -> Unit,
    glyph: GlyphKind,
    accent: Color,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    subtitleLines: Int = 1,
    dot: Color? = null,
    container: Color = WbColors.Card,
    pressed: Color = WbColors.CardHigh,
    titleColor: Color = WbColors.Label,
    subtitleColor: Color = WbColors.Secondary,
    trailing: (@Composable () -> Unit)? = null,
    /** Replaces the merged title + subtitle reading (for example a status sentence with a tone prefix). */
    description: String? = null,
    /** True when the subtitle is a status that changes over time: announced politely. */
    liveStatus: Boolean = false,
) {
    val m = LocalMetrics.current
    Tappable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = m.dp(60).atLeastTouchTarget())
            .then(if (liveStatus) Modifier.politeLiveRegion() else Modifier),
        description = description,
        shape = RoundedCornerShape(m.dp(26)),
        container = container,
        pressed = pressed,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = m.dp(10), vertical = m.dp(8)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(8)),
        ) {
            IconBadge(glyph, accent, m.dp(34))
            Column(Modifier.weight(1f)) {
                WbText(title, style = MaterialTheme.typography.titleSmall, color = titleColor, weight = FontWeight.SemiBold, maxLines = 1)
                if (subtitle != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(5))) {
                        if (dot != null) StatusDot(dot, m.dp(8))
                        WbText(
                            subtitle,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodySmall,
                            color = subtitleColor,
                            maxLines = subtitleLines,
                        )
                    }
                }
            }
            if (trailing != null) trailing()
        }
    }
}

/** Round count badge (Apple red). */
@Composable
internal fun CountBadge(count: Int) {
    val m = LocalMetrics.current
    Box(
        modifier = Modifier.sizeIn(minWidth = m.dp(22), minHeight = m.dp(22)).clip(CircleShape).background(WbColors.Red)
            .padding(horizontal = m.dp(5))
            .clearAndSetSemantics { contentDescription = AccessibilityText.describeCount(count) },
        contentAlignment = Alignment.Center,
    ) {
        WbText(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelMedium,
            weight = FontWeight.Bold,
            align = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** Large rounded pill button (full width). */
@Composable
internal fun Pill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = WbColors.CardHigh,
    pressed: Color = WbColors.Fill,
    textColor: Color = WbColors.Label,
    enabled: Boolean = true,
    maxLines: Int = 2,
    /** State of a button that behaves as a toggle, e.g. AccessibilityText.ON / OFF. */
    stateDescription: String? = null,
) {
    val m = LocalMetrics.current
    Tappable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = m.dp(48).atLeastTouchTarget()),
        shape = RoundedCornerShape(percent = 50),
        container = container,
        pressed = pressed,
        enabled = enabled,
        stateDescription = stateDescription,
    ) {
        WbText(
            text = label,
            modifier = Modifier.padding(horizontal = m.dp(14), vertical = m.dp(8)),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            weight = FontWeight.SemiBold,
            align = TextAlign.Center,
            maxLines = maxLines,
        )
    }
}

/** Round icon button (transport, volume, call). */
@Composable
internal fun CircleButton(
    kind: GlyphKind,
    onClick: () -> Unit,
    diameter: androidx.compose.ui.unit.Dp,
    description: String,
    modifier: Modifier = Modifier,
    container: Color = WbColors.CardHigh,
    pressed: Color = WbColors.Fill,
    tint: Color = WbColors.Label,
    enabled: Boolean = true,
) {
    Tappable(
        onClick = onClick,
        modifier = modifier.size(diameter.atLeastTouchTarget()),
        shape = CircleShape,
        container = container,
        pressed = pressed,
        enabled = enabled,
        description = description,
    ) {
        Glyph(kind, tint, Modifier.size(diameter.atLeastTouchTarget() * 0.5f))
    }
}

/** Screen title (big, bold) with an optional grey caption below. */
@Composable
internal fun ScreenTitle(title: String, caption: String? = null, liveCaption: Boolean = false) {
    val m = LocalMetrics.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = m.dp(2))
            .semantics(mergeDescendants = true) { heading() }
            .then(if (liveCaption) Modifier.politeLiveRegion() else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WbText(
            title,
            style = MaterialTheme.typography.titleLarge,
            weight = FontWeight.Bold,
            align = TextAlign.Center,
            maxLines = 1,
        )
        if (caption != null) {
            WbText(caption, style = MaterialTheme.typography.bodySmall, color = WbColors.Secondary, align = TextAlign.Center, maxLines = 2)
        }
    }
}

/** Caption line / empty-state text. */
@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier, color: Color = WbColors.Secondary, maxLines: Int = 4) {
    val m = LocalMetrics.current
    WbText(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = m.dp(6)),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        align = TextAlign.Center,
        maxLines = maxLines,
    )
}

/** Dark card container for grouped content. */
@Composable
internal fun GroupCard(modifier: Modifier = Modifier, mergeForReader: Boolean = true, content: @Composable () -> Unit) {
    val m = LocalMetrics.current
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(m.dp(22))).background(WbColors.Card)
            .padding(horizontal = m.dp(12), vertical = m.dp(10))
            .then(if (mergeForReader) Modifier.semantics(mergeDescendants = true) { } else Modifier),
        verticalArrangement = Arrangement.spacedBy(m.dp(6)),
    ) { content() }
}

/** Setting row with an Apple-style toggle. */
@Composable
internal fun ToggleRow(label: String, pref: BoolPref, secondary: String? = null) {
    val m = LocalMetrics.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = m.dp(56).atLeastTouchTarget()).clip(RoundedCornerShape(m.dp(22))).background(WbColors.Card)
            .toggleable(value = pref.value, role = Role.Switch, onValueChange = { pref.set(it) })
            .semantics { stateDescription = AccessibilityText.switchState(pref.value) }
            .padding(horizontal = m.dp(12), vertical = m.dp(8)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(8)),
    ) {
        Column(Modifier.weight(1f)) {
            WbText(label, style = MaterialTheme.typography.bodyMedium, weight = FontWeight.SemiBold, maxLines = 3)
            if (secondary != null) {
                WbText(secondary, style = MaterialTheme.typography.bodyExtraSmall, color = WbColors.Secondary, maxLines = 2)
            }
        }
        ToggleSwitch(pref.value)
    }
}

@Composable
private fun ToggleSwitch(on: Boolean) {
    val m = LocalMetrics.current
    val trackW = m.dp(40)
    val trackH = m.dp(24)
    val pad = m.dp(2)
    val thumb = trackH - pad * 2
    // The row announces "Activado"/"Desactivado"; the thumb position (not only the colour) shows it visually.
    Box(
        modifier = Modifier.width(trackW).heightIn(min = trackH, max = trackH).clip(CircleShape)
            .background(if (on) WbColors.Green else WbColors.Fill).decorative(),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(horizontal = pad).size(thumb).clip(CircleShape).background(Color.White))
    }
}

/**
 * Standard scrolling screen: scaffold (clock + scroll indicator) around a scaling column whose side and
 * edge insets scale with the watch width. Rotary / crown scrolling comes from the column's defaults.
 */
@Composable
internal fun ListScreen(paneTitle: String? = null, content: ScalingLazyListScope.() -> Unit) {
    val m = LocalMetrics.current
    val state = rememberScalingLazyListState()
    ScreenScaffold(scrollState = state) { padding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize()
                .then(if (paneTitle != null) Modifier.semantics { this.paneTitle = paneTitle } else Modifier),
            state = state,
            contentPadding = PaddingValues(
                start = m.dp(14),
                end = m.dp(14),
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(m.dp(8)),
            autoCentering = null,
            content = content,
        )
    }
}
