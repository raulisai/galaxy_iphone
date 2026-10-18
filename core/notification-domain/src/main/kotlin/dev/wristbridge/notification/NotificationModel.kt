package dev.wristbridge.notification

import dev.wristbridge.protocol.ancs.ActionId
import dev.wristbridge.protocol.ancs.AncsUid
import dev.wristbridge.protocol.ancs.CategoryId

/** Contract §3: identity of a forwarded notification. A UID is only meaningful inside its generation. */
data class NotificationKey(val sourceDeviceId: String, val sessionGeneration: Long, val uid: AncsUid) {
    /** Stable Android notification tag; never contains notification content. */
    val tag: String get() = "wb:$sessionGeneration:${uid.value}"
}

/** Synthetic campaign marker from the iOS fixture generator: title "<runId> · F001". */
data class FixtureTag(val runId: String, val fixtureId: String) {
    companion object {
        private val pattern = Regex("""^([A-Za-z0-9_-]{1,64}) · (F\d{3})$""")

        fun fromTitle(title: String?): FixtureTag? =
            title?.let { pattern.matchEntire(it) }?.let { FixtureTag(it.groupValues[1], it.groupValues[2]) }
    }
}

data class NotificationCard(
    val key: NotificationKey,
    val category: CategoryId,
    val appIdentifier: String?,
    val appName: String?,
    val title: String,
    val subtitle: String,
    val message: String,
    /** True when MessageSize reports more bytes than were received (body truncated by request). */
    val truncated: Boolean,
    val important: Boolean,
    val preExisting: Boolean,
    val silent: Boolean,
    /** Attributes could not be fetched (timeout, ANCS error or overflow); shown with a fallback. */
    val detailsUnavailable: Boolean,
    val malformedText: Boolean,
    val positiveActionLabel: String?,
    val negativeActionLabel: String?,
    val fixture: FixtureTag?,
) {
    /** Readable source label even for unknown apps or empty names (N08). */
    val sourceLabel: String
        get() = appName?.takeIf { it.isNotBlank() }
            ?: appIdentifier?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
            ?: categoryLabel(category)
}

fun categoryLabel(category: CategoryId): String = when (category) {
    CategoryId.INCOMING_CALL -> "Llamada entrante"
    CategoryId.MISSED_CALL -> "Llamada perdida"
    CategoryId.VOICEMAIL -> "Buzón de voz"
    CategoryId.SOCIAL -> "Social"
    CategoryId.SCHEDULE -> "Agenda"
    CategoryId.EMAIL -> "Correo"
    CategoryId.NEWS -> "Noticias"
    CategoryId.HEALTH_AND_FITNESS -> "Salud y actividad"
    CategoryId.BUSINESS_AND_FINANCE -> "Negocios y finanzas"
    CategoryId.LOCATION -> "Ubicación"
    CategoryId.ENTERTAINMENT -> "Entretenimiento"
    CategoryId.OTHER, CategoryId.UNKNOWN -> "iPhone"
}

sealed interface SessionEffect {
    /** Write to the ANCS Control Point; report back with onControlPointWritten(generation, transactionId). */
    data class WriteControlPoint(val generation: Long, val transactionId: Long, val bytes: ByteArray) : SessionEffect {
        override fun equals(other: Any?) = other is WriteControlPoint && other.generation == generation &&
            other.transactionId == transactionId && other.bytes.contentEquals(bytes)

        override fun hashCode() = (31 * generation.hashCode() + transactionId.hashCode()) * 31 + bytes.contentHashCode()
    }

    /** [alert] is false for silent, pre-existing (backlog) and Modified updates (no repeated haptics). */
    data class PostCard(val card: NotificationCard, val alert: Boolean) : SessionEffect
    data class UpdateCard(val card: NotificationCard) : SessionEffect
    data class RemoveCard(val key: NotificationKey) : SessionEffect

    /** Remove every card whose generation is older than [generation] (new session rebuilds backlog). */
    data class RemoveCardsBefore(val generation: Long) : SessionEffect

    /** Outcome of [AncsSessionEngine.performAction]; REQUESTED is not proof of the final effect. */
    data class ActionResult(val key: NotificationKey, val action: ActionId, val status: ActionStatus) : SessionEffect
}

enum class ActionStatus { REQUESTED, FAILED, UNAVAILABLE }

/** Counters for diagnostics and campaign evidence. No titles or bodies. */
data class SessionCounters(
    val nsEvents: Long = 0,
    val nsInvalid: Long = 0,
    val added: Long = 0,
    val modified: Long = 0,
    val removed: Long = 0,
    val preExisting: Long = 0,
    val fetchStarted: Long = 0,
    val fetchCompleted: Long = 0,
    val fetchTimedOut: Long = 0,
    val fetchFailed: Long = 0,
    val ancsErrors: Long = 0,
    val staleInputsIgnored: Long = 0,
    val unsolicitedData: Long = 0,
    val discardedResponses: Long = 0,
    val queueOverflow: Long = 0,
    val cardsPosted: Long = 0,
    val cardsUpdated: Long = 0,
    val cardsRemoved: Long = 0,
    val actionsRequested: Long = 0,
    val actionsFailed: Long = 0,
)
