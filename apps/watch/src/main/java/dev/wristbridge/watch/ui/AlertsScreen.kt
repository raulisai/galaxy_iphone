package dev.wristbridge.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import dev.wristbridge.notification.ActionStatus
import dev.wristbridge.protocol.ancs.ActionId
import dev.wristbridge.watch.bridge.ActiveCard

/** Notification list: Apple-Watch-like cards with app badge, title, 3-line body and iPhone action pills. */
@Composable
internal fun AlertsScreen(model: UiModel, onAction: (PresentedCard, ActionId) -> Unit) {
    val m = LocalMetrics.current
    ListScreen {
        item(key = "title") {
            ScreenTitle("Avisos", if (model.alerts.isEmpty()) null else "${model.alerts.size} en el reloj")
        }
        if (model.alerts.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = m.dp(10)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(m.dp(8)),
                ) {
                    Glyph(GlyphKind.BELL, WbColors.Fill, Modifier.size(m.dp(44)))
                    WbText("Sin avisos", style = MaterialTheme.typography.titleSmall, weight = FontWeight.SemiBold, align = TextAlign.Center)
                    Caption("Los avisos del iPhone aparecerán aquí.")
                }
            }
        } else {
            items(model.alerts, key = { "card:${it.active.card.key.tag}" }) { entry ->
                NotificationCardView(entry, model.actionsEnabled) { action -> onAction(entry, action) }
            }
        }
    }
}

/** One forwarded notification. Content is only rendered, never logged. */
@Composable
internal fun NotificationCardView(item: PresentedCard, actionsEnabled: Boolean, onAction: (ActionId) -> Unit) {
    val m = LocalMetrics.current
    val p = item.presentation
    val card = item.active.card
    val accent = Color(p.source.accentArgb)
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(m.dp(22))).background(WbColors.Card)
            .padding(horizontal = m.dp(12), vertical = m.dp(10)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(6))) {
            MonogramBadge(p.source.badge, accent, m.dp(24))
            WbText(
                p.source.label.uppercase(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = WbColors.Secondary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(m.dp(6)))
        // With hidden preview the title already is the source label; avoid repeating it.
        if (p.title != p.source.label) {
            WbText(p.title, style = MaterialTheme.typography.titleSmall, weight = FontWeight.Bold, maxLines = 2)
        }
        if (p.body.isNotBlank()) {
            WbText(p.body, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD1D1D6), maxLines = 3)
        }
        if (actionsEnabled && (card.positiveActionLabel != null || card.negativeActionLabel != null)) {
            Spacer(Modifier.height(m.dp(8)))
            Column(verticalArrangement = Arrangement.spacedBy(m.dp(5))) {
                card.positiveActionLabel?.let { label ->
                    Pill(label, { onAction(ActionId.POSITIVE) }, container = WbColors.Fill, pressed = WbColors.Secondary, maxLines = 1)
                }
                card.negativeActionLabel?.let { label ->
                    Pill(label, { onAction(ActionId.NEGATIVE) }, container = WbColors.Fill, pressed = WbColors.Secondary, textColor = WbColors.Red, maxLines = 1)
                }
            }
        }
        ActionStatusLine(item.active)
    }
}

/** Result of the last action tapped on this card; REQUESTED only means the iPhone accepted the write. */
@Composable
internal fun ActionStatusLine(active: ActiveCard) {
    val status = active.actionStatus ?: return
    val action = active.lastAction ?: return
    val name = when (action) {
        ActionId.POSITIVE -> active.card.positiveActionLabel
        ActionId.NEGATIVE -> active.card.negativeActionLabel
    } ?: "Acción"
    val m = LocalMetrics.current
    WbText(
        text = "$name: ${actionStatusLabel(status)}",
        modifier = Modifier.fillMaxWidth().padding(top = m.dp(6)),
        style = MaterialTheme.typography.labelSmall,
        color = if (status == ActionStatus.REQUESTED) WbColors.Secondary else WbColors.Red,
        align = TextAlign.Center,
        maxLines = 2,
    )
}
