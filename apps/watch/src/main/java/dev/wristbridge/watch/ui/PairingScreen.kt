package dev.wristbridge.watch.ui

import android.os.SystemClock
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import dev.wristbridge.watch.p2.P2Bridge
import dev.wristbridge.watch.p2.P2Phase
import kotlinx.coroutines.delay

/** Registration of the iPhone on the own channel (draft section 8.4): compare the 6-digit code on both screens. */
@Composable
internal fun PairingScreen(bridgeRunning: Boolean) {
    val context: Context = LocalContext.current
    val ui by P2Bridge.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { P2Bridge.refreshPaired(context) }
    RepeatWhileStarted(ui.registrationUntil) {
        while (ui.registrationUntil > 0) {
            now = SystemClock.elapsedRealtime()
            if (now >= ui.registrationUntil && ui.phase == P2Phase.REGISTERING) P2Bridge.cancelRegistration()
            delay(1_000)
        }
    }
    ListScreen(paneTitle = "Vincular iPhone") {
        item(key = "title") { ScreenTitle("Vincular iPhone", "Canal seguro para salud") }
        when {
            ui.phase == P2Phase.CODE && ui.code != null -> {
                item(key = "code") {
                    GroupCard {
                        WbText("Código de seguridad", style = MaterialTheme.typography.bodySmall, color = WbColors.Secondary)
                        WbText(ui.code!!.chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall)
                        Caption("Debe ser igual en el iPhone. Si no coincide, cancela.")
                    }
                }
                item(key = "match") {
                    Pill("Coincide", { P2Bridge.confirmCode(true) }, container = WbColors.Green, pressed = WbColors.Green.copy(alpha = 0.7f), textColor = androidx.compose.ui.graphics.Color.Black)
                }
                item(key = "nomatch") {
                    Pill("No coincide", { P2Bridge.confirmCode(false) }, container = WbColors.Red, pressed = WbColors.Red.copy(alpha = 0.7f), textColor = androidx.compose.ui.graphics.Color.White)
                }
            }
            ui.phase == P2Phase.REGISTERING -> {
                item(key = "waiting") {
                    GroupCard {
                        WbText("Esperando al iPhone", style = MaterialTheme.typography.titleMedium)
                        val left = ((ui.registrationUntil - now) / 1000).coerceAtLeast(0)
                        Caption("Abre WristBridge en el iPhone y elige este reloj. Quedan ${left} s.")
                    }
                }
                item(key = "cancel") { Pill("Cancelar", { P2Bridge.cancelRegistration() }) }
            }
            else -> {
                item(key = "state") {
                    GroupCard {
                        WbText(if (ui.paired) "iPhone vinculado" else "Sin iPhone vinculado", style = MaterialTheme.typography.titleMedium)
                        Caption(
                            when (ui.phase) {
                                P2Phase.READY -> "Sesión segura activa."
                                P2Phase.CLOSED -> "Sesión cerrada${if (ui.lastClosed.isNotBlank()) ": ${ui.lastClosed}" else ""}."
                                else -> "Los datos de salud solo salen del reloj por este canal cifrado."
                            },
                        )
                        Caption("Cifrado del sistema: autoprueba ${ui.cryptoSelfTest}.")
                    }
                }
                if (!bridgeRunning) {
                    item(key = "needbridge") { Caption("Inicia el puente en Conexión antes de vincular.") }
                } else if (!ui.paired) {
                    item(key = "register") { Pill("Vincular iPhone", { P2Bridge.openRegistration() }) }
                }
                if (ui.findingWatch) {
                    item(key = "stopfind") { Pill("Detener sonido", { P2Bridge.stopFindingWatch(context) }) }
                }
                if (ui.phase == P2Phase.READY) {
                    item(key = "findphone") { Pill("Buscar iPhone", { P2Bridge.findPhone(context) }) }
                    if (ui.findPhone.isNotBlank()) item(key = "findresult") { Caption(ui.findPhone) }
                }
                if (ui.paired) {
                    item(key = "forget") {
                        Pill("Olvidar iPhone", { P2Bridge.forget(context) }, container = WbColors.Red, pressed = WbColors.Red.copy(alpha = 0.7f), textColor = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
        }
    }
}
