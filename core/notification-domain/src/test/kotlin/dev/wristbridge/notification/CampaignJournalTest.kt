package dev.wristbridge.notification

import dev.wristbridge.protocol.ancs.ActionId
import dev.wristbridge.protocol.ancs.CategoryId
import dev.wristbridge.protocol.gatt.MonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2-08.K campaign journal. All values are synthetic; the journal has no way to receive content. */
class CampaignJournalTest {
    private var monoMs = 1_000L
    private var wallMs = 1_760_000_000_000L

    private fun journal(capacity: Int = CampaignJournal.DEFAULT_CAPACITY) =
        CampaignJournal(MonotonicClock { monoMs * 1_000_000 }, { wallMs }, capacity)

    private fun advance(ms: Long) {
        monoMs += ms
        wallMs += ms
    }

    private fun CampaignJournal.receive(gen: Long, uid: Long, tag: FixtureTag?, alert: Boolean = true) =
        cardReceived(gen, uid, CategoryId.SOCIAL, tag, 12, alert)

    private fun tag(run: String, n: Int) = FixtureTag(run, "F%03d".format(n))

    /** C01 */
    @Test
    fun c01_sessionStartAndEndAreRecordedAndDoubleEndIsIgnored() {
        val j = journal()
        j.sessionStart(3)
        assertNotNull(j.sessionEnd(3, "desconexión"))
        assertNull(j.sessionEnd(3, "otra vez"))
        assertNull(j.sessionEnd(9, "otra generación"))
        val s = j.stats()
        assertEquals(1, s.sessionsStarted)
        assertEquals(1, s.sessionsEnded)
        assertEquals(listOf(CampaignEventType.SESSION_START, CampaignEventType.SESSION_END), j.events().map { it.type })
    }

    /** C02 */
    @Test
    fun c02_cardReceivedKeepsOnlyIdsSizesCategoryAndFixture() {
        val j = journal()
        val e = j.cardReceived(4, 77, CategoryId.INCOMING_CALL, FixtureTag("run-1", "F001"), 42, alert = true)
        assertEquals(4L, e.generation)
        assertEquals(77L, e.uid)
        assertEquals("INCOMING_CALL", e.category)
        assertEquals(FixtureTag("run-1", "F001"), e.fixture)
        assertEquals(42, e.bytes)
        assertEquals(true, e.alert)
        val line = CampaignJournal.toJsonLine(e)
        assertTrue(line.contains("\"run\":\"run-1\"") && line.contains("\"fix\":\"F001\"") && line.contains("\"bytes\":42"), line)
    }

    /** C03 */
    @Test
    fun c03_boundedBufferDropsOldestButKeepsCumulativeCounters() {
        val j = journal(capacity = 5)
        repeat(8) { j.receive(1, it.toLong(), null); advance(10) }
        val s = j.stats()
        assertEquals(5, s.retainedEvents)
        assertEquals(3, s.dropped)
        assertEquals(8, s.totalEvents)
        assertEquals(8, s.received)
        assertEquals(listOf(3L, 4L, 5L, 6L, 7L), j.events().map { it.seq })
        assertEquals(5, j.toJsonLines().lines().count { it.isNotEmpty() })
    }

    /** C04 */
    @Test
    fun c04_sameFixtureTwiceInSameGenerationIsDuplicate() {
        val j = journal()
        j.receive(2, 1, tag("r", 1))
        j.receive(2, 2, tag("r", 1), alert = false)
        assertEquals(1, j.stats().duplicates)
        assertEquals(0, j.stats().replays)
    }

    /** C05 */
    @Test
    fun c05_silentReplayInNewGenerationIsNotDuplicateButSecondAlertIs() {
        val j = journal()
        j.receive(2, 1, tag("r", 1))
        j.receive(3, 1, tag("r", 1), alert = false)
        var s = j.stats()
        assertEquals(0, s.duplicates)
        assertEquals(1, s.replays)
        j.receive(4, 1, tag("r", 1), alert = true)
        s = j.stats()
        assertEquals(1, s.duplicates)
        assertEquals(1, s.replays)
    }

