package dev.wristbridge.watch.ui

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.touchTargetAwareSize
import dev.wristbridge.notification.ActionStatus
import dev.wristbridge.notification.CardPresentation
import dev.wristbridge.notification.NotificationCard
import dev.wristbridge.notification.SourceProfile
import dev.wristbridge.protocol.ams.MediaState
import dev.wristbridge.protocol.ams.PlaybackState
import dev.wristbridge.protocol.ams.RemoteCommand
import dev.wristbridge.protocol.ams.formatMediaTime
import dev.wristbridge.protocol.ancs.ActionId
import dev.wristbridge.watch.bridge.ActiveCard
import kotlinx.coroutines.delay

/** A visible card plus its presentation (already resolved against the hide-preview preference). */
internal data class PresentedCard(val active: ActiveCard, val presentation: CardPresentation)

// ---------------------------------------------------------------------------------------------
// Generic building blocks
// ---------------------------------------------------------------------------------------------

@Composable
internal fun AppTitle(text: String) = Text(
    text = text,
    modifier = Modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
    style = MaterialTheme.typography.labelMedium,
)

@Composable
internal fun HeadlineText(headline: Headline) {
    val color = when (headline.tone) {
        Tone.OK -> MaterialTheme.colorScheme.primary
        Tone.ERROR -> MaterialTheme.colorScheme.error
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = headline.text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        color = color,
        textAlign = TextAlign.Center,
        overflow = TextOverflow.Ellipsis,
        maxLines = 3,
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
internal fun SectionHeader(text: String) = ListHeader { Text(text = text) }

@Composable
internal fun InfoLine(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) = Text(
    text = text,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    color = color,
    textAlign = TextAlign.Center,
    style = MaterialTheme.typography.bodySmall,
)

/** Label above value, centered: reads well on a round screen without horizontal overflow. */
@Composable
internal fun StatusRow(label: String, value: String, detail: String = "") {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(text = value, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyExtraSmall,
            )
        }
    }
}

/** "Última recepción" refreshed once per second while the row is composed (visible). */
@Composable
internal fun LastReceptionRow(lastElapsedMs: Long?) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lastElapsedMs) {
        if (lastElapsedMs == null) return@LaunchedEffect
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    StatusRow(label = "Última recepción", value = lastReceptionLabel(lastElapsedMs, now))
}

@Composable
internal fun PrimaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) = Button(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    enabled = enabled,
    label = { CenteredLabel(label) },
)

@Composable
internal fun SecondaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) = FilledTonalButton(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    enabled = enabled,
    label = { CenteredLabel(label) },
)

@Composable
private fun CenteredLabel(text: String) = Text(
    text = text,
    modifier = Modifier.fillMaxWidth(),
    textAlign = TextAlign.Center,
    overflow = TextOverflow.Ellipsis,
    maxLines = 2,
)

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

/** Boolean preference mirrored in Compose state; every change is persisted immediately. */
@Stable
internal class BoolPref(private val prefs: SharedPreferences, private val key: String, default: Boolean) {
    var value by mutableStateOf(prefs.getBoolean(key, default))
        private set

    fun set(newValue: Boolean) {
        value = newValue
        prefs.edit { putBoolean(key, newValue) }
    }
}

@Composable
internal fun rememberBoolPref(prefs: SharedPreferences, key: String, default: Boolean): BoolPref =
    remember(prefs, key) { BoolPref(prefs, key, default) }

@Composable
internal fun PrefSwitch(label: String, pref: BoolPref, secondary: String? = null) {
    SwitchButton(
        checked = pref.value,
        onCheckedChange = { pref.set(it) },
        modifier = Modifier.fillMaxWidth(),
        secondaryLabel = if (secondary == null) null else {
            { Text(text = secondary, overflow = TextOverflow.Ellipsis, maxLines = 2) }
        },
        label = { Text(text = label, overflow = TextOverflow.Ellipsis, maxLines = 3) },
    )
}

// ---------------------------------------------------------------------------------------------
// Cards (ANCS)
// ---------------------------------------------------------------------------------------------

