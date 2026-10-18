package dev.wristbridge.watch.bridge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.wristbridge.notification.CardPresentation
import dev.wristbridge.notification.CallKind
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
    private val badges = SourceBadgeRenderer()
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
        val presentation = CardPresentation.from(
            card, hidePreview,
            hiddenText = context.getString(R.string.card_hidden),
            unavailableText = context.getString(R.string.card_details_unavailable),
        )
        val ringing = alert && presentation.callKind == CallKind.INCOMING
        val channel = when {
            ringing -> WristBridgeApp.CHANNEL_CALLS
            !alert -> WristBridgeApp.CHANNEL_QUIET
            else -> WristBridgeApp.CHANNEL_ALERTS
        }
        val smallIcon = when (presentation.callKind) {
            CallKind.INCOMING -> android.R.drawable.sym_call_incoming
            CallKind.MISSED -> android.R.drawable.sym_call_missed
            else -> R.drawable.ic_stat_bridge
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(smallIcon)
            .setContentTitle(presentation.title)
            .setContentText(presentation.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(presentation.body))
            .setLargeIcon(badges.icon(presentation.source))
            .setColor(presentation.source.accentArgb)
            .setSubText(presentation.source.label)
            .setCategory(
                when (presentation.callKind) {
                    CallKind.INCOMING -> NotificationCompat.CATEGORY_CALL
                    CallKind.MISSED -> NotificationCompat.CATEGORY_MISSED_CALL
                    else -> NotificationCompat.CATEGORY_MESSAGE
                },
            )
            .setOnlyAlertOnce(true)
            .setSilent(!alert)
            .setAutoCancel(false)
            .setContentIntent(open)
            .setGroup("wb-${card.key.sessionGeneration}-${presentation.source.groupKey}")
            .apply { if (ringing) setTimeoutAfter(RING_TIMEOUT_MS) }
            .build()
        // Insistent until ANCS Removed (answered, declined or missed) cancels it; the timeout above
        // bounds the ring if that event is lost with the link. Updates never restart the ring.
        if (ringing) n.flags = n.flags or Notification.FLAG_INSISTENT
        manager.notify(card.key.tag, CARD_ID, n)

        val fixture = card.fixture?.let { " fixture=${it.runId}/${it.fixtureId}" }.orEmpty()
        BridgeStatus.event(
            "${if (update) "Tarjeta actualizada" else "Tarjeta mostrada"} gen=${card.key.sessionGeneration} " +
                "uid=${card.key.uid} bytesCuerpo=${card.message.toByteArray().size} alerta=$alert " +
                "llamada=${presentation.callKind}$fixture" +
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
        private const val RING_TIMEOUT_MS = 90_000L
    }
}