    /** C06 */
    @Test
    fun c06_missingFixturesPerRunAgainstExpectedList() {
        val j = journal()
        j.receive(1, 1, tag("a", 1)); j.receive(1, 2, tag("a", 3)); j.receive(1, 3, tag("b", 1))
        j.receive(1, 4, null)
        val expected = mapOf("a" to setOf("F001", "F002", "F003", "F004"), "b" to setOf("F001"), "c" to setOf("F001"))
        val s = j.stats(expected)
        assertEquals(mapOf("a" to listOf("F002", "F004"), "b" to emptyList<String>(), "c" to listOf("F001")), s.missingFixtures)
        assertEquals(mapOf("a" to 2, "b" to 1), s.fixturesReceived)
        assertTrue(j.stats().missingFixtures.isEmpty())
    }

    /** C07 */
    @Test
    fun c07_reconnectLatencyPercentilesUseNearestRank() {
        val j = journal()
        for (n in 1..20) {
            j.linkDown("x"); advance(n * 1_000L); j.linkUp(); advance(100)
        }
        val s = j.stats()
        assertEquals(20, s.linkDowns)
        assertEquals(20, s.linkUps)
        assertEquals(10_000L, s.reconnectP50Ms)
        assertEquals(19_000L, s.reconnectP95Ms)
        assertEquals(20_000L, s.longestOutageMs)
        assertEquals(20_000L, s.lastOutageMs)
        assertNull(s.ongoingOutageMs)
        assertNull(journal().stats().reconnectP50Ms)
    }

    /** C08 */
    @Test
    fun c08_ongoingOutageCountsTowardsLongest() {
        val j = journal()
        j.linkDown(); advance(2_000); j.linkUp(); advance(1_000)
        j.linkDown(); advance(9_000)
        val s = j.stats()
        assertEquals(9_000L, s.ongoingOutageMs)
        assertEquals(9_000L, s.longestOutageMs)
        assertEquals(2_000L, s.lastOutageMs)
    }

    /** C09 */
    @Test
    fun c09_repeatedLinkTransitionsAreCollapsed() {
        val j = journal()
        assertNotNull(j.linkUp())
        assertNull(j.linkUp())
        assertNotNull(j.linkDown())
        advance(500)
        assertNull(j.linkDown())
        advance(500)
        val up = j.linkUp()
        assertEquals(1_000L, up?.elapsedMs)
        assertEquals(2, j.stats().linkUps)
        assertEquals(1, j.stats().linkDowns)
    }

    /** C10 */
    @Test
    fun c10_jsonEscapesQuotesBackslashControlAndKeepsUnicode() {
        val j = journal()
        j.sessionStart(1)
        val e = j.sessionEnd(1, "a\"b\\c\nd\te\u0001f ñ · F001")!!
        val line = CampaignJournal.toJsonLine(e)
        assertTrue(!line.contains('\n') && !line.contains('\u0001'), line)
        assertTrue(line.contains("a\\\"b\\\\c\\nd\\te\\u0001f ñ · F001"), line)
        assertEquals(e.reason, CampaignJournal.parseLine(line)?.reason)
    }

    /** C11 */
    @Test
    fun c11_jsonLinesRoundTripRestoresEventsAndStats() {
        val j = journal()
        j.sessionStart(1)
        j.receive(1, 5, tag("r", 1)); advance(5)
        j.cardUpdated(1, 5, CategoryId.EMAIL, tag("r", 1), 9)
        j.action(1, 5, ActionId.NEGATIVE, ActionStatus.FAILED)
        j.cardRemoved(1, 5, RemovedBy.WATCH_DISMISS)
        j.cardRemoved(1, 5, RemovedBy.ANCS)
        j.linkDown("gatt=19"); advance(3_000); j.linkUp()
        j.sessionEnd(1, "fin")
        val before = j.stats()

        val parsed = CampaignJournal.parseLines(j.toJsonLines())
        assertEquals(j.events(), parsed)
        val k = journal()
        k.restore(parsed)
        assertEquals(before.copy(ongoingOutageMs = null), k.stats().copy(ongoingOutageMs = null))
        // Sequence continues after the restored events.
        assertEquals(j.events().last().seq + 1, k.sessionStart(2).seq)
    }

