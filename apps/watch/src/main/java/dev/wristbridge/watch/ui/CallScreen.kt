package dev.wristbridge.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.coerceIn
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import dev.wristbridge.protocol.ancs.ActionId

/**
 * Full-screen takeover for a ringing call. Labels of the answer/decline buttons come from the iPhone
 * (ANCS action labels); the caller text honours the hide-preview setting through [PresentedCard].
 */
@Composable
internal fun CallScreen(
    call: PresentedCard,
    actionsEnabled: Boolean,
    onAction: (ActionId) -> Unit,
    onMinimize: () -> Unit,
) {
    val m = LocalMetrics.current
    val p = call.presentation
    val card = call.active.card
    val scroll = rememberScrollState()
    ScreenScaffold(scrollState = scroll) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val diameter = (minOf(maxWidth, maxHeight) * 0.27f).coerceIn(m.dp(44), m.dp(72))
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(scroll)
                    .padding(padding).padding(horizontal = m.dp(10)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.dp(3))) {
                    WbText(
                        p.title, style = MaterialTheme.typography.labelMedium, color = WbColors.Green,
                        weight = FontWeight.SemiBold, align = TextAlign.Center, maxLines = 1,
                    )
                    WbText(
                        p.body.ifBlank { p.source.label },
                        style = MaterialTheme.typography.titleMedium, weight = FontWeight.Bold,
                        align = TextAlign.Center, maxLines = 3,
                    )
                    WbText(p.source.label, style = MaterialTheme.typography.bodySmall, color = WbColors.Secondary, align = TextAlign.Center, maxLines = 1)
                }
                if (actionsEnabled) {
                    Row(Modifier.fillMaxWidth().padding(vertical = m.dp(8)), horizontalArrangement = Arrangement.SpaceEvenly) {
                        CallChoice(
                            label = card.negativeActionLabel ?: "Rechazar", kind = GlyphKind.PHONE_DOWN,
                            color = WbColors.Red, diameter = diameter, enabled = card.negativeActionLabel != null,
                            modifier = Modifier.weight(1f),
                        ) { onAction(ActionId.NEGATIVE) }
                        CallChoice(
                            label = card.positiveActionLabel ?: "Aceptar", kind = GlyphKind.PHONE,
                            color = WbColors.Green, diameter = diameter, enabled = card.positiveActionLabel != null,
                            modifier = Modifier.weight(1f),
                        ) { onAction(ActionId.POSITIVE) }
                    }
                } else {
                    Caption("Acciones desactivadas en Ajustes")
                }
                ActionStatusLine(call.active)
                Pill("Ocultar", onMinimize, container = WbColors.Card, textColor = WbColors.Secondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CallChoice(
    label: String,
    kind: GlyphKind,
    color: androidx.compose.ui.graphics.Color,
    diameter: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val m = LocalMetrics.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.dp(3))) {
        CircleButton(kind, onClick, diameter, label, container = color, pressed = color.copy(alpha = 0.7f), tint = androidx.compose.ui.graphics.Color.White, enabled = enabled)
        WbText(label, style = MaterialTheme.typography.labelSmall, color = WbColors.Label, align = TextAlign.Center, maxLines = 2)
    }
}
