package dev.wristbridge.watch.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.wristbridge.watch.WristBridgeApp

/**
 * Restarts the bridge after a watch reboot or an app update, but only if the user left it enabled and the
 * Bluetooth permissions are still granted. Both broadcasts are exempt from the background
 * foreground-service start restriction; a refusal is logged and the user can start it from the app.
 */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        dev.wristbridge.watch.health.HealthCapture.schedule(context)
        val prefs = context.getSharedPreferences(WristBridgeApp.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(WristBridgeApp.PREF_ENABLED, false) || !BlePermissions.bluetoothGranted(context)) return
        try {
            BridgeService.send(context, BridgeService.ACTION_START)
            Log.i(TAG, "Puente reanudado tras ${if (intent.action == Intent.ACTION_BOOT_COMPLETED) "reinicio" else "actualización"}")
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException and similar: leave it to the user.
            DiagnosticLog.w("No se pudo reanudar el puente automáticamente", e)
        }
    }
}
