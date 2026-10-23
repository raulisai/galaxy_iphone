package dev.wristbridge.watch.bridge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.wristbridge.notification.ActionStatus
import dev.wristbridge.notification.CallKind
import dev.wristbridge.notification.CardPresentation
import dev.wristbridge.notification.NotificationCard
import dev.wristbridge.notification.NotificationKey
import dev.wristbridge.notification.SessionEffect
import dev.wristbridge.protocol.ancs.ActionId
import dev.wristbridge.watch.R
import dev.wristbridge.watch.WristBridgeApp
import dev.wristbridge.watch.ui.MainActivity

/**
 * Presents cards as native Wear OS notifications (P1-08) and mirrors them into [ActiveCards] for the app
 * UI. Tags come from [NotificationKey.tag] so updates replace the same notification and removals retire it.
 * Actions the iPhone advertised become buttons (P2-07); results only ever re-post silently. Logs carry ids
 * and sizes only. Called on the BLE thread only.
 */
class CardPresenter(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val badges = SourceBadgeRenderer()
    private val prefs = context.getSharedPreferences(WristBridgeApp.PREFS, Context.MODE_PRIVATE)

    /** Last card posted per key, needed to re-post it on an action result. Retired with the card. */
    private val shown = HashMap<NotificationKey, Shown>()

    private class Shown(val card: NotificationCard, val callKind: CallKind, val feedback: ActionStatus?)

    fun apply(effect: SessionEffect) {
        try {
            when (effect) {
                is SessionEffect.PostCard -> post(effect.card, alert = effect.alert, update = false)
                is SessionEffect.UpdateCard -> post(effect.card, alert = false, update = true)
                is SessionEffect.RemoveCard -> remove(effect.key)
                is SessionEffect.RemoveCardsBefore -> removeBefore(effect.generation)
                is SessionEffect.ActionResult -> actionResult(effect)
                is SessionEffect.WriteControlPoint -> Unit
            }
        } catch (e: SecurityException) {
            BridgeStatus.event("Permiso de notificaciones denegado: tarjeta no mostrada")
            DiagnosticLog.w("notify denied", e)
        } catch (e: RuntimeException) {
            // A presentation failure must never take the BLE thread (and the whole bridge) down: the card is
            // lost, the session goes on and the next one is shown.
            BridgeStatus.event("Tarjeta no mostrada: ${e.javaClass.simpleName}")
            DiagnosticLog.w("card presentation failed", e)
        }
    }

    private fun post(card: NotificationCard, alert: Boolean, update: Boolean) {
        ActiveCards.upsert(card)
        // A requested call action stays pending across Modified updates until ANCS Removed retires the card;
        // failure notes are stale once the card changes.
        val feedback = shown[card.key]?.feedback?.takeIf { update && it == ActionStatus.REQUESTED }
        val presentation = render(card, alert, feedback)

        val fixture = card.fixture?.let { " fixture=${it.runId}/${it.fixtureId}" }.orEmpty()
        BridgeStatus.event(
            "${if (update) "Tarjeta actualizada" else "Tarjeta mostrada"} gen=${card.key.sessionGeneration} " +
                "uid=${card.key.uid} bytesCuerpo=${card.message.toByteArray().size} alerta=$alert " +
                "llamada=${presentation.callKind}$fixture" +
                if (card.detailsUnavailable) " sinDetalles" else "",
        )
    }

    /**
     * Builds and posts [card]. [feedback] adds a status line; a REQUESTED one also drops the buttons, since
     * the request is already on its way. Only an alerting incoming call rings (insistent, bounded).
     */
    private fun render(card: NotificationCard, alert: Boolean, feedback: ActionStatus?): CardPresentation {
        val hidePreview = prefs.getBoolean(WristBridgeApp.PREF_HIDE_PREVIEW, false)
        val presentation = CardPresentation.from(
            card, hidePreview,
            hiddenText = context.getString(R.string.card_hidden),
            unavailableText = context.getString(R.string.card_details_unavailable),
        )
        val kind = presentation.callKind
        shown[card.key] = Shown(card, kind, feedback)

        val ringing = alert && kind == CallKind.INCOMING
        val channel = when {
            ringing -> WristBridgeApp.CHANNEL_CALLS
            !alert -> WristBridgeApp.CHANNEL_QUIET
            else -> WristBridgeApp.CHANNEL_ALERTS
        }
        val smallIcon = when (kind) {
            CallKind.INCOMING -> android.R.drawable.sym_call_incoming
            CallKind.MISSED -> android.R.drawable.sym_call_missed
            else -> R.drawable.ic_stat_bridge
        }
        val statusLine = when (feedback) {
            ActionStatus.REQUESTED -> context.getString(R.string.card_action_requested)
            ActionStatus.FAILED -> context.getString(R.string.card_action_failed)
            ActionStatus.UNAVAILABLE -> context.getString(R.string.card_action_unavailable)
            null -> null
        }
        val body = listOfNotNull(statusLine, presentation.body.takeIf(String::isNotBlank)).joinToString("\n")
        val withActions = feedback != ActionStatus.REQUESTED && prefs.getBoolean(WristBridgeApp.PREF_ACTIONS, true)
        // Dismissing must never decline or clear a call: the delete intent is for plain cards only.
        val syncDismiss = kind == CallKind.NONE && card.negativeActionLabel != null &&
            prefs.getBoolean(WristBridgeApp.PREF_SYNC_DISMISS, false)
        // A call card also stays bounded after an action result, in case ANCS Removed is lost with the link.
        val bounded = ringing || (kind == CallKind.INCOMING && feedback != null)

        val incoming = kind == CallKind.INCOMING
        val open = if (incoming) {
            // Straight onto the call screen. No full-screen intent, Telecom or keyguard handling (project rule).
            PendingIntent.getActivity(
                context, REQUEST_OPEN_CALL,
                Intent(context, MainActivity::class.java)
                    .putExtra(WristBridgeApp.EXTRA_OPEN_CALL, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
        }
        // Notification.CallStyle is NOT used: since Android 14 the system rejects it (IllegalArgumentException)
        // unless the notification belongs to a foreground service or uses a full-screen intent, which this
        // project forbids. Observed on the Watch7: the exception killed the BLE thread on every incoming call.
        // Answer/decline stay as plain notification actions with the iPhone's own labels.
        val accent = if (incoming) CALL_GREEN else presentation.source.accentArgb
        val n: Notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(smallIcon)
            .setContentTitle(presentation.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setLargeIcon(badges.icon(presentation.source))
            .setColor(accent)
            .setSubText(presentation.source.label)
            .setCategory(
                when (kind) {
                    CallKind.INCOMING -> NotificationCompat.CATEGORY_CALL
                    CallKind.MISSED -> NotificationCompat.CATEGORY_MISSED_CALL
                    else -> NotificationCompat.CATEGORY_MESSAGE
                },
            )
            .setOnlyAlertOnce(true)
            .setSilent(!alert)
            .setAutoCancel(false)
            .setOngoing(incoming)
            .setContentIntent(open)
            .setGroup("wb-${card.key.sessionGeneration}-${presentation.source.groupKey}")
            .apply {
                if (withActions) {
                    // Labels are the iPhone's own ("Aceptar", "Rechazar", "Borrar"); positive first.
                    card.positiveActionLabel?.let {
                        addAction(
                            if (kind == CallKind.NONE) android.R.drawable.ic_menu_send else android.R.drawable.sym_action_call,
                            label(it, R.string.card_action_positive),
                            actionIntent(card.key, BridgeService.ACTION_CARD_ACTION, ActionId.POSITIVE, REQUEST_POSITIVE),
                        )
                    }
                    card.negativeActionLabel?.let {
                        addAction(
                            if (kind == CallKind.INCOMING) android.R.drawable.ic_menu_close_clear_cancel
                            else android.R.drawable.ic_menu_delete,
                            label(it, R.string.card_action_negative),
                            actionIntent(card.key, BridgeService.ACTION_CARD_ACTION, ActionId.NEGATIVE, REQUEST_NEGATIVE),
                        )
                    }
                }
                if (syncDismiss) {
                    setDeleteIntent(
                        actionIntent(card.key, BridgeService.ACTION_CARD_DISMISSED, ActionId.NEGATIVE, REQUEST_DISMISS),
                    )
                }
                if (bounded) setTimeoutAfter(RING_TIMEOUT_MS)
            }
            .build()
        // Insistent until ANCS Removed (answered, declined or missed) cancels it; the timeout above
        // bounds the ring if that event is lost with the link. Updates never restart the ring, and any
        // silent re-post (update or action result) on the quiet channel stops it.
        if (ringing) n.flags = n.flags or Notification.FLAG_INSISTENT
        manager.notify(card.key.tag, CARD_ID, n)
        return presentation
    }

    private fun label(fromIphone: String, fallback: Int): String =
        fromIphone.trim().ifEmpty { context.getString(fallback) }

    private fun actionIntent(key: NotificationKey, intentAction: String, action: ActionId, kind: Int): PendingIntent {
        // filterEquals() ignores extras: the identifier keeps each card/action PendingIntent distinct even if
        // two request codes collide, so a button can never carry another card's key.
        val intent = CommandReceiver.wrap(context, BridgeService.cardIntent(context, intentAction, key, action))
            .setIdentifier("${key.tag}#$kind")
        return PendingIntent.getBroadcast(
            context, BridgeService.requestCode(key, kind), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * REQUESTED is not proof of the final effect: ANCS Removed/Modified reports it later. A requested call
     * action stops the ring now and drops the buttons; other cards keep their state until ANCS reports.
     * FAILED/UNAVAILABLE re-post silently with a short note and keep the buttons. Never alerts again.
     */
    private fun actionResult(effect: SessionEffect.ActionResult) {
        val key = effect.key
        ActiveCards.actionResult(key, effect.action, effect.status)
        BridgeStatus.event("Acción ${effect.action.name} ${effect.status.name} gen=${key.sessionGeneration} uid=${key.uid}")
        val last = shown[key] ?: return
        // A card the user swiped away (or whose ring timed out) is never resurrected by feedback.
        if (manager.activeNotifications.none { it.tag == key.tag && it.id == CARD_ID }) return
        when (effect.status) {
            ActionStatus.REQUESTED ->
                if (last.callKind == CallKind.INCOMING) render(last.card, alert = false, ActionStatus.REQUESTED)
            ActionStatus.FAILED, ActionStatus.UNAVAILABLE -> render(last.card, alert = false, effect.status)
        }
    }

    private fun remove(key: NotificationKey) {
        ActiveCards.remove(key)
        shown.remove(key)
        manager.cancel(key.tag, CARD_ID)
    }

    private fun removeBefore(generation: Long) {
        ActiveCards.removeBefore(generation)
        shown.keys.removeAll { it.sessionGeneration < generation }
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
        private const val CALL_GREEN = 0xFF34A853.toInt()

        // Request-code kinds for BridgeService.requestCode(key, kind); distinct per card and per intent.
        private const val REQUEST_POSITIVE = 1
        private const val REQUEST_NEGATIVE = 2
        private const val REQUEST_DISMISS = 3
        private const val REQUEST_OPEN_CALL = 4
    }
}
