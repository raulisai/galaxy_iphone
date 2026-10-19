package dev.wristbridge.assistant.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeClock(var now: Long = 0L) : VoiceClock {
    override fun nowMs(): Long = now
}

private fun chunk(session: Long, size: Int = 1000, ms: Int = 100) = PcmChunk(session, ByteArray(size), ms)

class BoundedAudioQueueTest {
    @Test
    fun `V06 queue never exceeds byte or duration bounds under sustained overload`() {
        val q = BoundedAudioQueue(maxBytes = 5_000, maxDurationMs = 500, policy = OverflowPolicy.DROP_OLDEST)
        repeat(1_000) {
            q.offer(chunk(1))
            assertTrue(q.queuedBytes <= 5_000)
            assertTrue(q.queuedDurationMs <= 500)
        }
        assertEquals(5, q.size)
        val c = q.counters()
        assertEquals(995, c.droppedChunks)
        assertEquals(995, c.overruns)
    }

    @Test
    fun `drop oldest keeps newest chunks in order`() {
        val q = BoundedAudioQueue(3_000, 10_000, OverflowPolicy.DROP_OLDEST)
        for (i in 1..4) q.offer(PcmChunk(1, ByteArray(1000) { i.toByte() }, 100))
        assertEquals(2, q.poll()!!.bytes[0].toInt())
        assertEquals(3, q.poll()!!.bytes[0].toInt())
        assertEquals(4, q.poll()!!.bytes[0].toInt())
        assertNull(q.poll())
    }

    @Test
    fun `reject policy keeps queued data and counts overrun`() {
        val q = BoundedAudioQueue(2_000, 10_000, OverflowPolicy.REJECT)
        assertEquals(OfferResult.Accepted, q.offer(chunk(1)))
        assertEquals(OfferResult.Accepted, q.offer(chunk(1)))
        assertEquals(OfferResult.Rejected, q.offer(chunk(1)))
        assertEquals(2, q.size)
        val c = q.counters()
        assertEquals(1, c.overruns)
        assertEquals(1, c.rejectedChunks)
        assertEquals(0, c.droppedChunks)
    }

    @Test
    fun `duration limit alone can trigger overrun`() {
        val q = BoundedAudioQueue(1_000_000, 250, OverflowPolicy.REJECT)
        q.offer(chunk(1, 10, 200))
        assertEquals(OfferResult.Rejected, q.offer(chunk(1, 10, 100)))
    }

    @Test
    fun `chunk larger than capacity is too large`() {
        val q = BoundedAudioQueue(500, 1000, OverflowPolicy.DROP_OLDEST)
        assertEquals(OfferResult.TooLarge, q.offer(chunk(1, 600)))
        assertEquals(0, q.size)
    }

    @Test
    fun `closed queue refuses offers`() {
        val q = BoundedAudioQueue(5_000, 1000, OverflowPolicy.DROP_OLDEST)
        q.offer(chunk(1))
        q.close()
        assertEquals(0, q.size)
        assertEquals(OfferResult.Closed, q.offer(chunk(1)))
    }
}

class VoiceSessionTest {
    private val clock = FakeClock()
    private var released = 0

    private fun session(id: Long = 1, limits: VoiceLimits = VoiceLimits()) =
        VoiceSession(id, clock, limits) { released++ }

    private fun speaking(s: VoiceSession) {
        assertTrue(s.startListening())
        assertTrue(s.onEndOfUserSpeech())
        s.onModelAudio(chunk(s.id))
    }

    @Test
    fun `V05 interrupt flushes old audio which never reappears`() {
        val s = session()
        speaking(s)
        s.onModelAudio(chunk(1))
        assertTrue(s.interrupt())
        assertEquals(VoiceState.Listening, s.state)
        assertNull(s.pollPlayback())
        assertEquals(1, s.metrics().interruptions)
        // new capture works again after barge-in
        assertEquals(OfferResult.Accepted, s.onCaptured(chunk(1)))
        assertTrue(s.onEndOfUserSpeech())
        assertNull(s.pollPlayback())
        assertTrue(s.metrics().droppedChunks >= 2)
    }

    @Test
    fun `V07 new session ignores callbacks of the cancelled one`() {
        val releasedIds = mutableListOf<Long>()
        val m = VoiceSessionManager(clock, releaseFor = { releasedIds += it })
        val first = m.begin()
        first.startListening()
        first.onEndOfUserSpeech()
        val second = m.begin()
        assertEquals(VoiceState.Cancelled, first.state)
        assertEquals(listOf(1L), releasedIds)
        second.startListening()
        second.onEndOfUserSpeech()
        assertEquals(OfferResult.Closed, m.deliverModelAudio(chunk(1)))
        assertEquals(1, m.staleRouted)
        assertEquals(OfferResult.Closed, first.onModelAudio(chunk(1)))
        assertEquals(VoiceState.Thinking, second.state)
        assertIs<OfferResult.Accepted>(m.deliverModelAudio(chunk(2)))
        assertEquals(VoiceState.Speaking, second.state)
    }

