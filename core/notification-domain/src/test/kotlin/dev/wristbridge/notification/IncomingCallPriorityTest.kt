package dev.wristbridge.notification

import dev.wristbridge.protocol.gatt.GattOutcome
import dev.wristbridge.protocol.gatt.MonotonicClock
import kotlin.test.*

class IncomingCallPriorityTest {
    private fun event(uid: Int, category: Int = 4, flags: Int = 0, kind: Int = 0) =
        byteArrayOf(kind.toByte(), flags.toByte(), category.toByte(), 1, uid.toByte(), 0, 0, 0)
    private fun uid(write: SessionEffect.WriteControlPoint) = write.bytes[1].toInt() and 255
    private fun setup(): Pair<AncsSessionEngine, SessionEffect.WriteControlPoint> {
        val engine = AncsSessionEngine(MonotonicClock { 0 })
        engine.startSession("synthetic")
        val current = engine.onNotificationSource(engine.generation, event(1)).filterIsInstance<SessionEffect.WriteControlPoint>().single()
        engine.onNotificationSource(engine.generation, event(2))
        engine.onNotificationSource(engine.generation, event(3))
        return engine to current
    }

    @Test fun liveIncomingCallIsNextAfterCurrentTransactionWithoutPreemptingGatt() {
        val (engine, current) = setup()
        assertTrue(engine.onNotificationSource(engine.generation, event(9, category = 1)).isEmpty())
        val out = engine.onControlPointWritten(engine.generation, current.transactionId, GattOutcome.Failed(5))
        assertEquals(9, uid(out.filterIsInstance<SessionEffect.WriteControlPoint>().single()))
    }

    @Test fun removedQueuedCallIsNeverFetched() {
        val (engine, current) = setup()
        engine.onNotificationSource(engine.generation, event(9, category = 1))
        engine.onNotificationSource(engine.generation, event(9, category = 1, kind = 2))
        val out = engine.onControlPointWritten(engine.generation, current.transactionId, GattOutcome.Failed(5))
        assertEquals(2, uid(out.filterIsInstance<SessionEffect.WriteControlPoint>().single()))
    }

    @Test fun preExistingCallDoesNotJumpAheadOfLiveBacklog() {
        val (engine, current) = setup()
        engine.onNotificationSource(engine.generation, event(9, category = 1, flags = 4))
        val out = engine.onControlPointWritten(engine.generation, current.transactionId, GattOutcome.Failed(5))
        assertEquals(2, uid(out.filterIsInstance<SessionEffect.WriteControlPoint>().single()))
    }
}
