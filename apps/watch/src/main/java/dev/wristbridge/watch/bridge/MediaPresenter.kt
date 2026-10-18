package dev.wristbridge.watch.bridge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.wristbridge.notification.SourceProfiles
import dev.wristbridge.protocol.ams.MediaState
import dev.wristbridge.protocol.ams.RemoteCommand
import dev.wristbridge.protocol.ancs.CategoryId
import dev.wristbridge.watch.R
import dev.wristbridge.watch.WristBridgeApp
import dev.wristbridge.watch.ui.MainActivity
import dev.wristbridge.protocol.ams.PlaybackState as AmsPlayback

/**
 * "Now playing" for the iPhone's media app (AMS) as a real framework [MediaSession] plus an ongoing,
 * silent MediaStyle notification, so Wear OS shows its native media controls (track skip, pause)
 * instead of loose buttons. Active only while the user keeps it enabled, AMS is READY and a player is
 * registered; deactivated and cancelled otherwise. AMS sends many small updates (elapsed time, volume):
 * the notification is re-posted only when what it shows changes, while the session's playback state,
 * which is cheap, follows every play-state or elapsed-anchor change. Never logs track titles, artists,
 * albums or player names.
 */
class MediaPresenter(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val badges = SourceBadgeRenderer()
    private val prefs = context.getSharedPreferences(WristBridgeApp.PREFS, Context.MODE_PRIVATE)

    /** What the notification shows (or last tried to show); null once cancelled. */
    private var last: Visible? = null

    /** A previous process may have left the notification behind, so the first [clear] always cancels. */
    private var maybeStale = true

    private var session: MediaSession? = null

    /** Commands the iPhone currently accepts and play state; read by session callbacks on the main thread. */
    @Volatile
    private var commands: Set<RemoteCommand> = emptySet()

    @Volatile
    private var playingNow = false

    /** Last pushed playback state, to skip identical updates. */
    private var lastPlayback: List<Any?>? = null

    private data class Visible(
        val title: String,
        val text: String,
        val album: String,
        val durationMs: Long,
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
            album = if (hidden) "" else media.album.trim(),
            durationMs = if (hidden) 0L else (media.durationSeconds?.times(1000)?.toLong() ?: 0L),
            player = player,
            playing = media.isPlaying,
            controls = controls(media),
            hidden = hidden,
        )
        commands = media.supportedCommands.ifEmpty { BASIC_TRANSPORT }
        playingNow = media.isPlaying
        val previous = last
        val first = previous == null
        val changed = visible != previous
        val mediaSession = try {
            session ?: createSession().also { session = it }
        } catch (e: RuntimeException) {
            Log.w(TAG, "media session unavailable", e)
            null
        }
        if (mediaSession != null) {
            try {
                if (changed) mediaSession.setMetadata(metadata(visible))
                updatePlayback(mediaSession, media, hidden)
                if (!mediaSession.isActive) mediaSession.isActive = true
            } catch (e: RuntimeException) {
                Log.w(TAG, "media session update failed", e)
            }
        }
        if (!changed) return
        // Cached before posting: a denied post is retried on the next visible change, not on every update.
        last = visible
        maybeStale = false
        try {
            manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, build(visible, mediaSession))
            if (first || visible.controls != previous?.controls) {
                BridgeStatus.event("Aviso de música ${if (first) "mostrado" else "actualizado"} controles=${visible.controls.size}")
            }
        } catch (e: SecurityException) {
            BridgeStatus.event("Permiso de notificaciones denegado: aviso de música no mostrado")
            Log.w(TAG, "media notify denied", e)
        }
    }

    /** Cancels the notification, releases the session and resets the dedupe cache. Idempotent. */
    @Synchronized
    fun clear() {
        releaseSession()
        if (last == null && !maybeStale) return
        manager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
        if (last != null) BridgeStatus.event("Aviso de música retirado")
        last = null
        maybeStale = false
    }

    private fun releaseSession() {
        val s = session ?: return
        session = null
        lastPlayback = null
        try {
            s.isActive = false
            s.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "media session release failed", e)
        }
    }

    private fun createSession(): MediaSession {
        val s = MediaSession(context, SESSION_TAG)
        // Callbacks run on the main looper; sending a command only starts the foreground service.
        s.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = send(RemoteCommand.PLAY, toggleWhenPlaying = false)
                override fun onPause() = send(RemoteCommand.PAUSE, toggleWhenPlaying = true)
                override fun onSkipToNext() = send(RemoteCommand.NEXT_TRACK)
                override fun onSkipToPrevious() = send(RemoteCommand.PREVIOUS_TRACK)
                override fun onFastForward() = send(RemoteCommand.SKIP_FORWARD)
                override fun onRewind() = send(RemoteCommand.SKIP_BACKWARD)
            },
            Handler(Looper.getMainLooper()),
        )
        return s
    }

    /**
     * Sends [primary] when the player supports it. For play/pause ([toggleWhenPlaying] set) a player that
     * only offers the toggle gets TOGGLE_PLAY_PAUSE, but only if that would really change the state.
     * Unsupported commands are dropped.
     */
    private fun send(primary: RemoteCommand, toggleWhenPlaying: Boolean? = null) {
        val supported = commands
        val command = when {
            primary in supported -> primary
            toggleWhenPlaying != null && RemoteCommand.TOGGLE_PLAY_PAUSE in supported &&
                toggleWhenPlaying == playingNow -> RemoteCommand.TOGGLE_PLAY_PAUSE
            else -> return
        }
        BridgeService.sendMedia(context, command)
    }

    private fun metadata(v: Visible): MediaMetadata {
        val profile = SourceProfiles.resolve(null, v.player, CategoryId.ENTERTAINMENT)
        return MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, v.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, v.text)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, v.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, v.durationMs)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, badges.icon(profile))
            .build()
    }

    private fun updatePlayback(session: MediaSession, media: MediaState, hidden: Boolean) {
        val supported = commands
        val toggle = RemoteCommand.TOGGLE_PLAY_PAUSE in supported
        var actions = 0L
        if (toggle || RemoteCommand.PLAY in supported) actions = actions or PlaybackState.ACTION_PLAY
        if (toggle || RemoteCommand.PAUSE in supported) actions = actions or PlaybackState.ACTION_PAUSE
        if (toggle) actions = actions or PlaybackState.ACTION_PLAY_PAUSE
        if (RemoteCommand.NEXT_TRACK in supported) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (RemoteCommand.PREVIOUS_TRACK in supported) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (RemoteCommand.SKIP_FORWARD in supported) actions = actions or PlaybackState.ACTION_FAST_FORWARD
        if (RemoteCommand.SKIP_BACKWARD in supported) actions = actions or PlaybackState.ACTION_REWIND
        // ACTION_SEEK_TO is never offered: AMS cannot seek.
        val state = when (media.playback) {
            AmsPlayback.PLAYING -> PlaybackState.STATE_PLAYING
            AmsPlayback.PAUSED -> PlaybackState.STATE_PAUSED
            AmsPlayback.REWINDING -> PlaybackState.STATE_REWINDING
            AmsPlayback.FAST_FORWARDING -> PlaybackState.STATE_FAST_FORWARDING
            AmsPlayback.UNKNOWN -> PlaybackState.STATE_NONE
        }
        val moving = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_REWINDING ||
            state == PlaybackState.STATE_FAST_FORWARDING
        val speed = if (moving) media.rate.toFloat() else 0f
        // The key leaves out the extrapolated position: the system extrapolates from position + speed itself.
        val key = listOf(state, speed, media.elapsedSeconds, media.elapsedAtNanos, actions, hidden)
        if (key == lastPlayback) return
        lastPlayback = key
        val positionMs = if (hidden) {
            PlaybackState.PLAYBACK_POSITION_UNKNOWN
        } else {
            media.elapsedAt(SystemClock.elapsedRealtimeNanos())?.times(1000)?.toLong()
                ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, positionMs, speed, SystemClock.elapsedRealtime())
                .setActions(actions)
                .build(),
        )
    }

    private fun build(v: Visible, mediaSession: MediaSession?): Notification {
        val profile = SourceProfiles.resolve(null, v.player, CategoryId.ENTERTAINMENT)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, WristBridgeApp.CHANNEL_MEDIA)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(v.title)
            .setContentText(v.text)
            .setSubText(v.player)
            .setLargeIcon(badges.icon(profile))
            .setColor(profile.accentArgb)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Silence itself comes from the media channel's importance.
            .setShowWhen(false)
            // Own group: a silent post without one would be bundled with the service notice.
            .setGroup(NOTIFICATION_TAG)
            .setContentIntent(open)
        v.controls.forEach { builder.addAction(action(it, v.playing)) }
        val style = Notification.MediaStyle()
        mediaSession?.let { style.setMediaSession(it.sessionToken) }
        if (v.controls.isNotEmpty()) {
            style.setShowActionsInCompactView(*IntArray(minOf(v.controls.size, 3)) { it })
        }
        return builder.setStyle(style).build()
    }

    private fun action(command: RemoteCommand, playing: Boolean): Notification.Action {
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
        return Notification.Action.Builder(icon, context.getString(label), pending).build()
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
        private const val SESSION_TAG = "WristBridge"

        /** Constant request-code base; one code per RemoteCommand so transport PendingIntents never collide. */
        private const val REQUEST_BASE = 0x4D00
        private val BASIC_TRANSPORT = setOf(
            RemoteCommand.PLAY, RemoteCommand.PAUSE, RemoteCommand.TOGGLE_PLAY_PAUSE,
            RemoteCommand.NEXT_TRACK, RemoteCommand.PREVIOUS_TRACK,
        )
    }
}
