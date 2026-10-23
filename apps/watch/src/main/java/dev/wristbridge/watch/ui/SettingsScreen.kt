package dev.wristbridge.watch.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import dev.wristbridge.notification.CampaignStats
import dev.wristbridge.watch.BuildConfig
import dev.wristbridge.watch.bridge.CampaignRecorder

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
    ListScreen(paneTitle = "Ajustes") {
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
    val recorder = CampaignRecorder.get(LocalContext.current)
    val campaign by recorder.stats.collectAsStateWithLifecycle()
    // Two taps on purpose: resetting wipes the evidence of a 24/72 h run.
    var resetArmed by remember { mutableStateOf(false) }
    ListScreen(paneTitle = "Diagnóstico") {
        item(key = "title") { ScreenTitle("Diagnóstico", "Versión ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_COMMIT}") }
        item(key = "counters") {
            GroupCard {
                WbText(diagnostics(bridge), style = MaterialTheme.typography.bodyExtraSmall, color = WbColors.Secondary)
            }
        }
        item(key = "savedlog") {
            val kb = remember { dev.wristbridge.watch.bridge.DiagnosticLog.totalBytes() / 1024 }
            GroupCard {
                WbText("Registro guardado", style = MaterialTheme.typography.bodySmall, color = WbColors.Label)
                WbText(
                    "$kb KB de errores y eventos, también tras reinicios. Sácalo con scripts/windows/pull-watch-logs.sh o pídelo desde el iPhone.",
                    style = MaterialTheme.typography.bodyExtraSmall, color = WbColors.Secondary,
                )
            }
        }
        item(key = "campaign") {
            GroupCard {
                WbText("Campaña", style = MaterialTheme.typography.bodySmall, color = WbColors.Label)
                WbText(campaignText(campaign), style = MaterialTheme.typography.bodyExtraSmall, color = WbColors.Secondary)
            }
        }
        item(key = "campaign-reset") {
            Pill(
                if (resetArmed) "Confirmar reinicio" else "Reiniciar registro",
                {
                    if (resetArmed) recorder.reset()
                    resetArmed = !resetArmed
                },
            )
        }
        // Event lines may repeat, so they are not keyed.
        items(bridge.events) { line ->
            WbText(line, modifier = androidx.compose.ui.Modifier, style = MaterialTheme.typography.bodyExtraSmall, color = WbColors.Secondary)
        }
    }
}

/** Campaign journal summary: counts and reconnect timings only; no notification content. */
private fun campaignText(c: CampaignStats): String {
    val runs = c.fixturesReceived.entries.take(3).joinToString(" ") { "${it.key}=${it.value}" }
    return "eventos=${c.totalEvents} descartados=${c.dropped} sesiones=${c.sessionsStarted}\n" +
        "recibidas=${c.received} act=${c.updated} retiradas iPhone=${c.removedByAncs} reloj=${c.removedByWatch}\n" +
        "duplicadas=${c.duplicates} repetidas=${c.replays} acciones=${c.actions} sinExito=${c.actionsNotRequested}\n" +
        "caídas=${c.linkDowns} reconexión p50=${duration(c.reconnectP50Ms)} p95=${duration(c.reconnectP95Ms)}\n" +
        "listo p50=${duration(c.readyP50Ms)} p95=${duration(c.readyP95Ms)}\n" +
        "mayor corte=${duration(c.longestOutageMs)} último=${duration(c.lastOutageMs)}" +
        (c.ongoingOutageMs?.let { " ahora=${duration(it)}" } ?: "") +
        (if (runs.isEmpty()) "" else "\npruebas $runs")
}

private fun duration(ms: Long?): String = when {
    ms == null -> "-"
    ms < 1_000 -> "$ms ms"
    ms < 60_000 -> "${ms / 1_000},${ms % 1_000 / 100} s"
    else -> "${ms / 60_000} min ${ms % 60_000 / 1_000} s"
}
