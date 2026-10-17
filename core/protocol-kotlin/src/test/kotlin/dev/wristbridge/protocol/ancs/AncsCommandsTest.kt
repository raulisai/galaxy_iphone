package dev.wristbridge.protocol.ancs

import dev.wristbridge.protocol.MiniJson
import dev.wristbridge.protocol.hex
import dev.wristbridge.protocol.list
import dev.wristbridge.protocol.obj
import dev.wristbridge.protocol.repoFile
import dev.wristbridge.protocol.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AncsCommandsTest {
    private val golden = MiniJson.parse(repoFile("test/fixtures/ancs/vectors.json").readText()).obj()["commands"].list()
        .associate { it.obj()["case"] as String to hex(it.obj()["hex"] as String) }

    private fun assertGolden(case: String, actual: ByteArray) =
        assertEquals(golden.getValue(case).toHex(), actual.toHex(), case)

    @Test
    fun u04_defaultRequestMatchesIndependentFixture() {
        assertGolden(
            "get-notification-attributes-default-p1",
            AncsCommands.getNotificationAttributes(AncsUid(42), AncsCommands.defaultNotificationRequest()),
        )
    }

    @Test
    fun u04_otherCommandsMatchFixtures() {
        assertGolden(
            "get-notification-attributes-uid-max-title-only",
            AncsCommands.getNotificationAttributes(
                AncsUid(AncsUid.MAX),
                listOf(AttributeRequest(NotificationAttributeId.TITLE, 32)),
            ),
        )
        assertGolden(
            "get-app-attributes-display-name",
            AncsCommands.getAppAttributes("com.example", listOf(AppAttributeId.DISPLAY_NAME)),
        )
        assertGolden(
            "perform-negative-action-uid-2pow31",
            AncsCommands.performNotificationAction(AncsUid(2147483648L), ActionId.NEGATIVE),
        )
        assertGolden(
            "perform-positive-action-uid1",
            AncsCommands.performNotificationAction(AncsUid(1), ActionId.POSITIVE),
        )
    }

    @Test
    fun u04_actionLabelsAndOtherIdsNeverCarryMaxLength() {
        for (id in listOf(
            NotificationAttributeId.APP_IDENTIFIER, NotificationAttributeId.MESSAGE_SIZE, NotificationAttributeId.DATE,
            NotificationAttributeId.POSITIVE_ACTION_LABEL, NotificationAttributeId.NEGATIVE_ACTION_LABEL,
        )) {
            assertFailsWith<IllegalArgumentException> { AttributeRequest(id, 16) }
        }
        for (id in listOf(NotificationAttributeId.TITLE, NotificationAttributeId.SUBTITLE, NotificationAttributeId.MESSAGE)) {
            assertFailsWith<IllegalArgumentException> { AttributeRequest(id) }
            assertFailsWith<IllegalArgumentException> { AttributeRequest(id, 0) }
            assertFailsWith<IllegalArgumentException> { AttributeRequest(id, 0x10000) }
        }
    }

    @Test
    fun u04_invalidAppIdentifiersAreRejected() {
        assertFailsWith<IllegalArgumentException> { AncsCommands.getAppAttributes("", listOf(AppAttributeId.DISPLAY_NAME)) }
        assertFailsWith<IllegalArgumentException> { AncsCommands.getAppAttributes("a\u0000b", listOf(AppAttributeId.DISPLAY_NAME)) }
    }

    @Test
    fun u11_ancsAttErrorsAreTyped() {
        for (e in MiniJson.parse(repoFile("test/fixtures/ancs/vectors.json").readText()).obj()["attErrors"].list()) {
            val m = e.obj()
            assertEquals(m["expect"], AncsError.fromAttStatus((m["status"] as Long).toInt())?.name)
        }
        assertEquals(null, AncsError.fromAttStatus(0x05))
    }
}