    /** C12 */
    @Test
    fun c12_parserRejectsMalformedLinesAndIgnoresUnknownKeys() {
        val ok = "{\"seq\":1,\"wall\":2,\"mono\":3,\"type\":\"LINK_UP\",\"future\":\"x\"}"
        assertNotNull(CampaignJournal.parseLine(ok))
        val bad = listOf(
            "", "{", "not json", "{\"seq\":1}", "{\"seq\":1,\"wall\":2,\"mono\":3,\"type\":\"NOPE\"}",
            "{\"seq\":1,\"wall\":2,\"mono\":3,\"type\":\"LINK_UP\"} trailing",
            "{\"seq\":1,\"wall\":2,\"mono\":3,\"type\":\"LINK_UP\"", "{\"seq\":\"1\",\"wall\":2,\"mono\":3,\"type\":\"LINK_UP\"}",
        )
        for (b in bad) assertNull(CampaignJournal.parseLine(b), b)
        assertEquals(1, CampaignJournal.parseLines("$ok\n{\"seq\"\n\n").size)
    }

    /** C13 */
    @Test
    fun c13_removalsAreSplitByWhoRetiredTheCard() {
        val j = journal()
        j.cardRemoved(1, 1, RemovedBy.ANCS); j.cardRemoved(1, 2, RemovedBy.ANCS); j.cardRemoved(1, 3, RemovedBy.WATCH_DISMISS)
        val s = j.stats()
        assertEquals(2, s.removedByAncs)
        assertEquals(1, s.removedByWatch)
    }

    /** C14 */
    @Test
    fun c14_actionsCountNotRequestedOutcomes() {
        val j = journal()
        j.action(1, 1, ActionId.POSITIVE, ActionStatus.REQUESTED)
        j.action(1, 2, ActionId.NEGATIVE, ActionStatus.FAILED)
        j.action(1, 3, ActionId.NEGATIVE, ActionStatus.UNAVAILABLE)
        val s = j.stats()
        assertEquals(3, s.actions)
        assertEquals(2, s.actionsNotRequested)
    }

    /** C15 */
    @Test
    fun c15_everyLineUsesOnlyAllowedKeys() {
        val allowed = setOf(
            "seq", "wall", "mono", "type", "gen", "uid", "cat", "run", "fix", "bytes", "alert", "by", "action", "status", "reason", "ms",
        )
        val j = journal()
        j.sessionStart(1)
        j.receive(1, 1, tag("r", 1)); j.cardUpdated(1, 1, CategoryId.OTHER, null, 3)
        j.action(1, 1, ActionId.POSITIVE, ActionStatus.REQUESTED); j.cardRemoved(1, 1, RemovedBy.ANCS)
        j.linkDown("gatt=8"); advance(1); j.linkUp(); j.sessionEnd(1, "fin")
        for (line in j.toJsonLines().lines().filter { it.isNotEmpty() }) {
            val keys = Regex("\"([a-z]+)\":").findAll(line).map { it.groupValues[1] }.toSet()
            assertTrue(allowed.containsAll(keys), "$keys in $line")
        }
    }

    /** C16 */
    @Test
    fun c16_summaryJsonCarriesStatsAndNulls() {
        val j = journal()
        j.receive(1, 1, tag("r", 1))
        val json = j.summaryJson(mapOf("r" to setOf("F001", "F002")))
        assertTrue(json.startsWith("{") && json.endsWith("}"), json)
        assertTrue(json.contains("\"received\":1"), json)
        assertTrue(json.contains("\"fixturesReceived\":{\"r\":1}"), json)
        assertTrue(json.contains("\"missingFixtures\":{\"r\":[\"F002\"]}"), json)
        assertTrue(json.contains("\"reconnectP50Ms\":null"), json)
        assertNotNull(JsonObjectReader("{\"a\":1}").read())
    }

    /** C17 */
    @Test
    fun c17_readyLatencyIsMeasuredFromLinkDownToNextSessionStart() {
        val j = journal()
        j.sessionStart(1)
        assertNull(j.events().last().elapsedMs)
        j.linkDown(); advance(1_000); j.linkUp(); advance(4_000)
        val e = j.sessionStart(2)
        assertEquals(5_000L, e.elapsedMs)
        j.sessionStart(3)
        assertNull(j.events().last().elapsedMs)
        val s = j.stats()
        assertEquals(5_000L, s.readyP50Ms)
        assertEquals(1_000L, s.reconnectP50Ms)
    }

    /** C18 */
    @Test
    fun c18_resetClearsEverything() {
        val j = journal()
        j.sessionStart(1); j.receive(1, 1, tag("r", 1)); j.linkDown()
        j.reset()
        assertEquals(CampaignStats(), j.stats())
        assertEquals("", j.toJsonLines())
        assertEquals(0L, j.sessionStart(2).seq)
    }
}
