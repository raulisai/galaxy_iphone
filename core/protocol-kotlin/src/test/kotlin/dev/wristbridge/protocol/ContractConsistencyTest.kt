package dev.wristbridge.protocol

import dev.wristbridge.protocol.contract.AncsLimits
import dev.wristbridge.protocol.contract.AncsUuids
import dev.wristbridge.protocol.contract.WristBridgeProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class ContractConsistencyTest {
    private val profile = MiniJson.parse(repoFile("contracts/ble-profile.json").readText()).obj()

    @Test
    fun reservedUuidsMatchContract() {
        val own = profile["ownService"].obj()
        val chars = own["reservedCharacteristics"].obj()
        assertEquals(WristBridgeProfile.SERVICE.toString(), own["service"])
        assertEquals(WristBridgeProfile.CAPABILITIES.toString(), chars["capabilities"])
        assertEquals(WristBridgeProfile.REQUESTS.toString(), chars["requests"])
        assertEquals(WristBridgeProfile.EVENTS.toString(), chars["events"])
        assertEquals(WristBridgeProfile.CONTRACT_VERSION, profile["contractVersion"])
        assertEquals(WristBridgeProfile.PROFILE_REVISION, profile["profileRevision"])
    }

    @Test
    fun reservedUuidsMatchOriginalSharedDocument() {
        // Doc/01 is the source of truth; the JSON registry must not drift from it.
        val doc = repoFile("Doc/01_CONTRATO_COMPARTIDO.md").readText()
        listOf(WristBridgeProfile.SERVICE, WristBridgeProfile.CAPABILITIES, WristBridgeProfile.REQUESTS, WristBridgeProfile.EVENTS)
            .forEach { assert(doc.contains("`$it`")) { "$it missing from Doc/01" } }
    }

    @Test
    fun advertisingAndAncsMatchContract() {
        val adv = profile["advertising"].obj()
        assertEquals(WristBridgeProfile.SERVICE.toString(), adv["serviceUuidInAdvertisement"])
        assertEquals(WristBridgeProfile.ADVERTISED_NAME, adv["localName"])
        val ancs = profile["ancs"].obj()
        assertEquals(AncsUuids.SERVICE.toString(), ancs["service"])
        assertEquals(AncsUuids.NOTIFICATION_SOURCE.toString(), ancs["notificationSource"])
        assertEquals(AncsUuids.CONTROL_POINT.toString(), ancs["controlPoint"])
        assertEquals(AncsUuids.DATA_SOURCE.toString(), ancs["dataSource"])
    }

    @Test
    fun productLimitsMatchContract() {
        val l = profile["productLimits"].obj()
        val d = AncsLimits()
        assertEquals(d.maxPendingUids.toLong(), l["maxPendingUids"])
        assertEquals(d.maxResponseBytes.toLong(), l["maxAncsResponseBytes"])
        assertEquals(d.titleMaxBytes.toLong(), l["titleMaxBytes"])
        assertEquals(d.messageMaxBytes.toLong(), l["messageMaxBytes"])
    }
}
