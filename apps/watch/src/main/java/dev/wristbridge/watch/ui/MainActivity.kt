package dev.wristbridge.watch.ui

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.wristbridge.watch.BuildConfig
import dev.wristbridge.watch.R
import dev.wristbridge.watch.WristBridgeApp
import dev.wristbridge.watch.bridge.AncsState
import dev.wristbridge.watch.bridge.BlePermissions
import dev.wristbridge.watch.bridge.BondState
import dev.wristbridge.watch.bridge.BridgeService
import dev.wristbridge.watch.bridge.BridgeStatus
import dev.wristbridge.watch.bridge.BridgeUiState
import dev.wristbridge.watch.bridge.OwnChannelState
import dev.wristbridge.watch.bridge.RadioState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { BridgeScreen() } }
    }
}

@Composable
private fun BridgeScreen() {
    val context = LocalContext.current
    val state by BridgeStatus.state.collectAsStateWithLifecycle()
    var missing by remember { mutableStateOf(BlePermissions.missing(context)) }
    val prefs = remember { context.getSharedPreferences(WristBridgeApp.PREFS, Context.MODE_PRIVATE) }
    var hidePreview by remember { mutableStateOf(prefs.getBoolean(WristBridgeApp.PREF_HIDE_PREVIEW, false)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = BlePermissions.missing(context)
    }
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(modifier = Modifier.fillMaxWidth(), state = listState) {
        item { Title(stringResource(R.string.app_name)) }
        item { Line("Versión ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_COMMIT}") }

        if (missing.isNotEmpty()) {
            item { Line(stringResource(R.string.permissions_needed)) }
            item {
                Action(stringResource(R.string.grant_permissions)) { launcher.launch(BlePermissions.REQUIRED) }
            }
        }

        item { Status("Puente", if (state.serviceRunning) "Activo" else "Detenido") }
        item { Status("Bluetooth", radioLabel(state.radio)) }
        item { Status("Canal propio", ownLabel(state)) }
        state.advertisedName?.let { item { Line("Nombre anunciado: $it") } }
        if (state.ownChannelDetail.isNotBlank()) item { Line(state.ownChannelDetail) }
        item { Status("Vínculo", bondLabel(state.bond)) }
        item { Status("Avisos (ANCS)", ancsLabel(state.ancs)) }
        if (state.ancsDetail.isNotBlank()) item { Line(state.ancsDetail) }
        item { Status("Internet/IA", "No aplica en P1") }
        item { Line("Última recepción: ${lastReception(state)}") }

        item {
            if (state.serviceRunning) {
                Action(stringResource(R.string.stop_bridge)) { BridgeService.send(context, BridgeService.ACTION_STOP) }
            } else {
                Action(stringResource(R.string.start_bridge), enabled = BlePermissions.bluetoothGranted(context)) {
                    BridgeService.send(context, BridgeService.ACTION_START)
                }
            }
        }
        if (state.serviceRunning) {
            item { Action(stringResource(R.string.recover)) { BridgeService.send(context, BridgeService.ACTION_RECOVER) } }
            item {
                Action(stringResource(R.string.use_proposed_name)) {
                    BridgeService.send(context, BridgeService.ACTION_USE_PROPOSED_NAME)
                }
            }
            item {
                Action(stringResource(R.string.restore_name)) { BridgeService.send(context, BridgeService.ACTION_RESTORE_NAME) }
            }
        }
        item {
            Action(if (hidePreview) "Mostrar contenido" else "Ocultar contenido") {
                hidePreview = !hidePreview
                prefs.edit { putBoolean(WristBridgeApp.PREF_HIDE_PREVIEW, hidePreview) }
            }
        }

        item { Title("Diagnóstico") }
        item { Line(diagnostics(state)) }
        items(state.events.size) { i -> Line(state.events[i]) }
    }
}

@Composable
private fun Title(text: String) = Text(
    text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp),
)

@Composable
private fun Line(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
)

@Composable
private fun Status(label: String, value: String) = Text(
    "$label: $value", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
)

@Composable
private fun Action(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(label)
    }
}

private fun radioLabel(r: RadioState) = when (r) {
    RadioState.ON -> "Encendido"
    RadioState.OFF -> "Apagado"
    RadioState.UNAVAILABLE -> "No disponible"
    RadioState.UNKNOWN -> "Sin comprobar"
}

private fun ownLabel(s: BridgeUiState) = when (s.ownChannel) {
    OwnChannelState.STOPPED -> "Detenido"
    OwnChannelState.STARTING -> "Iniciando"
    OwnChannelState.ADVERTISING -> "Anunciando, esperando iPhone"
    OwnChannelState.CONNECTED -> "iPhone conectado (${s.peer ?: "?"})"
    OwnChannelState.FAILED -> "Error"
}

private fun bondLabel(b: BondState) = when (b) {
    BondState.BONDED -> "Vinculado"
    BondState.BONDING -> "Vinculando"
    BondState.NONE -> "Sin vincular"
    BondState.UNKNOWN -> "Sin comprobar"
}

/** "Listo" only after both remote subscriptions were confirmed (Contract §1). */
private fun ancsLabel(a: AncsState) = when (a) {
    AncsState.IDLE -> "Sin sesión"
    AncsState.CONNECTING -> "Conectando"
    AncsState.DISCOVERING -> "Buscando servicio"
    AncsState.NOT_FOUND -> "No disponible"
    AncsState.SUBSCRIBING -> "Suscribiendo"
    AncsState.DATA_SOURCE_CONFIRMED -> "Data Source confirmado"
    AncsState.READY -> "Listo"
    AncsState.FAILED -> "Error"
}

private fun lastReception(s: BridgeUiState): String {
    val t = s.lastReceptionElapsedMs ?: return "ninguna"
    return "hace ${(SystemClock.elapsedRealtime() - t) / 1000} s"
}

private fun diagnostics(s: BridgeUiState): String {
    val c = s.counters
    return "gen=${s.generation} mtu=${s.mtu} gatt=${s.lastGattStatus ?: "-"}\n" +
        "eventos=${c.nsEvents} añadidos=${c.added} modificados=${c.modified} retirados=${c.removed} previos=${c.preExisting}\n" +
        "consultas=${c.fetchStarted} completas=${c.fetchCompleted} timeouts=${c.fetchTimedOut} fallos=${c.fetchFailed} ancsErr=${c.ancsErrors}\n" +
        "tarjetas +${c.cardsPosted} ~${c.cardsUpdated} -${c.cardsRemoved} desbordes=${c.queueOverflow} " +
        "descartes=${c.discardedResponses} obsoletos=${c.staleInputsIgnored} inválidos=${c.nsInvalid}"
}
