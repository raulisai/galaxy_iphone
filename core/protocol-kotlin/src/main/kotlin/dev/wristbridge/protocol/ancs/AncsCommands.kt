package dev.wristbridge.protocol.ancs

import dev.wristbridge.protocol.contract.AncsLimits
import java.io.ByteArrayOutputStream

/** One requested notification attribute. Only Title/Subtitle/Message carry a max length (U04). */
data class AttributeRequest(val id: NotificationAttributeId, val maxLength: Int? = null) {
    init {
        if (id.takesMaxLength) {
            require(maxLength != null && maxLength in 1..0xFFFF) { "$id requires max length 1..65535" }
        } else {
            require(maxLength == null) { "$id must not carry a max length" }
        }
    }
}

object AncsCommands {
    fun defaultNotificationRequest(limits: AncsLimits = AncsLimits()): List<AttributeRequest> = listOf(
        AttributeRequest(NotificationAttributeId.APP_IDENTIFIER),
        AttributeRequest(NotificationAttributeId.TITLE, limits.titleMaxBytes),
        AttributeRequest(NotificationAttributeId.SUBTITLE, limits.titleMaxBytes),
        AttributeRequest(NotificationAttributeId.MESSAGE, limits.messageMaxBytes),
        AttributeRequest(NotificationAttributeId.MESSAGE_SIZE),
        AttributeRequest(NotificationAttributeId.DATE),
        AttributeRequest(NotificationAttributeId.POSITIVE_ACTION_LABEL),
        AttributeRequest(NotificationAttributeId.NEGATIVE_ACTION_LABEL),
    )

    fun getNotificationAttributes(uid: AncsUid, requests: List<AttributeRequest>): ByteArray {
        require(requests.isNotEmpty()) { "at least one attribute" }
        require(requests.map { it.id }.toSet().size == requests.size) { "duplicate attribute" }
        val out = ByteArrayOutputStream()
        out.write(CommandId.GET_NOTIFICATION_ATTRIBUTES.raw)
        out.writeUInt32Le(uid.value)
        for (r in requests) {
            out.write(r.id.raw)
            r.maxLength?.let { out.writeUInt16Le(it) }
        }
        return out.toByteArray()
    }

    fun getAppAttributes(appIdentifier: String, attributes: List<AppAttributeId>): ByteArray {
        require(appIdentifier.isNotEmpty()) { "empty app identifier" }
        require(attributes.isNotEmpty()) { "at least one attribute" }
        val id = appIdentifier.toByteArray(Charsets.UTF_8)
        require(id.none { it == 0.toByte() }) { "app identifier contains NUL" }
        val out = ByteArrayOutputStream()
        out.write(CommandId.GET_APP_ATTRIBUTES.raw)
        out.write(id)
        out.write(0)
        attributes.forEach { out.write(it.raw) }
        return out.toByteArray()
    }

    fun performNotificationAction(uid: AncsUid, action: ActionId): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CommandId.PERFORM_NOTIFICATION_ACTION.raw)
        out.writeUInt32Le(uid.value)
        out.write(action.raw)
        return out.toByteArray()
    }
}

private fun ByteArrayOutputStream.writeUInt32Le(v: Long) {
    write((v and 0xFF).toInt())
    write(((v shr 8) and 0xFF).toInt())
    write(((v shr 16) and 0xFF).toInt())
    write(((v shr 24) and 0xFF).toInt())
}

private fun ByteArrayOutputStream.writeUInt16Le(v: Int) {
    write(v and 0xFF)
    write((v shr 8) and 0xFF)
}
