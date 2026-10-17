package dev.wristbridge.watch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class WristBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                // Persistent service notice: discreet and separate from forwarded cards (plan §6.5).
                NotificationChannel(CHANNEL_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
                // Silent, pre-existing backlog and updates: no repeated haptics.
                NotificationChannel(CHANNEL_QUIET, getString(R.string.channel_quiet), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "bridge_service"
        const val CHANNEL_ALERTS = "iphone_alerts"
        const val CHANNEL_QUIET = "iphone_quiet"
        const val PREFS = "wristbridge"
        const val PREF_ENABLED = "bridge_enabled"
        const val PREF_HIDE_PREVIEW = "hide_preview"
        const val PREF_ORIGINAL_NAME = "original_bt_name"
    }
}
