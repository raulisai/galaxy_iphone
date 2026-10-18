package dev.wristbridge.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import dev.wristbridge.protocol.ams.RemoteCommand
import dev.wristbridge.protocol.ancs.ActionId
import dev.wristbridge.watch.bridge.MediaUiState

/**
 * Full-screen takeover for a ringing call. Labels of the answer/decline buttons come from the iPhone
 * (ANCS action labels); the caller text honours the hide-preview setting through [PresentedCard].
 * The crown changes the iPhone volume through AMS; there are no microphone or in-call audio controls.
 */
@Composable
internal fun CallScreen(
    call: PresentedCard,
    music: MediaUiState,
    actionsEnabled: Boolean,
    onAction: (ActionId) -> Unit,
    onMedia: (RemoteCommand) -> Unit,
    onMinimize: () -> Unit,
) {
    val m = LocalMetrics.current
    val p = call.presentation
    val card = call.active.card
    val scroll = rememberScrollState()
    val crown = rememberCrownVolume(music, onMedia)
    ScreenScaffold(scrollState = scroll) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().then(crown.modifier)) {
            // Round bezel safe area: about 12% at the top/bottom and 9% at the sides.
            val sideInset = maxWidth * 0.09f
            val vertInset = maxHeight * 0.06f
            val diameter: Dp = m.dp(64)
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(scroll)
                    .padding(padding).padding(horizontal = sideInset, vertical = vertInset),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(m.dp(8), Alignment.CenterVertically),
            ) {
                // App badge (source) on top, then the caller in large type.
                WbText(
                    p.source.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall, color = WbColors.Green,
                    weight = FontWeight.SemiBold, align = TextAlign.Center, maxLines = 1,
                )
                WbText(
                    p.body.ifBlank { p.title },
                    style = MaterialTheme.typography.titleMedium, weight = FontWeight.Bold,
                    align = TextAlign.Center, maxLines = 2,
                )
                if (actionsEnabled) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = m.dp(4)),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Top,
                    ) {
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
                if (crown.active) Caption("Gira la corona para el volumen", maxLines = 2)
                Pill("Ocultar", onMinimize, container = WbColors.Card, textColor = WbColors.Secondary, maxLines = 1)
                // Spare room so the last button clears the bottom bezel when scrolled.
                Box(Modifier.padding(bottom = m.dp(6)))
            }
            VolumeOverlay(crown, Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * 0.12f))
        }
    }
}

@Composable
private fun CallChoice(
    label: String,
    kind: GlyphKind,
    color: Color,
    diameter: Dp,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val m = LocalMetrics.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.dp(4))) {
        CircleButton(kind, onClick, diameter, label, container = color, pressed = color.copy(alpha = 0.7f), tint = Color.White, enabled = enabled)
        WbText(label, style = MaterialTheme.typography.labelSmall, color = WbColors.Label, align = TextAlign.Center, maxLines = 2)
    }
}
