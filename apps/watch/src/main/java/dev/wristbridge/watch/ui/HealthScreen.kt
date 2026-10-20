package dev.wristbridge.watch.ui

import androidx.compose.ui.Modifier
import dev.wristbridge.notification.AccessibilityText
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.wear.compose.material3.MaterialTheme
import dev.wristbridge.watch.health.HealthCapture
import dev.wristbridge.watch.health.HealthStore
import dev.wristbridge.watch.health.HealthSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HealthScreen() {
    val context = LocalContext.current
    val prefs = remember { HealthCapture.prefs(context) }
    var summary by remember { mutableStateOf(HealthSummary()) }
    var status by remember { mutableStateOf(HealthCapture.status(context)) }
    var eraseArmed by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf(prefs.getBoolean(HealthCapture.STEPS, false)) }
    var heart by remember { mutableStateOf(prefs.getBoolean(HealthCapture.HEART, false)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        HealthCapture.schedule(context)
    }
    LaunchedEffect(Unit) { HealthCapture.schedule(context) }
    RepeatWhileStarted(Unit) {
        while (true) {
            summary = withContext(Dispatchers.IO) { HealthStore.get(context).summary() }
            status = HealthCapture.status(context)
            delay(5_000)
        }
    }
    fun changed(key: String, enabled: Boolean, isHeart: Boolean) {
        prefs.edit { putBoolean(key, enabled) }
        if (enabled && !HealthCapture.granted(context, HealthCapture.permission(isHeart))) {
            launcher.launch(HealthCapture.permission(isHeart))
        } else HealthCapture.schedule(context)
    }
    ListScreen(paneTitle = "Salud") {
        item { ScreenTitle("Salud", "Datos locales del reloj") }
        item {
            GroupCard {
                WbText("Pasos", style = MaterialTheme.typography.bodySmall)
                WbText(summary.steps?.toString() ?: "Sin datos", style = MaterialTheme.typography.titleMedium)
                WbText("Total diario del reloj · " + time(summary.stepsAt),
                    style = MaterialTheme.typography.bodyExtraSmall, color = WbColors.Secondary)
            }
        }
        item {
            GroupCard {
                WbText("Pulso", style = MaterialTheme.typography.bodySmall)
                WbText(summary.heartRate?.let { "${it.toInt()} lpm" } ?: "Sin datos",
                    style = MaterialTheme.typography.titleMedium)
                WbText(time(summary.heartAt), style = MaterialTheme.typography.bodyExtraSmall, color = WbColors.Secondary)
            }
        }
        item {
            Pill(if (steps) "Detener pasos" else "Activar pasos", {
                steps = !steps; changed(HealthCapture.STEPS, steps, false)
            }, stateDescription = AccessibilityText.switchState(steps))
        }
        item {
            Pill(if (heart) "Detener pulso" else "Activar pulso", {
                heart = !heart; changed(HealthCapture.HEART, heart, true)
            }, stateDescription = AccessibilityText.switchState(heart))
        }
        if (heart && HealthCapture.granted(context, HealthCapture.permission(true)) &&
            !HealthCapture.granted(context, HealthCapture.backgroundPermission())) {
            item {
                WbText("El pulso pasivo necesita permiso en segundo plano. Puedes dejarlo desactivado y seguir recibiendo avisos.",
                    style = MaterialTheme.typography.bodyExtraSmall, color = WbColors.Secondary)
            }
            item { Pill("Permitir segundo plano", { launcher.launch(HealthCapture.backgroundPermission()) }) }
        }
        item {
            GroupCard {
                WbText(status, modifier = Modifier.politeLiveRegion(), style = MaterialTheme.typography.bodyExtraSmall)
                WbText("La recepción es por lotes; no es una medición en vivo.",
                    style = MaterialTheme.typography.bodyExtraSmall, color = WbColors.Secondary)
                WbText("iPhone: envío pendiente del canal seguro y del receptor Swift.",
                    style = MaterialTheme.typography.bodyExtraSmall, color = WbColors.Amber)
                WbText("Registros locales: ${summary.pending} · expirados/descartados: ${summary.dropped}\nRetención máxima: 7 días / 4 000 registros.",
                    style = MaterialTheme.typography.bodyExtraSmall, color = WbColors.Secondary)
            }
        }
        item {
            Pill(if (eraseArmed) "Confirmar borrado" else "Borrar datos locales", {
                if (eraseArmed) {
                    steps = false; heart = false
                    HealthCapture.erase(context)
                }
                eraseArmed = !eraseArmed
            })
        }
    }
}

private fun time(at: Long?): String = at?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
} ?: "Sin lectura"
