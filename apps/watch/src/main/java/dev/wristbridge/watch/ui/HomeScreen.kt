package dev.wristbridge.watch.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import dev.wristbridge.watch.p2.P2Bridge
import dev.wristbridge.watch.p2.P2Phase

import dev.wristbridge.notification.AccessibilityText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.MaterialTheme
import dev.wristbridge.watch.R
import androidx.compose.ui.res.stringResource
import dev.wristbridge.watch.bridge.AmsState

/** Hub with one large tile per area, like the Apple Watch app list. Everything is derived from [model]. */
@Composable
internal fun HomeScreen(
    model: UiModel,
    onOpen: (Screen) -> Unit,
    onShowCall: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val m = LocalMetrics.current
    val music = model.music
    val media = music.media
    val playerVisible = music.ams == AmsState.READY && media.hasPlayer
    val hiddenText = stringResource(R.string.card_hidden)
    val connectionColor = statusColor(model.headline, model.bridge.serviceRunning)

    ListScreen(paneTitle = stringResource(R.string.app_name)) {
        item(key = "title") { ScreenTitle(stringResource(R.string.app_name)) }

        // Without permissions nothing works: the request goes first.
        if (model.permissionsMissing) {
            item(key = "permissions") {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(m.dp(24))).background(WbColors.Card)
                        .padding(m.dp(12)),
                    verticalArrangement = Arrangement.spacedBy(m.dp(8)),
                ) {
                    WbText(
                        stringResource(R.string.permissions_needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = WbColors.Amber,
                        align = TextAlign.Center,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Pill(stringResource(R.string.grant_permissions), onRequestPermissions, container = WbColors.Amber, pressed = WbColors.Amber, textColor = Color.Black)
                }
            }
        }

        // A ringing call is a green card on top of the list; the call screen itself is a takeover.
        model.call?.let { call ->
            item(key = "call") {
                Tile(
                    onClick = onShowCall,
                    glyph = GlyphKind.PHONE,
                    accent = Color.White.copy(alpha = 0.25f),
                    title = "Llamada entrante",
                    subtitle = call.presentation.body.ifBlank { call.presentation.source.label },
                    container = WbColors.Green,
                    pressed = WbColors.Green.copy(alpha = 0.8f),
                    subtitleColor = Color.White,
                )
            }
        }

        item(key = "alerts") {
            val newest = model.alerts.firstOrNull()?.presentation?.source?.label
            Tile(
                onClick = { onOpen(Screen.ALERTS) },
                glyph = GlyphKind.BELL,
                accent = WbColors.Red,
                title = "Avisos",
                subtitle = newest ?: "Sin avisos",
                trailing = if (model.alerts.isNotEmpty()) ({ CountBadge(model.alerts.size) }) else null,
            )
        }

        item(key = "music") {
            if (playerVisible) {
                val title = when {
                    model.hidePreview -> hiddenText
                    media.title.isNotBlank() -> media.title
                    else -> media.playerName
                }
                val artist = if (model.hidePreview) null else media.artist.takeIf { it.isNotBlank() } ?: media.playerName
                // Same brand color as the player screen; white would vanish behind the white note glyph.
                val accent = playerAccent(media.playerName).takeIf { it != WbColors.Label } ?: WbColors.Pink
                Tile(
                    onClick = { onOpen(Screen.MUSIC) },
                    glyph = GlyphKind.NOTE,
                    accent = accent,
                    title = title,
                    subtitle = artist,
                    trailing = {
                        // Icon shows the action; TalkBack gets the current state instead.
                        Glyph(
                            if (media.isPlaying) GlyphKind.PAUSE else GlyphKind.PLAY,
                            accent,
                            Modifier.size(m.dp(22)),
                            description = AccessibilityText.playbackState(media.isPlaying),
                        )
                    },
                )
            } else {
                Tile(
                    onClick = { onOpen(Screen.MUSIC) },
                    glyph = GlyphKind.NOTE,
                    accent = WbColors.Pink,
                    title = "Música",
                    subtitle = musicShort(music),
                )
            }
        }

        item(key = "health") {
            Tile(
                onClick = { onOpen(Screen.HEALTH) }, glyph = GlyphKind.PHONE,
                accent = WbColors.Green, title = "Salud", subtitle = "Pasos y pulso del reloj",
            )
        }

        item(key = "findphone") {
            val p2 by P2Bridge.state.collectAsStateWithLifecycle()
            Tile(
                onClick = { P2Bridge.findPhone() },
                glyph = GlyphKind.BELL,
                accent = WbColors.Amber,
                title = "Buscar iPhone",
                subtitle = p2.findPhone.ifBlank { when { p2.phase == P2Phase.READY -> "Hacer sonar tu iPhone"; p2.paired -> "Sesión cerrada" + (if (p2.lastClosed.isNotBlank()) ": ${p2.lastClosed}" else ". Abre WristBridge en el iPhone") else -> "Requiere vincular el iPhone" } },
                subtitleLines = 2,
                description = "Buscar iPhone. " + p2.findPhone.ifBlank { "Hace sonar el iPhone vinculado" },
                liveStatus = true,
            )
        }

        item(key = "connection") {
            Tile(
                onClick = { onOpen(Screen.CONNECTION) },
                glyph = GlyphKind.RADIO,
                accent = WbColors.Blue,
                title = "Conexión",
                subtitle = model.headline.text,
                subtitleLines = 2,
                dot = connectionColor,
                description = "Conexión, " + describeStatus(model.headline),
                liveStatus = true,
            )
        }

        item(key = "settings") {
            Tile(
                onClick = { onOpen(Screen.SETTINGS) },
                glyph = GlyphKind.GEAR,
                accent = WbColors.Secondary,
                title = "Ajustes",
                subtitle = "Privacidad y diagnóstico",
            )
        }
    }
}
