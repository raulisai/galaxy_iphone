package dev.wristbridge.protocol.ancs

import dev.wristbridge.protocol.MiniJson
import dev.wristbridge.protocol.hex
import dev.wristbridge.protocol.list
import dev.wristbridge.protocol.obj
import dev.wristbridge.protocol.repoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class NotificationSourceParserTest {
    private val vectors = MiniJson.parse(repoFile("test/fixtures/ancs/vectors.json").readText()).obj()

    private fun valid(bytes: ByteArray) =
        assertIs<NotificationSourceParse.Valid>(NotificationSourceParser.parse(bytes)).event

    @Test
    fun u01_fixtureVectorsDecodeExactly() {
        for (v in vectors["notificationSource"].list()) {
            val case = v.obj()
            val e = valid(hex(case["hex"] as String))
            val exp = case["expect"].obj()
            assertEquals(exp["event"], e.event.name, case["case"] as String)
            assertEquals(exp["flags"].list().toSet(), e.flags.map { it.name }.toSet(), case["case"] as String)
            assertEquals(exp["category"], e.category.name, case["case"] as String)
            assertEquals(exp["categoryCount"], e.categoryCount.toLong())
            assertEquals(exp["uid"], e.uid.value)
        }
    }

    @Test
    fun u01_everyEventCategoryAndFlagIsDistinct() {
        for (event in EventId.entries) for (cat in CategoryId.entries.filter { it != CategoryId.UNKNOWN }) {
            for (flag in EventFlag.entries) {
                val e = valid(byteArrayOf(event.raw.toByte(), flag.mask.toByte(), cat.raw.toByte(), 3, 9, 0, 0, 0))
                assertEquals(event, e.event)
                assertEquals(cat, e.category)
                assertEquals(setOf(flag), e.flags)
                assertEquals(3, e.categoryCount)
            }
        }
        val reserved = valid(byteArrayOf(0, 0xE0.toByte(), 200.toByte(), 0, 1, 0, 0, 0))
        assertEquals(CategoryId.UNKNOWN, reserved.category)
        assertEquals(200, reserved.rawCategory)
        assertEquals(emptySet(), reserved.flags)
        assertEquals(0xE0, reserved.rawFlags)
    }

    @Test
    fun u02_shortBuffersAreRejectedWithoutOverflow() {
        for (n in 0..7) {
            val r = NotificationSourceParser.parse(ByteArray(n))
            assertIs<NotificationSourceParse.Invalid>(r)
            assertEquals(n, r.length)
        }
        assertIs<NotificationSourceParse.Invalid>(NotificationSourceParser.parse(hex("03 00 00 00 01 00 00 00")))
        val longer = valid(hex("00 00 00 00 01 00 00 00 AA BB"))
        assertEquals(2, longer.extraBytes)
    }

    @Test
    fun u03_uidBoundariesAreUnsignedAndDistinct() {
        val uids = listOf(
            "00 00 00 00" to 0L,
            "FF FF FF 7F" to 2147483647L,
            "00 00 00 80" to 2147483648L,
            "FF FF FF FF" to 4294967295L,
        )
        val parsed = uids.map { (h, want) ->
            val e = valid(hex("00 00 00 00 $h"))
            assertEquals(want, e.uid.value)
            e.uid
        }
        assertEquals(parsed.size, parsed.toSet().size)
        assertNotEquals(parsed[2].value.toInt().toLong(), parsed[2].value)
    }
}
