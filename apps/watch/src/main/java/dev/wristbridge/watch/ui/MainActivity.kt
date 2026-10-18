package dev.wristbridge.watch.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import dev.wristbridge.notification.CallKind
import dev.wristbridge.notification.CardPresentation
import dev.wristbridge.watch.BuildConfig
import dev.wristbridge.watch.R
import dev.wristbridge.watch.WristBridgeApp
import dev.wristbridge.watch.bridge.ActiveCards
import dev.wristbridge.watch.bridge.AmsState
import dev.wristbridge.watch.bridge.BlePermissions
import dev.wristbridge.watch.bridge.BridgeService
import dev.wristbridge.watch.bridge.BridgeStatus
import dev.wristbridge.watch.bridge.MediaStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppScaffold { BridgeScreen() } } }
    }
}

/**
 * Main screen, top to bottom: status headline, incoming call, music, active cards, settings, connection
 * and (collapsed) diagnostics. Card and track content is only rendered, never logged.
 */
@Composable
private fun BridgeScreen() {
    val context = LocalContext.current
    val bridge by BridgeStatus.state.collectAsStateWithLifecycle()
    val music by MediaStatus.state.collectAsStateWithLifecycle()
    val cards by ActiveCards.state.collectAsStateWithLifecycle()

    val prefs = remember { context.getSharedPreferences(WristBridgeApp.PREFS, Context.MODE_PRIVATE) }
    val hidePref = rememberBoolPref(prefs, WristBridgeApp.PREF_HIDE_PREVIEW, false)
    val actionsPref = rememberBoolPref(prefs, WristBridgeApp.PREF_ACTIONS, true)
    val syncDismissPref = rememberBoolPref(prefs, WristBridgeApp.PREF_SYNC_DISMISS, false)
    val mediaNotificationPref = rememberBoolPref(prefs, WristBridgeApp.PREF_MEDIA_NOTIFICATION, true)

    var missing by remember { mutableStateOf(BlePermissions.missing(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = BlePermissions.missing(context)
    }
    val bluetoothGranted = remember(missing) { BlePermissions.bluetoothGranted(context) }
    var diagnosticsOpen by remember { mutableStateOf(false) }

    val hidePreview = hidePref.value
    val actionsEnabled = actionsPref.value
    val hiddenText = stringResource(R.string.card_hidden)
    val unavailableText = stringResource(R.string.card_details_unavailable)
    val presented = remember(cards, hidePreview, hiddenText, unavailableText) {
        cards.map { PresentedCard(it, CardPresentation.from(it.card, hidePreview, hiddenText, unavailableText)) }
    }
    // Newest incoming call goes on top; every other card (older calls included) stays in the list.
    val call = presented.firstOrNull { it.presentation.callKind == CallKind.INCOMING }
    val others = if (call == null) presented else presented.filter { it !== call }
    val status = headline(bridge, music.ams, bluetoothGranted)
    val media = music.media
    val playerVisible = music.ams == AmsState.READY && media.hasPlayer
    val events = bridge.events
    val showDiagnostics = diagnosticsOpen

    val appName = stringResource(R.string.app_name)
    val permissionsNeeded = stringResource(R.string.permissions_needed)
    val grantPermissions = stringResource(R.string.grant_permissions)
    val startBridge = stringResource(R.string.start_bridge)
    val stopBridge = stringResource(R.string.stop_bridge)
    val recover = stringResource(R.string.recover)
    val useProposedName = stringResource(R.string.use_proposed_name)
    val restoreName = stringResource(R.string.restore_name)

    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
        ) {
            // 1. Header
            item(key = "app") { AppTitle(appName) }
            item(key = "headline") { HeadlineText(status) }
            // Without permissions nothing else works: offer the request right under the headline.
            if (missing.isNotEmpty()) {
                item(key = "perm-text") { InfoLine(permissionsNeeded) }
                item(key = "perm-button") {
                    PrimaryAction(grantPermissions) { launcher.launch(BlePermissions.REQUIRED) }
                }
            }

            // 2. Incoming call
            if (call != null) {
                item(key = "call") {
                    IncomingCallCard(call, actionsEnabled) { action ->
                        BridgeService.sendCardAction(context, call.active.card.key, action)
                    }
                }
            }

            // 3. Music (AMS)
            item(key = "music-header") { SectionHeader("Música") }
            if (playerVisible) {
                item(key = "music-info") { NowPlaying(media, hidePreview, hiddenText) }
                item(key = "music-transport") {
                    TransportControls(media) { command -> BridgeService.sendMedia(context, command) }
                }
                if (showVolume(media)) {
                    item(key = "music-volume") {
                        VolumeControls(media) { command -> BridgeService.sendMedia(context, command) }
                    }
                } else {
                    media.volume?.let { v -> item(key = "music-volume-level") { InfoLine("Volumen ${volumePercent(v)}") } }
                }
            } else {
                item(key = "music-off") { InfoLine(musicUnavailableLine(music)) }
            }

            // 4. Active cards (ANCS)
            item(key = "cards-header") { SectionHeader("Avisos activos (${others.size})") }
            if (others.isEmpty()) {
                item(key = "cards-empty") { InfoLine("Sin avisos") }
            } else {
                items(others, key = { "card:${it.active.card.key.tag}" }) { entry ->
                    NotificationItem(entry, actionsEnabled) { action ->
                        BridgeService.sendCardAction(context, entry.active.card.key, action)
                    }
                }
            }

            // 5. Settings (persisted immediately)
            item(key = "settings-header") { SectionHeader("Ajustes") }
            item(key = "pref-hide") { PrefSwitch("Ocultar contenido", hidePref) }
            item(key = "pref-actions") { PrefSwitch("Acciones en avisos", actionsPref) }
            item(key = "pref-sync") {
                PrefSwitch("Borrar en iPhone al descartar", syncDismissPref, secondary = "Solo avisos, nunca llamadas")
            }
            item(key = "pref-media") { PrefSwitch("Notificación de música", mediaNotificationPref) }

            // 6. Connection
            item(key = "conn-header") { SectionHeader("Conexión") }
            item(key = "bridge-toggle") {
                if (bridge.serviceRunning) {
                    SecondaryAction(stopBridge) { BridgeService.send(context, BridgeService.ACTION_STOP) }
                } else {
                    PrimaryAction(startBridge, enabled = bluetoothGranted) {
                        BridgeService.send(context, BridgeService.ACTION_START)
                    }
                }
            }
            if (bridge.serviceRunning) {
                item(key = "recover") { SecondaryAction(recover) { BridgeService.send(context, BridgeService.ACTION_RECOVER) } }
                item(key = "use-name") {
                    SecondaryAction(useProposedName) { BridgeService.send(context, BridgeService.ACTION_USE_PROPOSED_NAME) }
                }
                item(key = "restore-name") {
                    SecondaryAction(restoreName) { BridgeService.send(context, BridgeService.ACTION_RESTORE_NAME) }
                }
            }
            item(key = "st-bridge") { StatusRow("Puente", if (bridge.serviceRunning) "Activo" else "Detenido") }
            item(key = "st-radio") { StatusRow("Bluetooth", radioLabel(bridge.radio)) }
            item(key = "st-own") {
                val detail = listOfNotNull(
                    bridge.advertisedName?.let { "Nombre anunciado: $it" },
                    bridge.ownChannelDetail.takeIf { it.isNotBlank() },
                ).joinToString("\n")
                StatusRow("Canal propio", ownLabel(bridge), detail)
            }
            item(key = "st-bond") { StatusRow("Vínculo", bondLabel(bridge.bond)) }
            item(key = "st-ancs") { StatusRow("Avisos (ANCS)", ancsLabel(bridge.ancs), bridge.ancsDetail) }
            item(key = "st-ams") { StatusRow("Música (AMS)", amsLabel(music.ams), music.detail) }
            item(key = "st-ia") { StatusRow("Internet/IA", "No aplica en P1") }
            item(key = "st-last") { LastReceptionRow(bridge.lastReceptionElapsedMs) }

            // 7. Diagnostics (collapsed by default)
            item(key = "diag-header") { SectionHeader("Diagnóstico") }
            item(key = "diag-toggle") {
                SecondaryAction(if (showDiagnostics) "Ocultar diagnóstico" else "Mostrar diagnóstico") {
                    diagnosticsOpen = !diagnosticsOpen
                }
            }
            if (showDiagnostics) {
                item(key = "diag-version") { InfoLine("Versión ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_COMMIT}") }
                item(key = "diag-counters") { InfoLine(diagnostics(bridge)) }
                // Event lines may repeat, so they are not keyed.
                items(events) { line -> InfoLine(line) }
            }
        }
    }
}
