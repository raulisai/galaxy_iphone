package dev.wristbridge.watch.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import dev.wristbridge.notification.CallKind
import dev.wristbridge.notification.CardPresentation
import dev.wristbridge.watch.R
import dev.wristbridge.watch.WristBridgeApp
import dev.wristbridge.watch.bridge.ActiveCards
import dev.wristbridge.watch.bridge.BlePermissions
import dev.wristbridge.watch.bridge.BridgeService
import dev.wristbridge.watch.bridge.BridgeStatus
import dev.wristbridge.watch.bridge.MediaStatus

/** Destinations of the simple state-based navigation. Back (swipe) always returns to [parent]. */
internal enum class Screen(val parent: Screen?) {
    HOME(null),
    ALERTS(HOME),
    MUSIC(HOME),
    CONNECTION(HOME),
    SETTINGS(HOME),
    DIAGNOSTICS(SETTINGS),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppScaffold { WatchApp() } } }
    }
}

/**
 * App root. All content is derived from the process-wide StateFlows (bridge, media, cards) through
 * lifecycle-aware collection, so every emission recomposes the visible screen; nothing is snapshotted.
 * Card and track content is only rendered, never logged.
 */
@Composable
private fun WatchApp() {
    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val metrics = remember(widthDp) { Metrics((widthDp / 192f).coerceIn(0.78f, 1.3f)) }

    val bridge by BridgeStatus.state.collectAsStateWithLifecycle()
    val music by MediaStatus.state.collectAsStateWithLifecycle()
    val cards by ActiveCards.state.collectAsStateWithLifecycle()

    val sharedPrefs = remember { context.getSharedPreferences(WristBridgeApp.PREFS, Context.MODE_PRIVATE) }
    val prefs = Prefs(
        hidePreview = rememberBoolPref(sharedPrefs, WristBridgeApp.PREF_HIDE_PREVIEW, false),
        actions = rememberBoolPref(sharedPrefs, WristBridgeApp.PREF_ACTIONS, true),
        syncDismiss = rememberBoolPref(sharedPrefs, WristBridgeApp.PREF_SYNC_DISMISS, false),
        mediaNotification = rememberBoolPref(sharedPrefs, WristBridgeApp.PREF_MEDIA_NOTIFICATION, true),
    )

    var missing by remember { mutableStateOf(BlePermissions.missing(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = BlePermissions.missing(context)
    }
    val bluetoothGranted = remember(missing) { BlePermissions.bluetoothGranted(context) }

    val hidePreview = prefs.hidePreview.value
    val hiddenText = stringResource(R.string.card_hidden)
    val unavailableText = stringResource(R.string.card_details_unavailable)
    val presented = remember(cards, hidePreview, hiddenText, unavailableText) {
        cards.map { PresentedCard(it, CardPresentation.from(it.card, hidePreview, hiddenText, unavailableText)) }
    }
    // Newest incoming call is the takeover; every other card (older calls included) stays in the list.
    val call = presented.firstOrNull { it.presentation.callKind == CallKind.INCOMING }
    val model = UiModel(
        bridge = bridge,
        music = music,
        alerts = if (call == null) presented else presented.filter { it !== call },
        call = call,
        hidePreview = hidePreview,
        actionsEnabled = prefs.actions.value,
        headline = headline(bridge, music.ams, bluetoothGranted),
        bluetoothGranted = bluetoothGranted,
        permissionsMissing = missing.isNotEmpty(),
    )

    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    // Tag of a ringing call the user minimized; a new call (new tag) takes over again.
    var minimizedCall by rememberSaveable { mutableStateOf<String?>(null) }
    val callTag = call?.active?.card?.key?.tag
    val showCall = call != null && callTag != minimizedCall

    BackHandler(enabled = screen.parent != null) { screen = screen.parent ?: Screen.HOME }
    BackHandler(enabled = showCall) { minimizedCall = callTag }

    val requestPermissions = { launcher.launch(BlePermissions.REQUIRED) }

    CompositionLocalProvider(LocalMetrics provides metrics) {
        Box(Modifier.fillMaxSize().background(WbColors.Black)) {
            if (showCall && call != null) {
                CallScreen(
                    call = call,
                    actionsEnabled = model.actionsEnabled,
                    onAction = { action -> BridgeService.sendCardAction(context, call.active.card.key, action) },
                    onMinimize = { minimizedCall = callTag },
                )
            } else {
                Crossfade(targetState = screen, label = "screen") { target ->
                    when (target) {
                        Screen.HOME -> HomeScreen(
                            model = model,
                            onOpen = { screen = it },
                            onShowCall = { minimizedCall = null },
                            onRequestPermissions = requestPermissions,
                        )
                        Screen.ALERTS -> AlertsScreen(model) { entry, action ->
                            BridgeService.sendCardAction(context, entry.active.card.key, action)
                        }
                        Screen.MUSIC -> MusicScreen(model) { command -> BridgeService.sendMedia(context, command) }
                        Screen.CONNECTION -> ConnectionScreen(
                            model = model,
                            onBridgeCommand = { BridgeService.send(context, it) },
                            onRequestPermissions = requestPermissions,
                        )
                        Screen.SETTINGS -> SettingsScreen(prefs) { screen = Screen.DIAGNOSTICS }
                        Screen.DIAGNOSTICS -> DiagnosticsScreen(model)
                    }
                }
            }
        }
    }
}
