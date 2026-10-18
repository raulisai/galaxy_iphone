package dev.wristbridge.watch.bridge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.wristbridge.notification.SourceProfiles
import dev.wristbridge.protocol.ams.MediaState
import dev.wristbridge.protocol.ams.RemoteCommand
import dev.wristbridge.protocol.ancs.CategoryId
import dev.wristbridge.watch.R
import dev.wristbridge.watch.WristBridgeApp
import dev.wristbridge.watch.ui.MainActivity

/**
 * Ongoing, silent "now playing" notification for the iPhone's media app (AMS) with transport buttons.
 * Shown only while the user keeps it enabled, AMS is READY and a player is registered; cancelled
 * otherwise. AMS sends many small updates (elapsed time, volume), so it posts only when what the
 * notification shows changes. Never logs track titles, artists, albums or player names.
 */
class MediaPresenter(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val badges = SourceBadgeRenderer()
    private val prefs = context.getSharedPreferences(WristBridgeApp.PREFS, Context.MODE_PRIVATE)

    /** What the notification shows (or last tried to show); null once cancelled. */
    private var last: Visible? = null

    /** A previous process may have left the notification behind, so the first [clear] always cancels. */
    private var maybeStale = true

    private data class Visible(
        val title: String,
        val text: String,
        val player: String,
        val playing: Boolean,
        val controls: List<RemoteCommand>,
        val hidden: Boolean,
    )

    /** Called on the BLE thread after each AMS change. */
    @Synchronized
    fun render(state: MediaUiState) {
        val media = state.media
        val enabled = prefs.getBoolean(WristBridgeApp.PREF_MEDIA_NOTIFICATION, true)
        // An idle player (nothing loaded, not playing) only offers volume: no ongoing notice for it.
        val active = media.hasPlayer && (media.isPlaying || media.title.isNotBlank())
        if (!enabled || state.ams != AmsState.READY || !active) {
            clear()
            return
        }
        val hidden = prefs.getBoolean(WristBridgeApp.PREF_HIDE_PREVIEW, false)
        val status = context.getString(if (media.isPlaying) R.string.media_playing else R.string.media_paused)
        val player = media.playerName.trim().ifEmpty { context.getString(R.string.channel_media) }
        val visible = Visible(
            title = if (hidden) player else media.title.trim().ifEmpty { player },
            text = if (hidden) status else line(media.artist.trim(), media.album.trim()).ifEmpty { status },
            player = player,
            playing = media.isPlaying,
            controls = controls(media),
            hidden = hidden,
        )
        if (visible == last) return
        val previous = last
        val first = previous == null
        // Cached before posting: a denied post is retried on the next visible change, not on every update.
        last = visible
        maybeStale = false
        try {
            manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, build(visible))
            if (first || visible.controls != previous?.controls) {
                BridgeStatus.event("Aviso de música ${if (first) "mostrado" else "actualizado"} controles=${visible.controls.size}")
            }
        } catch (e: SecurityException) {
            BridgeStatus.event("Permiso de notificaciones denegado: aviso de música no mostrado")
            Log.w(TAG, "media notify denied", e)
        }
    }

    /** Cancels the notification and resets the dedupe cache. Idempotent. */
    @Synchronized
    fun clear() {
        if (last == null && !maybeStale) return
        manager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
        if (last != null) BridgeStatus.event("Aviso de música retirado")
        last = null
        maybeStale = false
    }

    private fun build(v: Visible): Notification {
        val profile = SourceProfiles.resolve(null, v.player, CategoryId.ENTERTAINMENT)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, WristBridgeApp.CHANNEL_MEDIA)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(v.title)
            .setContentText(v.text)
            .setSubText(v.player)
            .setLargeIcon(badges.icon(profile))
            .setColor(profile.accentArgb)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Own group: a silent post without one would be bundled with the service notice.
            .setGroup(NOTIFICATION_TAG)
            .setContentIntent(open)
            .apply { v.controls.forEach { addAction(action(it, v.playing)) } }
            .build()
    }

    private fun action(command: RemoteCommand, playing: Boolean): NotificationCompat.Action {
        val play = android.R.drawable.ic_media_play to R.string.media_play
        val pause = android.R.drawable.ic_media_pause to R.string.media_pause
        val (icon, label) = when (command) {
            RemoteCommand.PREVIOUS_TRACK -> android.R.drawable.ic_media_previous to R.string.media_previous
            RemoteCommand.NEXT_TRACK -> android.R.drawable.ic_media_next to R.string.media_next
            RemoteCommand.PLAY -> play
            RemoteCommand.PAUSE -> pause
            else -> if (playing) pause else play // TOGGLE_PLAY_PAUSE: shows what a tap does now.
        }
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_BASE + command.raw, CommandReceiver.wrap(context, BridgeService.mediaIntent(context, command)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(icon, context.getString(label), pending).build()
    }

    /** Previous, play/pause, next, limited to what the player supports (none reported = basic transport). */
    private fun controls(media: MediaState): List<RemoteCommand> {
        val supported = media.supportedCommands.ifEmpty { BASIC_TRANSPORT }
        val playPause = when {
            RemoteCommand.TOGGLE_PLAY_PAUSE in supported -> RemoteCommand.TOGGLE_PLAY_PAUSE
            media.isPlaying -> RemoteCommand.PAUSE.takeIf { it in supported }
            else -> RemoteCommand.PLAY.takeIf { it in supported }
        }
        return listOfNotNull(
            RemoteCommand.PREVIOUS_TRACK.takeIf { it in supported },
            playPause,
            RemoteCommand.NEXT_TRACK.takeIf { it in supported },
        )
    }

    /** Artist, plus " · album" only when both fit on one short watch line. */
    private fun line(artist: String, album: String): String = when {
        artist.isEmpty() -> album
        album.isEmpty() || artist.length + album.length + 3 > LINE_ROOM -> artist
        else -> "$artist · $album"
    }

    companion object {
        const val NOTIFICATION_TAG = "wb-media"
        const val NOTIFICATION_ID = 2
        private const val LINE_ROOM = 30

        /** Constant request-code base; one code per RemoteCommand so transport PendingIntents never collide. */
        private const val REQUEST_BASE = 0x4D00
        private val BASIC_TRANSPORT = setOf(
            RemoteCommand.PLAY, RemoteCommand.PAUSE, RemoteCommand.TOGGLE_PLAY_PAUSE,
            RemoteCommand.NEXT_TRACK, RemoteCommand.PREVIOUS_TRACK,
        )
    }
}
