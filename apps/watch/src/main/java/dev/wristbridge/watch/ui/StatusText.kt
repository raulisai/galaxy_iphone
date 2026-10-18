package dev.wristbridge.watch.ui

import dev.wristbridge.notification.ActionStatus
import dev.wristbridge.watch.bridge.AmsState
import dev.wristbridge.watch.bridge.AncsState
import dev.wristbridge.watch.bridge.BondState
import dev.wristbridge.watch.bridge.BridgeUiState
import dev.wristbridge.watch.bridge.MediaUiState
import dev.wristbridge.watch.bridge.OwnChannelState
import dev.wristbridge.watch.bridge.RadioState
import kotlin.math.roundToInt

/**
 * Pure label helpers for the main screen. They only describe link/service state and never receive
 * notification or track content, so they are safe to reuse in diagnostics.
 */
internal enum class Tone { OK, NEUTRAL, ERROR }

internal data class Headline(val text: String, val tone: Tone)

/**
 * One plain-language status line. "Listo" requires ANCS READY (both subscriptions confirmed, Contract §1);
 * a connected own channel alone is never reported as ready.
 */
internal fun headline(s: BridgeUiState, ams: AmsState, bluetoothGranted: Boolean): Headline = when {
    !s.serviceRunning && !bluetoothGranted -> Headline("Faltan permisos", Tone.ERROR)
    !s.serviceRunning -> Headline("Detenido", Tone.NEUTRAL)
    s.radio == RadioState.OFF -> Headline("Bluetooth apagado", Tone.ERROR)
    s.radio == RadioState.UNAVAILABLE -> Headline("Bluetooth no disponible", Tone.ERROR)
    s.ancs == AncsState.READY && ams == AmsState.READY -> Headline("Listo · avisos y música", Tone.OK)
    s.ancs == AncsState.READY -> Headline("Listo · avisos", Tone.OK)
    s.ancs == AncsState.FAILED -> Headline(errorLine(s.ancsDetail), Tone.ERROR)
    s.ancs == AncsState.NOT_FOUND -> Headline(errorLine(s.ancsDetail.ifBlank { "avisos no disponibles" }), Tone.ERROR)
    s.ownChannel == OwnChannelState.FAILED -> Headline(errorLine(s.ownChannelDetail), Tone.ERROR)
    s.bond == BondState.BONDING -> Headline("Vinculando…", Tone.NEUTRAL)
    s.ancs != AncsState.IDLE || s.ownChannel == OwnChannelState.CONNECTED -> Headline("Conectando…", Tone.NEUTRAL)
    else -> Headline("Esperando al iPhone", Tone.NEUTRAL)
}

private fun errorLine(detail: String) = if (detail.isBlank()) "Error" else "Error: $detail"

internal fun radioLabel(r: RadioState) = when (r) {
    RadioState.ON -> "Encendido"
    RadioState.OFF -> "Apagado"
    RadioState.UNAVAILABLE -> "No disponible"
    RadioState.UNKNOWN -> "Sin comprobar"
}

internal fun ownLabel(s: BridgeUiState) = when (s.ownChannel) {
    OwnChannelState.STOPPED -> "Detenido"
    OwnChannelState.STARTING -> "Iniciando"
    OwnChannelState.ADVERTISING -> "Anunciando, esperando iPhone"
    OwnChannelState.CONNECTED -> "iPhone conectado (${s.peer ?: "?"})"
    OwnChannelState.FAILED -> "Error"
}

internal fun bondLabel(b: BondState) = when (b) {
    BondState.BONDED -> "Vinculado"
    BondState.BONDING -> "Vinculando"
    BondState.NONE -> "Sin vincular"
    BondState.UNKNOWN -> "Sin comprobar"
}

/** "Listo" only after both remote subscriptions were confirmed (Contract §1). */
internal fun ancsLabel(a: AncsState) = when (a) {
    AncsState.IDLE -> "Sin sesión"
    AncsState.CONNECTING -> "Conectando"
    AncsState.DISCOVERING -> "Buscando servicio"
    AncsState.NOT_FOUND -> "No disponible"
    AncsState.SUBSCRIBING -> "Suscribiendo"
    AncsState.DATA_SOURCE_CONFIRMED -> "Data Source confirmado"
    AncsState.READY -> "Listo"
    AncsState.FAILED -> "Error"
}

/** AMS is independent of ANCS: an AMS problem never changes the ANCS label. */
internal fun amsLabel(a: AmsState) = when (a) {
    AmsState.IDLE -> "Sin sesión"
    AmsState.NOT_FOUND -> "No disponible"
    AmsState.SUBSCRIBING -> "Suscribiendo"
    AmsState.READY -> "Listo"
    AmsState.FAILED -> "Error"
}

/** Single line shown in the music section when there is no player to control. */
internal fun musicUnavailableLine(ui: MediaUiState): String = when {
    ui.ams == AmsState.READY -> "Música: sin reproducción en el iPhone"
    ui.detail.isBlank() -> "Música: ${amsLabel(ui.ams).lowercase()}"
    else -> "Música: ${amsLabel(ui.ams).lowercase()} · ${ui.detail}"
}

/** REQUESTED only means the iPhone accepted the write; it is not proof of the final effect. */
internal fun actionStatusLabel(status: ActionStatus) = when (status) {
    ActionStatus.REQUESTED -> "Solicitado…"
    ActionStatus.FAILED -> "Falló"
    ActionStatus.UNAVAILABLE -> "No disponible"
}

internal fun volumePercent(volume: Double): String = "${(volume.coerceIn(0.0, 1.0) * 100).roundToInt()} %"

internal fun lastReceptionLabel(lastElapsedMs: Long?, nowElapsedMs: Long): String {
    val t = lastElapsedMs ?: return "ninguna"
    return "hace ${((nowElapsedMs - t) / 1000).coerceAtLeast(0)} s"
}

internal fun diagnostics(s: BridgeUiState): String {
    val c = s.counters
    return "gen=${s.generation} mtu=${s.mtu} gatt=${s.lastGattStatus ?: "-"}\n" +
        "eventos=${c.nsEvents} añadidos=${c.added} modificados=${c.modified} retirados=${c.removed} previos=${c.preExisting}\n" +
        "consultas=${c.fetchStarted} completas=${c.fetchCompleted} timeouts=${c.fetchTimedOut} fallos=${c.fetchFailed} ancsErr=${c.ancsErrors}\n" +
        "tarjetas +${c.cardsPosted} ~${c.cardsUpdated} -${c.cardsRemoved} desbordes=${c.queueOverflow} " +
        "descartes=${c.discardedResponses} obsoletos=${c.staleInputsIgnored} inválidos=${c.nsInvalid}\n" +
        "acciones ok=${c.actionsRequested} fallo=${c.actionsFailed}"
}
