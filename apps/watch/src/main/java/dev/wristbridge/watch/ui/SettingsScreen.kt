package dev.wristbridge.watch.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import dev.wristbridge.watch.BuildConfig

/** The four persisted preferences, owned by the app root so every screen sees the same state. */
internal class Prefs(
    val hidePreview: BoolPref,
    val actions: BoolPref,
    val syncDismiss: BoolPref,
    val mediaNotification: BoolPref,
)

@Composable
internal fun SettingsScreen(prefs: Prefs, onOpenDiagnostics: () -> Unit) {
    val m = LocalMetrics.current
    ListScreen {
        item(key = "title") { ScreenTitle("Ajustes") }
        item(key = "hide") { ToggleRow("Ocultar contenido", prefs.hidePreview, "Oculta textos de avisos y música") }
        item(key = "actions") { ToggleRow("Acciones en avisos", prefs.actions) }
        item(key = "sync") { ToggleRow("Borrar en iPhone al descartar", prefs.syncDismiss, "Solo avisos, nunca llamadas") }
        item(key = "media") { ToggleRow("Notificación de música", prefs.mediaNotification) }
        item(key = "diagnostics") {
            Tile(
                onClick = onOpenDiagnostics,
                glyph = GlyphKind.GEAR,
                accent = WbColors.Secondary,
                title = "Diagnóstico",
                subtitle = "v${BuildConfig.VERSION_NAME}",
                trailing = { Glyph(GlyphKind.CHEVRON, WbColors.Secondary, Modifier.size(m.dp(16))) },
            )
        }
    }
}

/** Version, counters and the redacted event log. No notification or track content is ever shown here. */
@Composable
internal fun DiagnosticsScreen(model: UiModel) {
    val bridge = model.bridge
    ListScreen {
        item(key = "title") { ScreenTitle("Diagnóstico", "Versión ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_COMMIT}") }
        item(key = "counters") {
            GroupCard {
                WbText(diagnostics(bridge), style = MaterialTheme.typography.bodyExtraSmall, color = WbColors.Secondary)
            }
        }
        // Event lines may repeat, so they are not keyed.
        items(bridge.events) { line ->
            WbText(line, modifier = androidx.compose.ui.Modifier, style = MaterialTheme.typography.bodyExtraSmall, color = WbColors.Secondary)
        }
    }
}
