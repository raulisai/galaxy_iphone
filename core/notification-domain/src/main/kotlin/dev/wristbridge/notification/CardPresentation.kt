package dev.wristbridge.notification

import dev.wristbridge.protocol.ancs.CategoryId

enum class CallKind { NONE, INCOMING, MISSED, VOICEMAIL }

/** Pure presentation policy: no commands, remote intents or changes to session/card identity. */
data class CardPresentation(
    val source: SourceProfile,
    val title: String,
    val body: String,
    val callKind: CallKind,
) {
    companion object {
        fun from(
            card: NotificationCard,
            hidePreview: Boolean,
            hiddenText: String = "Contenido oculto",
            unavailableText: String = "Detalles no disponibles",
        ): CardPresentation {
            val source = SourceProfiles.resolve(card.appIdentifier, card.appName, card.category)
            val kind = when (card.category) {
                CategoryId.INCOMING_CALL -> CallKind.INCOMING
                CategoryId.MISSED_CALL -> CallKind.MISSED
                CategoryId.VOICEMAIL -> CallKind.VOICEMAIL
                else -> CallKind.NONE
            }
            val title = when (kind) {
                CallKind.INCOMING -> "Llamada entrante"
                CallKind.MISSED -> "Llamada perdida"
                CallKind.VOICEMAIL -> "Buzón de voz"
                CallKind.NONE -> if (hidePreview) source.label else card.title.ifBlank { source.label }
            }
            val body = when {
                hidePreview -> hiddenText
                card.detailsUnavailable -> unavailableText
                else -> {
                    val lines = if (kind == CallKind.NONE) listOf(card.subtitle, card.message)
                        else listOf(card.title, card.subtitle, card.message)
                    lines.filter(String::isNotBlank).distinct().joinToString("\n") +
                        if (card.truncated) "…" else ""
                }
            }
            return CardPresentation(source, title, body, kind)
        }
    }
}