    @Test
    fun `cancel releases exactly once and session cannot restart`() {
        val s = session()
        s.startListening()
        s.cancel("user")
        s.cancel("again")
        s.fail("late")
        assertEquals(1, released)
        assertEquals(VoiceState.Cancelled, s.state)
        assertFalse(s.micOpen)
        assertFalse(s.startListening())
    }

    @Test
    fun `network return never reopens a cancelled session`() {
        val s = session()
        s.startListening()
        s.cancel()
        s.onNetworkAvailable()
        assertFalse(s.micOpen)
        assertEquals(VoiceState.Cancelled, s.state)
        assertFalse(s.startListening())
        assertEquals(OfferResult.Closed, s.onCaptured(chunk(1)))
    }

    @Test
    fun `failed session also stays closed after network return`() {
        val s = session()
        s.startListening()
        s.fail("network")
        s.onNetworkAvailable()
        assertEquals(VoiceState.Failed, s.state)
        assertFalse(s.micOpen)
        assertEquals(1, released)
    }

    @Test
    fun `mic is released when user stops speaking`() {
        val s = session()
        s.startListening()
        assertTrue(s.micOpen)
        s.onEndOfUserSpeech()
        assertFalse(s.micOpen)
        assertEquals(OfferResult.Closed, s.onCaptured(chunk(1)))
    }

    @Test
    fun `permanent focus loss cancels and releases deterministically`() {
        val s = session()
        speaking(s)
        s.onFocus(FocusEvent.Loss)
        assertEquals(VoiceState.Cancelled, s.state)
        assertEquals(1, released)
        s.onFocus(FocusEvent.Gain)
        assertEquals(VoiceState.Cancelled, s.state)
        assertEquals(1, s.metrics().focusLosses)
    }

    @Test
    fun `transient focus loss pauses playback and gain resumes it`() {
        val s = session()
        speaking(s)
        s.onFocus(FocusEvent.LossTransient)
        assertTrue(s.paused)
        assertNull(s.pollPlayback())
        s.onFocus(FocusEvent.Gain)
        assertFalse(s.paused)
        assertTrue(s.pollPlayback() != null)
        assertEquals(0, released)
    }

    @Test
    fun `transient loss while listening pauses capture`() {
        val s = session()
        s.startListening()
        s.onFocus(FocusEvent.LossTransient)
        assertEquals(OfferResult.Closed, s.onCaptured(chunk(1)))
        s.onFocus(FocusEvent.Gain)
        assertEquals(OfferResult.Accepted, s.onCaptured(chunk(1)))
    }

    @Test
    fun `duck leaves state untouched`() {
        val s = session()
        speaking(s)
        s.onFocus(FocusEvent.LossTransientCanDuck)
        assertFalse(s.paused)
        assertEquals(VoiceState.Speaking, s.state)
    }

    @Test
    fun `time to first byte uses injected clock and is recorded once`() {
        val s = session()
        s.startListening()
        clock.now = 1_000
        s.onEndOfUserSpeech()
        assertNull(s.metrics().timeToFirstByteMs)
        clock.now = 1_450
        s.onModelAudio(chunk(1))
        clock.now = 9_000
        s.onModelAudio(chunk(1))
        assertEquals(450L, s.metrics().timeToFirstByteMs)
    }

    @Test
    fun `metrics report drops and overruns from playback overload`() {
        val limits = VoiceLimits(playbackMaxBytes = 2_000, playbackMaxDurationMs = 10_000)
        val s = session(limits = limits)
        s.startListening()
        s.onEndOfUserSpeech()
        repeat(5) { s.onModelAudio(chunk(1)) }
        val m = s.metrics()
        assertEquals(3, m.droppedChunks)
        assertEquals(3, m.overruns)
        assertEquals(3000L, m.droppedBytes)
    }

    @Test
    fun `complete releases and ends the session`() {
        val s = session()
        speaking(s)
        assertTrue(s.complete())
        assertEquals(1, released)
        assertEquals(VoiceState.Idle, s.state)
        assertFalse(s.startListening())
    }

    @Test
    fun `invalid transitions are refused`() {
        val s = session()
        assertFalse(s.onEndOfUserSpeech())
        assertFalse(s.interrupt())
        assertFalse(s.complete())
        assertEquals(OfferResult.Closed, s.onModelAudio(chunk(1)))
        assertEquals(1, s.metrics().staleCallbacks)
    }
}
