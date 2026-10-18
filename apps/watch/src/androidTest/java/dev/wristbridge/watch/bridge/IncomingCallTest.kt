package dev.wristbridge.watch.bridge

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.wristbridge.notification.*
import dev.wristbridge.protocol.ancs.AncsUid
import dev.wristbridge.protocol.ancs.CategoryId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingCallTest {
    @Test fun incomingTelegramCardPostsWithoutCallStyleOrBridgeCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val key = NotificationKey("synthetic-test", 9_000_000_000, AncsUid(424242))
        val card = NotificationCard(
            key, CategoryId.INCOMING_CALL, "ph.telegra.Telegraph", "Telegram",
            "LLAMANTE SINTÉTICO", "", "PRUEBA LOCAL", false, true, true, true,
            false, false, "Aceptar", "Rechazar", null,
        )
        val presenter = CardPresenter(context)
        try {
            // Silent replay exercises the same incoming-card template, without ringing the user's watch.
            presenter.apply(SessionEffect.PostCard(card, alert = false))
            val postDeadline = SystemClock.elapsedRealtime() + 3_000
            var posted = manager.activeNotifications.singleOrNull { it.tag == key.tag && it.id == CardPresenter.CARD_ID }
            while (posted == null && SystemClock.elapsedRealtime() < postDeadline) {
                SystemClock.sleep(25)
                posted = manager.activeNotifications.singleOrNull { it.tag == key.tag && it.id == CardPresenter.CARD_ID }
            }
            assertNotNull("Incoming card was rejected by NotificationManager", posted)
            val n = posted!!.notification
            assertEquals(Notification.CATEGORY_CALL, n.category)
            assertNull(n.fullScreenIntent)
            assertFalse(n.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty().contains("CallStyle"))
            assertEquals(0, n.flags and Notification.FLAG_INSISTENT)
            assertNotNull(n.contentIntent)
            assertEquals("Llamada entrante", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            presenter.apply(SessionEffect.RemoveCard(key))
            val removeDeadline = SystemClock.elapsedRealtime() + 3_000
            while (manager.activeNotifications.any { it.tag == key.tag } && SystemClock.elapsedRealtime() < removeDeadline) {
                SystemClock.sleep(25)
            }
            assertFalse(manager.activeNotifications.any { it.tag == key.tag })
        } finally {
            manager.cancel(key.tag, CardPresenter.CARD_ID)
            ActiveCards.remove(key)
        }
    }
}
