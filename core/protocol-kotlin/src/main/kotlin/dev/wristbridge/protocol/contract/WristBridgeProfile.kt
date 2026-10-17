package dev.wristbridge.protocol.contract

import java.util.UUID

/**
 * Constants derived from contracts/ble-profile.json. ContractConsistencyTest fails if they diverge.
 * Reserved characteristics are listed but not exposed in P1; their properties are not frozen.
 */
object WristBridgeProfile {
    const val CONTRACT_VERSION = "1.0"
    const val PROFILE_REVISION = "p1-lab-1"

    val SERVICE: UUID = UUID.fromString("6f06b8b1-21d9-4c65-9d7e-118880e36a01")
    val CAPABILITIES: UUID = UUID.fromString("6f06b8b1-21d9-4c65-9d7e-118880e36a02")
    val REQUESTS: UUID = UUID.fromString("6f06b8b1-21d9-4c65-9d7e-118880e36a03")
    val EVENTS: UUID = UUID.fromString("6f06b8b1-21d9-4c65-9d7e-118880e36a04")

    /** Apple proposal for AccessorySetupKit name filtering; pending ADR-001 confirmation. */
    const val ADVERTISED_NAME = "WristBridge"
}

/** Official ANCS identifiers (Apple ANCS specification). Not project UUIDs. */
object AncsUuids {
    val SERVICE: UUID = UUID.fromString("7905f431-b5ce-4e99-a40f-4b1e122d00d0")
    val NOTIFICATION_SOURCE: UUID = UUID.fromString("9fbf120d-6301-42d9-8c58-25e699a21dbd")
    val CONTROL_POINT: UUID = UUID.fromString("69d1d8f3-45e1-49a8-9821-9bbdfdaad9d9")
    val DATA_SOURCE: UUID = UUID.fromString("22eac6e9-24d6-4bb5-be44-b36ace7c7bfb")

    /** Client Characteristic Configuration Descriptor (Bluetooth SIG). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** Product limits from the plan (§6.4). Not official ANCS maximums. */
data class AncsLimits(
    val maxPendingUids: Int = 128,
    val maxResponseBytes: Int = 16 * 1024,
    val titleMaxBytes: Int = 256,
    val messageMaxBytes: Int = 1024,
)
