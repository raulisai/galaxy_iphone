package dev.wristbridge.protocol.ancs

/** Unsigned 32-bit ANCS NotificationUID kept in a Long so the sign bit never collides (U03). */
@JvmInline
value class AncsUid(val value: Long) {
    init {
        require(value in 0..MAX) { "UID out of uint32 range: $value" }
    }

    override fun toString(): String = value.toString()

    companion object {
        const val MAX: Long = 0xFFFF_FFFFL
    }
}

enum class EventId(val raw: Int) {
    ADDED(0), MODIFIED(1), REMOVED(2);

    companion object {
        fun of(raw: Int): EventId? = entries.firstOrNull { it.raw == raw }
    }
}

enum class EventFlag(val mask: Int) {
    SILENT(1 shl 0),
    IMPORTANT(1 shl 1),
    PRE_EXISTING(1 shl 2),
    POSITIVE_ACTION(1 shl 3),
    NEGATIVE_ACTION(1 shl 4);

    companion object {
        fun decode(raw: Int): Set<EventFlag> = entries.filterTo(LinkedHashSet()) { raw and it.mask != 0 }
    }
}

enum class CategoryId(val raw: Int) {
    OTHER(0), INCOMING_CALL(1), MISSED_CALL(2), VOICEMAIL(3), SOCIAL(4), SCHEDULE(5), EMAIL(6),
    NEWS(7), HEALTH_AND_FITNESS(8), BUSINESS_AND_FINANCE(9), LOCATION(10), ENTERTAINMENT(11),

    /** Reserved/future value; the raw byte is kept in [NotificationSourceEvent.rawCategory]. */
    UNKNOWN(-1);

    companion object {
        fun of(raw: Int): CategoryId = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

enum class NotificationAttributeId(val raw: Int, val takesMaxLength: Boolean) {
    APP_IDENTIFIER(0, false),
    TITLE(1, true),
    SUBTITLE(2, true),
    MESSAGE(3, true),
    MESSAGE_SIZE(4, false),
    DATE(5, false),
    POSITIVE_ACTION_LABEL(6, false),
    NEGATIVE_ACTION_LABEL(7, false);

    companion object {
        fun of(raw: Int): NotificationAttributeId? = entries.firstOrNull { it.raw == raw }
    }
}

enum class AppAttributeId(val raw: Int) {
    DISPLAY_NAME(0);
}

enum class ActionId(val raw: Int) {
    POSITIVE(0), NEGATIVE(1);
}

enum class CommandId(val raw: Int) {
    GET_NOTIFICATION_ATTRIBUTES(0), GET_APP_ATTRIBUTES(1), PERFORM_NOTIFICATION_ACTION(2);
}

/** ANCS-specific ATT error codes returned on Control Point writes (U11). */
enum class AncsError(val attStatus: Int) {
    UNKNOWN_COMMAND(0xA0),
    INVALID_COMMAND(0xA1),
    INVALID_PARAMETER(0xA2),
    ACTION_FAILED(0xA3);

    companion object {
        fun fromAttStatus(status: Int): AncsError? = entries.firstOrNull { it.attStatus == status }
    }
}
