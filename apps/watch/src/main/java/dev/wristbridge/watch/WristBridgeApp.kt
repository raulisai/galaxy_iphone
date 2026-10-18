package dev.wristbridge.watch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager

class WristBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                // Persistent service notice: discreet and separate from forwarded cards (plan §6.5).
                NotificationChannel(CHANNEL_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
                // Incoming calls ring with the ringtone and a repeated pattern until ANCS reports Removed.
                NotificationChannel(CHANNEL_CALLS, getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
                },
                // Silent, pre-existing backlog and updates: no repeated haptics.
                NotificationChannel(CHANNEL_QUIET, getString(R.string.channel_quiet), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "bridge_service"
        const val CHANNEL_ALERTS = "iphone_alerts"
        const val CHANNEL_CALLS = "iphone_calls"
        const val CHANNEL_QUIET = "iphone_quiet"
        const val PREFS = "wristbridge"
        const val PREF_ENABLED = "bridge_enabled"
        const val PREF_HIDE_PREVIEW = "hide_preview"
        const val PREF_ORIGINAL_NAME = "original_bt_name"
    }
}