/** Prominent incoming-call card; answer/decline labels come from the iPhone (ANCS action labels). */
@Composable
internal fun IncomingCallCard(call: PresentedCard, actionsEnabled: Boolean, onAction: (ActionId) -> Unit) {
    val p = call.presentation
    val card = call.active.card
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
        ),
    ) {
        Text(
            text = p.title,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = p.source.label,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall,
        )
        if (p.body.isNotBlank()) {
            Text(
                text = p.body,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                maxLines = 3,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (actionsEnabled) {
            card.positiveActionLabel?.let { label ->
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = { onAction(ActionId.POSITIVE) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { CenteredLabel(label) },
                )
            }
            card.negativeActionLabel?.let { label ->
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { onAction(ActionId.NEGATIVE) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                    label = { CenteredLabel(label) },
                )
            }
        }
        ActionStatusText(call.active)
    }
}

/** One forwarded notification: source badge, title, body (3 lines) and optional iPhone actions. */
@Composable
internal fun NotificationItem(item: PresentedCard, actionsEnabled: Boolean, onAction: (ActionId) -> Unit) {
    val p = item.presentation
    val card = item.active.card
    TitleCard(
        title = { Text(text = p.title, overflow = TextOverflow.Ellipsis, maxLines = 2) },
        modifier = Modifier.fillMaxWidth(),
        time = { SourceBadge(p.source) },
        subtitle = {
            // With hidden preview the title already is the source label; avoid repeating it.
            if (p.title != p.source.label) {
                Text(text = p.source.label, overflow = TextOverflow.Ellipsis, maxLines = 1)
            }
            if (actionsEnabled) CardActions(card, onAction)
            ActionStatusText(item.active)
        },
        content = {
            if (p.body.isNotBlank()) {
                Text(text = p.body, overflow = TextOverflow.Ellipsis, maxLines = 3)
            }
        },
    )
}

@Composable
private fun CardActions(card: NotificationCard, onAction: (ActionId) -> Unit) {
    card.positiveActionLabel?.let { label ->
        CompactButton(
            onClick = { onAction(ActionId.POSITIVE) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = label, overflow = TextOverflow.Ellipsis, maxLines = 1) },
        )
    }
    card.negativeActionLabel?.let { label ->
        CompactButton(
            onClick = { onAction(ActionId.NEGATIVE) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.filledTonalButtonColors(),
            label = { Text(text = label, overflow = TextOverflow.Ellipsis, maxLines = 1) },
        )
    }
}

/** Short colored pill with the source badge (e.g. "WA"), using the source accent color. */
@Composable
private fun SourceBadge(source: SourceProfile) {
    val accent = Color(source.accentArgb)
    if (source.badge.isBlank()) {
        Box(modifier = Modifier.size(8.dp).background(color = accent, shape = CircleShape))
    } else {
        Box(
            modifier = Modifier
                .background(color = accent, shape = CircleShape)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text(text = source.badge, color = Color.White, maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ActionStatusText(active: ActiveCard) {
    val status = active.actionStatus ?: return
    val action = active.lastAction ?: return
    val name = when (action) {
        ActionId.POSITIVE -> active.card.positiveActionLabel
        ActionId.NEGATIVE -> active.card.negativeActionLabel
    } ?: "Acción"
    Text(
        text = "$name: ${actionStatusLabel(status)}",
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        color = if (status == ActionStatus.REQUESTED) Color.Unspecified else MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        maxLines = 1,
        style = MaterialTheme.typography.labelSmall,
    )
}

// ---------------------------------------------------------------------------------------------
// Music (AMS)
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

/** Explicit PLAY/PAUSE matching the shown icon when available, otherwise TOGGLE_PLAY_PAUSE. */
private fun playPauseCommand(media: MediaState): RemoteCommand? =
    listOf(if (media.isPlaying) RemoteCommand.PAUSE else RemoteCommand.PLAY, RemoteCommand.TOGGLE_PLAY_PAUSE)
        .firstOrNull { canSend(media, it) }

/** Elapsed time extrapolated from the AMS anchor; ticks once per second only while playback moves. */
@Composable
private fun rememberElapsedSeconds(media: MediaState): Double? {
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

@Composable
internal fun NowPlaying(media: MediaState, hidePreview: Boolean, hiddenText: String) {
    val elapsed = rememberElapsedSeconds(media)
    val duration = media.durationSeconds
    val time = when {
        elapsed == null && duration == null -> null
        duration == null -> formatMediaTime(elapsed)
        else -> "${formatMediaTime(elapsed)} / ${formatMediaTime(duration)}"
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = media.playerName,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall,
        )
        if (hidePreview) {
            Text(text = hiddenText, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
        } else {
            if (media.title.isNotBlank()) {
                Text(
                    text = media.title,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (media.artist.isNotBlank()) {
                Text(
                    text = media.artist,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (time != null) {
            Text(text = time, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun TransportControls(media: MediaState, onCommand: (RemoteCommand) -> Unit) {
    val playPause = playPauseCommand(media)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaIconButton(
            icon = android.R.drawable.ic_media_previous,
            description = "Anterior",
            size = IconButtonDefaults.SmallButtonSize,
            enabled = canSend(media, RemoteCommand.PREVIOUS_TRACK),
            filled = false,
        ) { onCommand(RemoteCommand.PREVIOUS_TRACK) }
        MediaIconButton(
            icon = if (media.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            description = if (media.isPlaying) "Pausar" else "Reproducir",
            size = IconButtonDefaults.DefaultButtonSize,
            enabled = playPause != null,
            filled = true,
        ) { playPause?.let(onCommand) }
        MediaIconButton(
            icon = android.R.drawable.ic_media_next,
            description = "Siguiente",
            size = IconButtonDefaults.SmallButtonSize,
            enabled = canSend(media, RemoteCommand.NEXT_TRACK),
            filled = false,
        ) { onCommand(RemoteCommand.NEXT_TRACK) }
    }
}

@Composable
internal fun VolumeControls(media: MediaState, onCommand: (RemoteCommand) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactButton(
            onClick = { onCommand(RemoteCommand.VOLUME_DOWN) },
            enabled = media.supports(RemoteCommand.VOLUME_DOWN),
            colors = ButtonDefaults.filledTonalButtonColors(),
            label = { Text(text = "Vol −", maxLines = 1) },
        )
        media.volume?.let { v ->
            Text(text = volumePercent(v), maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
        CompactButton(
            onClick = { onCommand(RemoteCommand.VOLUME_UP) },
            enabled = media.supports(RemoteCommand.VOLUME_UP),
            colors = ButtonDefaults.filledTonalButtonColors(),
            label = { Text(text = "Vol +", maxLines = 1) },
        )
    }
}

@Composable
private fun MediaIconButton(
    @DrawableRes icon: Int,
    description: String,
    size: Dp,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val modifier = Modifier.touchTargetAwareSize(size)
    val content: @Composable BoxScope.() -> Unit = {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(IconButtonDefaults.iconSizeFor(size)),
        )
    }
    if (filled) {
        FilledIconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    } else {
        FilledTonalIconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}
