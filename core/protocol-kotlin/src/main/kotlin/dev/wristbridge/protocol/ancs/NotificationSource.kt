package dev.wristbridge.protocol.ancs

data class NotificationSourceEvent(
    val event: EventId,
    val flags: Set<EventFlag>,
    val rawFlags: Int,
    val category: CategoryId,
    val rawCategory: Int,
    val categoryCount: Int,
    val uid: AncsUid,
    /** Bytes beyond the 8 defined by the specification; ignored but counted. */
    val extraBytes: Int = 0,
) {
    val silent: Boolean get() = EventFlag.SILENT in flags
    val preExisting: Boolean get() = EventFlag.PRE_EXISTING in flags
    val important: Boolean get() = EventFlag.IMPORTANT in flags
}

sealed interface NotificationSourceParse {
    data class Valid(val event: NotificationSourceEvent) : NotificationSourceParse
    data class Invalid(val reason: String, val length: Int) : NotificationSourceParse
}

object NotificationSourceParser {
    const val LENGTH = 8

    /**
     * Notification Source packets are exactly 8 bytes. Shorter packets are rejected without reading
     * past the end (U02). Longer packets keep the first 8 bytes and report the excess.
     */
    fun parse(bytes: ByteArray): NotificationSourceParse {
        if (bytes.size < LENGTH) return NotificationSourceParse.Invalid("short", bytes.size)
        val eventRaw = bytes[0].u8()
        val event = EventId.of(eventRaw)
            ?: return NotificationSourceParse.Invalid("unknown-event-$eventRaw", bytes.size)
        val flagsRaw = bytes[1].u8()
        val categoryRaw = bytes[2].u8()
        return NotificationSourceParse.Valid(
            NotificationSourceEvent(
                event = event,
                flags = EventFlag.decode(flagsRaw),
                rawFlags = flagsRaw,
                category = CategoryId.of(categoryRaw),
                rawCategory = categoryRaw,
                categoryCount = bytes[3].u8(),
                uid = AncsUid(readUInt32Le(bytes, 4)),
                extraBytes = bytes.size - LENGTH,
            ),
        )
    }
}

internal fun Byte.u8(): Int = toInt() and 0xFF

internal fun readUInt32Le(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].u8().toLong()) or
        (bytes[offset + 1].u8().toLong() shl 8) or
        (bytes[offset + 2].u8().toLong() shl 16) or
        (bytes[offset + 3].u8().toLong() shl 24)
