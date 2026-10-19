package dev.wristbridge.watch.ui

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import dev.wristbridge.notification.CardPresentation
import dev.wristbridge.protocol.ams.MediaState
import dev.wristbridge.protocol.ams.PlaybackState
import dev.wristbridge.protocol.ams.RemoteCommand
import dev.wristbridge.watch.bridge.ActiveCard
import dev.wristbridge.watch.bridge.BridgeUiState
import dev.wristbridge.watch.bridge.MediaUiState
import kotlinx.coroutines.delay

/** A visible card plus its presentation (already resolved against the hide-preview preference). */
internal data class PresentedCard(val active: ActiveCard, val presentation: CardPresentation)

/** Everything the screens render, derived from the three process-wide StateFlows on every emission. */
internal data class UiModel(
    val bridge: BridgeUiState,
    val music: MediaUiState,
    /** Non-call-in-progress cards, newest first. */
    val alerts: List<PresentedCard>,
    /** Newest ringing call, if any. */
    val call: PresentedCard?,
    val hidePreview: Boolean,
    val actionsEnabled: Boolean,
    val headline: Headline,
    val bluetoothGranted: Boolean,
    val permissionsMissing: Boolean,
)

/**
 * Boolean preference mirrored in Compose state; every change is persisted immediately and external
 * changes (for example from the service) are picked up through a preference listener.
 */
@Stable
internal class BoolPref(private val prefs: SharedPreferences, private val key: String, private val default: Boolean) {
    var value by mutableStateOf(prefs.getBoolean(key, default))
        private set

    fun set(newValue: Boolean) {
        value = newValue
        prefs.edit { putBoolean(key, newValue) }
    }

    fun refresh() {
        value = prefs.getBoolean(key, default)
    }
}

@Composable
internal fun rememberBoolPref(prefs: SharedPreferences, key: String, default: Boolean): BoolPref {
    val pref = remember(prefs, key) { BoolPref(prefs, key, default) }
    DisposableEffect(pref, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed -> if (changed == key) pref.refresh() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return pref
}

// ---------------------------------------------------------------------------------------------
// Music helpers (AMS)
// ---------------------------------------------------------------------------------------------

private val TRANSPORT = setOf(
    RemoteCommand.PLAY, RemoteCommand.PAUSE, RemoteCommand.TOGGLE_PLAY_PAUSE,
    RemoteCommand.NEXT_TRACK, RemoteCommand.PREVIOUS_TRACK,
)

/** An empty supported set (not reported yet) assumes basic transport only; volume stays hidden. */
internal fun canSend(media: MediaState, command: RemoteCommand): Boolean =
    if (media.supportedCommands.isEmpty()) command in TRANSPORT else media.supports(command)

internal fun showVolume(media: MediaState): Boolean =
    media.supports(RemoteCommand.VOLUME_UP) || media.supports(RemoteCommand.VOLUME_DOWN)

/**
 * Explicit PLAY/PAUSE matching the shown icon when available, otherwise TOGGLE_PLAY_PAUSE. [playing] is the
 * state the button shows, which may run ahead of the iPhone right after a tap.
 */
internal fun playPauseCommand(media: MediaState, playing: Boolean = media.isPlaying): RemoteCommand? =
    listOf(if (playing) RemoteCommand.PAUSE else RemoteCommand.PLAY, RemoteCommand.TOGGLE_PLAY_PAUSE)
        .firstOrNull { canSend(media, it) }

/**
 * No app owns now-playing (Spotify paused for a while), yet the iPhone still advertises PLAY: it resumes the
 * last player. Without this the screen hid every control and play could not be pressed at all.
 */
internal fun resumeCommand(media: MediaState): RemoteCommand? =
    if (media.hasPlayer) null
    else listOf(RemoteCommand.PLAY, RemoteCommand.TOGGLE_PLAY_PAUSE).firstOrNull { media.supports(it) }

/** Elapsed time extrapolated from the AMS anchor; ticks once per second only while playback moves. */
@Composable
internal fun rememberElapsedSeconds(media: MediaState): Double? {
    val moving = media.playback == PlaybackState.PLAYING ||
        media.playback == PlaybackState.REWINDING ||
        media.playback == PlaybackState.FAST_FORWARDING
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(moving, media.elapsedAtNanos) {
        now = SystemClock.elapsedRealtimeNanos()
        if (moving) {
            while (true) {
                delay(1_000)
                now = SystemClock.elapsedRealtimeNanos()
            }
        }
    }
    return media.elapsedAt(maxOf(now, media.elapsedAtNanos))
}
