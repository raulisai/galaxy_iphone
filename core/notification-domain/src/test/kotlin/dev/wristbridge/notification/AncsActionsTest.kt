package dev.wristbridge.notification

import dev.wristbridge.protocol.ancs.ActionId
import dev.wristbridge.protocol.ancs.AncsUid
import dev.wristbridge.protocol.gatt.GattOutcome
import dev.wristbridge.protocol.gatt.MonotonicClock
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P2-07 action requests. Byte builders follow the ANCS layout; all values are synthetic. */
class AncsActionsTest {
    private var now = 1_000_000_000L
    private val engine = AncsSessionEngine(MonotonicClock { now })
    private val gen = engine.startSession("iphone-A").let { engine.generation }

    private fun ns(event: Int, uid: Long, flags: Int, category: Int) = byteArrayOf(
        event.toByte(), flags.toByte(), category.toByte(), 1,
        (uid and 0xFF).toByte(), ((uid shr 8) and 0xFF).toByte(), ((uid shr 16) and 0xFF).toByte(), ((uid shr 24) and 0xFF).toByte(),
    )

    private fun notifResponse(uid: Long, values: Map<Int, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0)
        for (s in 0..3) out.write(((uid shr (8 * s)) and 0xFF).toInt())
        for (id in 0..7) {
            val v = values[id].orEmpty().toByteArray(Charsets.UTF_8)
            out.write(id); out.write(v.size and 0xFF); out.write(v.size shr 8); out.write(v)
        }
        return out.toByteArray()
    }

    private fun appResponse(app: String, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(1); out.write(app.toByteArray()); out.write(0)
        val v = name.toByteArray()
        out.write(0); out.write(v.size); out.write(0); out.write(v)
        return out.toByteArray()
    }

    private fun writes(e: List<SessionEffect>) = e.filterIsInstance<SessionEffect.WriteControlPoint>()
    private fun results(e: List<SessionEffect>) = e.filterIsInstance<SessionEffect.ActionResult>()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Posts an incoming call (category 1) with both actions; returns its card and the pending app-name write. */
    private fun postCall(uid: Long, flags: Int = 8 or 16): Pair<NotificationCard, SessionEffect.WriteControlPoint?> {
        val w = writes(engine.onNotificationSource(gen, ns(0, uid, flags, 1))).single()
        engine.onControlPointWritten(gen, w.transactionId, GattOutcome.Success)
        val done = engine.onDataSource(
            gen, notifResponse(uid, mapOf(0 to "com.apple.mobilephone", 1 to "Llamante sintético", 6 to "Aceptar", 7 to "Rechazar")),
        )
        return done.filterIsInstance<SessionEffect.PostCard>().single().card to writes(done).singleOrNull()
    }

    private fun finishAppLookup(w: SessionEffect.WriteControlPoint?): List<SessionEffect> {
        if (w == null) return emptyList()
        engine.onControlPointWritten(gen, w.transactionId, GattOutcome.Success)
        return engine.onDataSource(gen, appResponse("com.apple.mobilephone", "Teléfono"))
    }

    @Test
    fun a01_actionWaitsForActiveTransactionThenWritesAndReportsRequested() {
        val (card, appWrite) = postCall(5)
        assertEquals("Aceptar", card.positiveActionLabel)
        assertEquals("Rechazar", card.negativeActionLabel)
        assertTrue(writes(engine.performAction(card.key, ActionId.NEGATIVE)).isEmpty(), "one control-point transaction at a time")
        val action = writes(finishAppLookup(appWrite)).single()
        assertEquals("020500000001", hex(action.bytes))
        val r = results(engine.onControlPointWritten(gen, action.transactionId, GattOutcome.Success)).single()
        assertEquals(ActionStatus.REQUESTED, r.status)
        assertEquals(1, engine.counters.actionsRequested)
        assertTrue(!engine.transactionActive)
    }

    @Test
    fun a02_actionNotAdvertisedOrFromOldGenerationIsUnavailable() {
        val (card, appWrite) = postCall(6, flags = 16)
        finishAppLookup(appWrite)
        assertEquals(null, card.positiveActionLabel)
        assertEquals(ActionStatus.UNAVAILABLE, results(engine.performAction(card.key, ActionId.POSITIVE)).single().status)
        val stale = card.key.copy(sessionGeneration = gen - 1)
        assertEquals(ActionStatus.UNAVAILABLE, results(engine.performAction(stale, ActionId.NEGATIVE)).single().status)
        val unknown = card.key.copy(uid = AncsUid(999))
        assertEquals(ActionStatus.UNAVAILABLE, results(engine.performAction(unknown, ActionId.NEGATIVE)).single().status)
    }

    @Test
    fun a03_ancsErrorOrTimeoutReportsFailedAndQueueContinues() {
        val (card, appWrite) = postCall(7)
        finishAppLookup(appWrite)
        val w = writes(engine.performAction(card.key, ActionId.POSITIVE)).single()
        val failed = results(engine.onControlPointWritten(gen, w.transactionId, GattOutcome.Failed(0xA3))).single()
        assertEquals(ActionStatus.FAILED, failed.status)
        assertEquals(1, engine.counters.actionsFailed)

        val w2 = writes(engine.performAction(card.key, ActionId.NEGATIVE)).single()
        now += 6_000_000_000L
        assertEquals(ActionStatus.FAILED, results(engine.onTick()).single().status)
        assertTrue(!engine.transactionActive)
        assertTrue(w2.transactionId > w.transactionId)
    }

    @Test
    fun a04_removedBeforeWriteDropsActionAndDataSourceDuringActionIsUnsolicited() {
        val (card, appWrite) = postCall(8)
        engine.performAction(card.key, ActionId.NEGATIVE)
        engine.onNotificationSource(gen, ns(2, 8, 0, 1))
        assertTrue(writes(finishAppLookup(appWrite)).isEmpty(), "removed call: queued action is dropped")

        val (card2, appWrite2) = postCall(9)
        assertEquals(null, appWrite2, "display name already cached")
        writes(engine.performAction(card2.key, ActionId.POSITIVE)).single()
        val before = engine.counters.unsolicitedData
        engine.onDataSource(gen, byteArrayOf(0, 9, 0, 0, 0))
        assertEquals(before + 1, engine.counters.unsolicitedData)
    }
}
