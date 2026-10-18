package dev.wristbridge.protocol.ams

import java.util.Locale

/**
 * Now-playing state of the iPhone's active media app as reported by AMS. Pure and immutable: the BLE
 * adapter feeds [apply] with Entity Updates and renders the result. Elapsed time is anchored to a
 * monotonic timestamp so the UI can extrapolate it without polling the iPhone (Doc/02 §P3).
 */
data class MediaState(
    val playerName: String = "",
    val playback: PlaybackState = PlaybackState.UNKNOWN,
    val rate: Double = 0.0,
    val elapsedSeconds: Double? = null,
    val elapsedAtNanos: Long = 0,
    val volume: Double? = null,
    val artist: String = "",
    val album: String = "",
    val title: String = "",
    val durationSeconds: Double? = null,
    val queueIndex: Int? = null,
    val queueCount: Int? = null,
    val shuffleMode: Int? = null,
    val repeatMode: Int? = null,
    val supportedCommands: Set<RemoteCommand> = emptySet(),
    /** (entity, attribute) pairs whose last value arrived truncated. */
    val truncated: Set<Pair<Int, Int>> = emptySet(),
) {
    /** An app is registered as the now-playing player on the iPhone. */
    val hasPlayer: Boolean get() = playerName.isNotEmpty()
    val isPlaying: Boolean get() = playback == PlaybackState.PLAYING

    fun supports(command: RemoteCommand) = command in supportedCommands

    fun elapsedAt(nowNanos: Long): Double? {
        val base = elapsedSeconds ?: return null
        val moving = playback != PlaybackState.PAUSED && playback != PlaybackState.UNKNOWN
        val advanced = if (moving) base + rate * (nowNanos - elapsedAtNanos) / 1e9 else base
        val capped = durationSeconds?.let { advanced.coerceAtMost(it) } ?: advanced
        return capped.coerceAtLeast(0.0)
    }

    fun apply(update: EntityUpdate, nowNanos: Long): MediaState {
        val key = update.entity to update.attribute
        val next = when (AmsEntity.of(update.entity)) {
            AmsEntity.PLAYER -> when (update.attribute) {
                PlayerAttribute.NAME -> if (update.value.isEmpty()) {
                    // No app owns now-playing any more: drop stale track details.
                    MediaState(supportedCommands = supportedCommands, volume = volume)
                } else copy(playerName = update.value)
                PlayerAttribute.PLAYBACK_INFO -> withPlaybackInfo(update.value, nowNanos)
                PlayerAttribute.VOLUME -> copy(volume = number(update.value)?.coerceIn(0.0, 1.0) ?: volume)
                else -> this
            }
            AmsEntity.QUEUE -> when (update.attribute) {
                QueueAttribute.INDEX -> copy(queueIndex = update.value.trim().toIntOrNull())
                QueueAttribute.COUNT -> copy(queueCount = update.value.trim().toIntOrNull())
                QueueAttribute.SHUFFLE_MODE -> copy(shuffleMode = update.value.trim().toIntOrNull())
                QueueAttribute.REPEAT_MODE -> copy(repeatMode = update.value.trim().toIntOrNull())
                else -> this
            }
            AmsEntity.TRACK -> when (update.attribute) {
                TrackAttribute.ARTIST -> copy(artist = update.value)
                TrackAttribute.ALBUM -> copy(album = update.value)
                TrackAttribute.TITLE -> copy(title = update.value)
                TrackAttribute.DURATION -> copy(durationSeconds = number(update.value)?.takeIf { it >= 0 })
                else -> this
            }
            null -> this
        }
        return next.copy(truncated = if (update.truncated) next.truncated + key else next.truncated - key)
    }

    fun withSupportedCommands(commands: Set<RemoteCommand>) = copy(supportedCommands = commands)

    /** PlaybackInfo is "PlaybackState,PlaybackRate,ElapsedTime"; malformed fields keep previous values. */
    private fun withPlaybackInfo(value: String, nowNanos: Long): MediaState {
        val parts = value.split(',')
        val raw = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val state = when (raw) {
            0 -> PlaybackState.PAUSED
            1 -> PlaybackState.PLAYING
            2 -> PlaybackState.REWINDING
            3 -> PlaybackState.FAST_FORWARDING
            null -> playback
            else -> PlaybackState.UNKNOWN
        }
        return copy(
            playback = state,
            rate = parts.getOrNull(1)?.let(::number) ?: rate,
            elapsedSeconds = parts.getOrNull(2)?.let(::number) ?: elapsedSeconds,
            elapsedAtNanos = nowNanos,
        )
    }

    private fun number(s: String): Double? = s.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** "m:ss" or "h:mm:ss" for whole seconds; never locale-dependent. */
fun formatMediaTime(seconds: Double?): String {
    val total = seconds?.toLong()?.coerceAtLeast(0) ?: return "--:--"
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}
