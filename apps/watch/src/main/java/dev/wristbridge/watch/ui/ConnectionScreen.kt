package dev.wristbridge.watch.ui

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.MaterialTheme
import dev.wristbridge.watch.R
import dev.wristbridge.watch.bridge.BridgeService
import kotlinx.coroutines.delay

/** Live connection state in plain language, bridge controls and per-link details. */
@Composable
internal fun ConnectionScreen(
    model: UiModel,
    onBridgeCommand: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenPairing: () -> Unit,
) {
    val m = LocalMetrics.current
    val bridge = model.bridge
    val color = statusColor(model.headline, bridge.serviceRunning)

    ListScreen(paneTitle = "Conexión") {
        item(key = "status") {
            Column(
                // Headline = the screen's title and a live status; the dot colour is never the only signal.
                modifier = Modifier.fillMaxWidth().padding(bottom = m.dp(2))
                    .statusSemantics(model.headline).semantics { heading() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(m.dp(6)),
            ) {
                StatusDot(color, m.dp(14))
                WbText(
                    model.headline.text,
                    style = MaterialTheme.typography.titleMedium,
                    weight = FontWeight.Bold,
                    align = TextAlign.Center,
                    maxLines = 3,
                )
            }
        }

        if (model.permissionsMissing) {
            item(key = "permissions") {
                Pill(stringResource(R.string.grant_permissions), onRequestPermissions, container = WbColors.Amber, pressed = WbColors.Amber, textColor = androidx.compose.ui.graphics.Color.Black)
            }
        }
        if (bridge.serviceRunning) {
            item(key = "stop") {
                Pill(stringResource(R.string.stop_bridge), { onBridgeCommand(BridgeService.ACTION_STOP) }, container = WbColors.Red, pressed = WbColors.Red.copy(alpha = 0.7f), textColor = androidx.compose.ui.graphics.Color.White)
            }
            item(key = "recover") {
                Pill(stringResource(R.string.recover), { onBridgeCommand(BridgeService.ACTION_RECOVER) })
            }
            item(key = "use-name") {
                Pill(stringResource(R.string.use_proposed_name), { onBridgeCommand(BridgeService.ACTION_USE_PROPOSED_NAME) })
            }
            item(key = "restore-name") {
                Pill(stringResource(R.string.restore_name), { onBridgeCommand(BridgeService.ACTION_RESTORE_NAME) })
            }
        } else {
            item(key = "start") {
                Pill(
                    stringResource(R.string.start_bridge), { onBridgeCommand(BridgeService.ACTION_START) },
                    container = WbColors.Green, pressed = WbColors.Green.copy(alpha = 0.7f), textColor = androidx.compose.ui.graphics.Color.Black,
                    enabled = model.bluetoothGranted,
                )
            }
        }

        item(key = "pairing") { Pill("Vincular iPhone (salud)", onOpenPairing) }

        item(key = "details") {
            GroupCard(mergeForReader = false) {
                DetailRow("Puente", if (bridge.serviceRunning) "Activo" else "Detenido")
                DetailRow("Bluetooth", radioLabel(bridge.radio))
                DetailRow(
                    "Canal propio", ownLabel(bridge),
                    listOfNotNull(
                        bridge.advertisedName?.let { "Nombre anunciado: $it" },
                        bridge.ownChannelDetail.takeIf { it.isNotBlank() },
                    ).joinToString("\n"),
                )
                DetailRow("Vínculo", bondLabel(bridge.bond))
                DetailRow("Avisos (ANCS)", ancsLabel(bridge.ancs), bridge.ancsDetail)
                DetailRow("Música (AMS)", amsLabel(model.music.ams), model.music.detail)
                DetailRow("Internet/IA", "No aplica en P1")
                LastReceptionRow(bridge.lastReceptionElapsedMs)
            }
        }
    }
}

/** Label above value so long values wrap instead of overflowing the round edge. */
@Composable
internal fun DetailRow(label: String, value: String, detail: String = "") {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
        WbText(label, style = MaterialTheme.typography.labelSmall, color = WbColors.Secondary, maxLines = 1)
        WbText(value, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        if (detail.isNotBlank()) {
            WbText(detail, style = MaterialTheme.typography.bodyExtraSmall, color = WbColors.Secondary, maxLines = 4)
        }
    }
}

/** "Última recepción" refreshed once per second while the row is composed (visible). */
@Composable
private fun LastReceptionRow(lastElapsedMs: Long?) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    RepeatWhileStarted(lastElapsedMs) {
        if (lastElapsedMs == null) return@RepeatWhileStarted
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    DetailRow("Última recepción", lastReceptionLabel(lastElapsedMs, now))
}
