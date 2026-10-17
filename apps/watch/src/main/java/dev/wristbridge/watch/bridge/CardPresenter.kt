package dev.wristbridge.watch.bridge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.wristbridge.notification.NotificationCard
import dev.wristbridge.notification.SessionEffect
import dev.wristbridge.watch.R
import dev.wristbridge.watch.WristBridgeApp
import dev.wristbridge.watch.ui.MainActivity

/**
 * Presents cards as native Wear OS notifications (P1-08). Tags come from [NotificationKey.tag] so
 * updates replace the same notification and removals retire it. Logs carry ids and sizes only.
 */
class CardPresenter(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val prefs = context.getSharedPreferences(WristBridgeApp.PREFS, Context.MODE_PRIVATE)

    fun apply(effect: SessionEffect) {
        try {
            when (effect) {
                is SessionEffect.PostCard -> show(effect.card, alert = effect.alert, update = false)
                is SessionEffect.UpdateCard -> show(effect.card, alert = false, update = true)
                is SessionEffect.RemoveCard -> manager.cancel(effect.key.tag, CARD_ID)
                is SessionEffect.RemoveCardsBefore -> removeBefore(effect.generation)
                is SessionEffect.WriteControlPoint -> Unit
            }
        } catch (e: SecurityException) {
            BridgeStatus.event("Permiso de notificaciones denegado: tarjeta no mostrada")
            Log.w(TAG, "notify denied", e)
        }
    }

    private fun show(card: NotificationCard, alert: Boolean, update: Boolean) {
        val hidePreview = prefs.getBoolean(WristBridgeApp.PREF_HIDE_PREVIEW, false)
        val title = when {
            hidePreview -> card.sourceLabel
            card.title.isNotBlank() -> card.title
            else -> card.sourceLabel
        }
        val body = when {
            hidePreview -> context.getString(R.string.card_hidden)
            card.detailsUnavailable -> context.getString(R.string.card_details_unavailable)
            else -> listOf(card.subtitle, card.message).filter { it.isNotBlank() }.joinToString("\n")
        } + if (card.truncated && !hidePreview) "…" else ""

        val channel = if (alert) WristBridgeApp.CHANNEL_ALERTS else WristBridgeApp.CHANNEL_QUIET
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(card.sourceLabel)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setSilent(!alert)
            .setAutoCancel(false)
            .setContentIntent(open)
            .setGroup("wb-${card.key.sessionGeneration}")
            .build()
        manager.notify(card.key.tag, CARD_ID, n)

        val fixture = card.fixture?.let { " fixture=${it.runId}/${it.fixtureId}" }.orEmpty()
        BridgeStatus.event(
            "${if (update) "Tarjeta actualizada" else "Tarjeta mostrada"} gen=${card.key.sessionGeneration} " +
                "uid=${card.key.uid} bytesCuerpo=${card.message.toByteArray().size} alerta=$alert$fixture" +
                if (card.detailsUnavailable) " sinDetalles" else "",
        )
    }

    private fun removeBefore(generation: Long) {
        var removed = 0
        for (sbn in manager.activeNotifications) {
            val tag = sbn.tag ?: continue
            val gen = tag.split(':').takeIf { it.size == 3 && it[0] == "wb" }?.get(1)?.toLongOrNull() ?: continue
            if (gen < generation) {
                manager.cancel(tag, sbn.id)
                removed++
            }
        }
        if (removed > 0) BridgeStatus.event("Retiradas $removed tarjetas de sesiones anteriores")
    }

    companion object {
        const val CARD_ID = 1
    }
}
