package dev.wristbridge.protocol.ancs

import dev.wristbridge.protocol.MiniJson
import dev.wristbridge.protocol.hex
import dev.wristbridge.protocol.list
import dev.wristbridge.protocol.obj
import dev.wristbridge.protocol.repoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DataSourceAssemblerTest {
    private val ds = MiniJson.parse(repoFile("test/fixtures/ancs/vectors.json").readText()).obj()["dataSource"].list()
        .associate { it.obj()["case"] as String to it.obj() }

    private fun expectedFor(case: Map<String, Any?>): ExpectedResponse {
        val ids = case["requested"].list().map { (it as Long).toInt() }
        val app = case["appIdentifier"] as String?
        return if (app != null) {
            ExpectedResponse.App(app, ids)
        } else {
            ExpectedResponse.Notification(AncsUid((case["expect"].obj()["uid"] as Long)), ids)
        }
    }

    private fun texts(r: AncsResponse) = r.attributes.mapKeys { it.key.toString() }.mapValues { it.value.text }

    private fun feedAll(a: DataSourceAssembler, chunks: List<ByteArray>): AssemblyResult {
        var last: AssemblyResult = AssemblyResult.NeedMore
        for ((i, c) in chunks.withIndex()) {
            last = a.feed(c)
            if (i < chunks.lastIndex) assertEquals(AssemblyResult.NeedMore, last, "early result at chunk $i")
        }
        return last
    }

    @Test
    fun fixtureResponsesDecodeInOneChunk() {
        for ((name, case) in ds) {
            val a = DataSourceAssembler()
            a.begin(expectedFor(case))
            val done = assertIs<AssemblyResult.Complete>(a.feed(hex(case["hex"] as String)), name)
            assertEquals(case["expect"].obj()["attributes"], texts(done.response), name)
            assertEquals(0, done.trailingBytes)
            assertFalse(a.isActive)
        }
    }

    @Test
    fun u05_u06_u07_everySplitPointEqualsUnfragmented() {
        for ((name, case) in ds) {
            val bytes = hex(case["hex"] as String)
            val whole = DataSourceAssembler().also { it.begin(expectedFor(case)) }.feed(bytes)
            val wholeTexts = texts((whole as AssemblyResult.Complete).response)
            // Two-way cuts at every byte boundary, including inside UID, attribute length and UTF-8 runs.
            for (cut in 1 until bytes.size) {
                val a = DataSourceAssembler().also { it.begin(expectedFor(case)) }
                val r = feedAll(a, listOf(bytes.copyOfRange(0, cut), bytes.copyOfRange(cut, bytes.size)))
                assertEquals(wholeTexts, texts(assertIs<AssemblyResult.Complete>(r, "$name cut $cut").response))
            }
            // Byte-by-byte delivery.
            val a = DataSourceAssembler().also { it.begin(expectedFor(case)) }
            val r = feedAll(a, bytes.map { byteArrayOf(it) })
            assertEquals(wholeTexts, texts(assertIs<AssemblyResult.Complete>(r).response))
        }
    }

    @Test
    fun u07_malformedUtf8IsFlaggedExplicitly() {
        val a = DataSourceAssembler()
        a.begin(ExpectedResponse.Notification(AncsUid(1), listOf(1)))
        val r = assertIs<AssemblyResult.Complete>(a.feed(hex("00 01 00 00 00 01 02 00 C3 28")))
        val v = r.response.attributes.getValue(1)
        assertTrue(v.malformedUtf8)
        assertEquals(2, v.bytes.size)
    }

    @Test
    fun u08_emptyAttributeDiffersFromIncompleteResponse() {
        val a = DataSourceAssembler()
        a.begin(ExpectedResponse.Notification(AncsUid(1), listOf(1, 2)))
        assertEquals(AssemblyResult.NeedMore, a.feed(hex("00 01 00 00 00 01 01 00 54")))
        assertTrue(a.isActive, "still waiting for subtitle")
        val r = assertIs<AssemblyResult.Complete>(a.feed(hex("02 00 00")))
        assertEquals("", r.response.attributes.getValue(2).text)
    }

    @Test
    fun u08_unrequestedOrReservedAttributeFailsInsteadOfBlocking() {
        val a = DataSourceAssembler()
        a.begin(ExpectedResponse.Notification(AncsUid(1), listOf(1)))
        val r = assertIs<AssemblyResult.Failed>(a.feed(hex("00 01 00 00 00 09 00 00")))
        assertEquals(AssemblyFailure.UnexpectedAttribute(9), r.failure)
        assertFalse(a.isActive)
    }

    @Test
    fun u09_largeAnnouncedLengthIsBoundedByReceivedBytes() {
        val a = DataSourceAssembler(maxResponseBytes = 64)
        a.begin(ExpectedResponse.Notification(AncsUid(1), listOf(3)))
        // Announces 65535 bytes; no allocation from that number, failure when the cap is crossed.
        assertEquals(AssemblyResult.NeedMore, a.feed(hex("00 01 00 00 00 03 FF FF")))
        var result: AssemblyResult = AssemblyResult.NeedMore
        repeat(10) { if (result == AssemblyResult.NeedMore) result = a.feed(ByteArray(16)) }
        assertEquals(AssemblyFailure.ResponseTooLarge(64), assertIs<AssemblyResult.Failed>(result).failure)
    }

    @Test
    fun u10_responseForAnotherUidIsNotAssociated() {
        val a = DataSourceAssembler()
        a.begin(ExpectedResponse.Notification(AncsUid(42), listOf(1)))
        val r = assertIs<AssemblyResult.Failed>(a.feed(hex("00 2B 00 00 00 01 01 00 41")))
        assertEquals(AssemblyFailure.UidMismatch(AncsUid(42), AncsUid(43)), r.failure)
        assertEquals(AssemblyFailure.Unsolicited, (a.feed(hex("00")) as AssemblyResult.Failed).failure)
    }

    @Test
    fun u10_wrongCommandOrAppIsRejected() {
        val a = DataSourceAssembler()
        a.begin(ExpectedResponse.Notification(AncsUid(1), listOf(1)))
        assertIs<AssemblyFailure.CommandMismatch>(assertIs<AssemblyResult.Failed>(a.feed(hex("01"))).failure)
        a.begin(ExpectedResponse.App("com.a", listOf(0)))
        assertIs<AssemblyFailure.AppMismatch>(assertIs<AssemblyResult.Failed>(a.feed(hex("01 63 6F 6D 2E 62 00"))).failure)
    }

    @Test
    fun u12_resetDuringHeaderOrBodyLeavesNoResidue() {
        val good = hex("00 01 00 00 00 01 01 00 54")
        for (prefix in listOf("00 01 00", "00 01 00 00 00 01", "00 01 00 00 00 01 05 00 41 42")) {
            val a = DataSourceAssembler()
            a.begin(ExpectedResponse.Notification(AncsUid(9), listOf(1)))
            a.feed(hex(prefix))
            a.reset()
            a.begin(ExpectedResponse.Notification(AncsUid(1), listOf(1)))
            val r = assertIs<AssemblyResult.Complete>(a.feed(good), prefix)
            assertEquals("T", r.response.attributes.getValue(1).text)
        }
    }

    @Test
    fun trailingBytesAreCountedNotCarried() {
        val a = DataSourceAssembler()
        a.begin(ExpectedResponse.Notification(AncsUid(1), listOf(1)))
        val r = assertIs<AssemblyResult.Complete>(a.feed(hex("00 01 00 00 00 01 01 00 54 EE EE")))
        assertEquals(2, r.trailingBytes)
        assertFalse(a.isActive)
    }
}
