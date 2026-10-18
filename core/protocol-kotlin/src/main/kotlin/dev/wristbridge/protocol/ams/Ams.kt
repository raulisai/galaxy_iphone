package dev.wristbridge.protocol.ams

import java.util.UUID

/** Official Apple Media Service identifiers (Apple AMS specification). Not project UUIDs. */
object AmsUuids {
    val SERVICE: UUID = UUID.fromString("89d3502b-0f36-433a-8ef4-c502ad55f8dc")
    val REMOTE_COMMAND: UUID = UUID.fromString("9b3c81d8-57b1-4a8a-b8df-0e56f7ca51c2")
    val ENTITY_UPDATE: UUID = UUID.fromString("2f7cabce-808d-411f-9a0c-bb92ba96c102")
    val ENTITY_ATTRIBUTE: UUID = UUID.fromString("c6b2f38c-23ab-46d8-a6ab-a3a870bbd5d7")
}

enum class AmsEntity(val raw: Int) {
    PLAYER(0), QUEUE(1), TRACK(2);

    companion object {
        fun of(raw: Int): AmsEntity? = entries.firstOrNull { it.raw == raw }
    }
}

object PlayerAttribute {
    const val NAME = 0
    const val PLAYBACK_INFO = 1
    const val VOLUME = 2
}

object QueueAttribute {
    const val INDEX = 0
    const val COUNT = 1
    const val SHUFFLE_MODE = 2
    const val REPEAT_MODE = 3
}

object TrackAttribute {
    const val ARTIST = 0
    const val ALBUM = 1
    const val TITLE = 2
    const val DURATION = 3
}

enum class RemoteCommand(val raw: Int) {
    PLAY(0), PAUSE(1), TOGGLE_PLAY_PAUSE(2), NEXT_TRACK(3), PREVIOUS_TRACK(4), VOLUME_UP(5), VOLUME_DOWN(6),
    ADVANCE_REPEAT_MODE(7), ADVANCE_SHUFFLE_MODE(8), SKIP_FORWARD(9), SKIP_BACKWARD(10),
    LIKE_TRACK(11), DISLIKE_TRACK(12), BOOKMARK_TRACK(13);

    companion object {
        fun of(raw: Int): RemoteCommand? = entries.firstOrNull { it.raw == raw }
    }
}

enum class PlaybackState { PAUSED, PLAYING, REWINDING, FAST_FORWARDING, UNKNOWN }

/** One Entity Update notification: EntityID, AttributeID, EntityUpdateFlags, UTF-8 value. */
data class EntityUpdate(val entity: Int, val attribute: Int, val truncated: Boolean, val value: String)

/** AMS ATT error codes on Remote Command / Entity Update / Entity Attribute writes. */
enum class AmsError(val attStatus: Int) {
    INVALID_STATE(0xA0), INVALID_COMMAND(0xA1), ABSENT_ATTRIBUTE(0xA2);

    companion object {
        fun fromAttStatus(status: Int): AmsError? = entries.firstOrNull { it.attStatus == status }
    }
}

object AmsCodec {
    private const val FLAG_TRUNCATED = 1

    /** Registration written to Entity Update: one entity per write, followed by its attribute IDs. */
    fun register(entity: AmsEntity, attributes: List<Int>): ByteArray {
        require(attributes.isNotEmpty()) { "at least one attribute" }
        require(attributes.all { it in 0..0xFF }) { "attribute out of range" }
        return byteArrayOf(entity.raw.toByte()) + attributes.map { it.toByte() }.toByteArray()
    }

    val registerPlayer: ByteArray
        get() = register(AmsEntity.PLAYER, listOf(PlayerAttribute.NAME, PlayerAttribute.PLAYBACK_INFO, PlayerAttribute.VOLUME))
    val registerQueue: ByteArray
        get() = register(
            AmsEntity.QUEUE,
            listOf(QueueAttribute.INDEX, QueueAttribute.COUNT, QueueAttribute.SHUFFLE_MODE, QueueAttribute.REPEAT_MODE),
        )
    val registerTrack: ByteArray
        get() = register(AmsEntity.TRACK, listOf(TrackAttribute.ARTIST, TrackAttribute.ALBUM, TrackAttribute.TITLE, TrackAttribute.DURATION))

    fun remoteCommand(command: RemoteCommand): ByteArray = byteArrayOf(command.raw.toByte())

    /** Written to Entity Attribute before reading it, to fetch the full value of a truncated attribute. */
    fun entityAttributeRequest(entity: AmsEntity, attribute: Int): ByteArray =
        byteArrayOf(entity.raw.toByte(), attribute.toByte())

    /** Returns null for packets shorter than the 3-byte header (never reads past the end). */
    fun parseEntityUpdate(bytes: ByteArray): EntityUpdate? {
        if (bytes.size < 3) return null
        val value = bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
        return EntityUpdate(
            bytes[0].toInt() and 0xFF, bytes[1].toInt() and 0xFF,
            (bytes[2].toInt() and FLAG_TRUNCATED) != 0, value,
        )
    }

    /** Remote Command notifications list the currently supported command IDs; unknown IDs are ignored. */
    fun parseSupportedCommands(bytes: ByteArray): Set<RemoteCommand> =
        bytes.asList().mapNotNullTo(LinkedHashSet()) { RemoteCommand.of(it.toInt() and 0xFF) }
}
