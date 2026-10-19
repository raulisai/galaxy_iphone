package dev.wristbridge.notification

import kotlin.math.roundToInt

/**
 * Pure Spanish strings for TalkBack (P3-01/P3-02, UI03). No Android or Compose types, so they are unit-testable
 * on the JVM. They only describe link/media state and never receive notification or track content, except
 * [describeIncomingCall], which receives the already-presented (hide-preview aware) caller text.
 */
object AccessibilityText {
    const val ON = "Activado"
    const val OFF = "Desactivado"

    enum class StatusKind { OK, NEUTRAL, ERROR }

    fun switchState(on: Boolean): String = if (on) ON else OFF

    fun playbackState(isPlaying: Boolean): String = if (isPlaying) "Reproduciendo" else "En pausa"

    /** "1 minuto y 5 segundos"; null when [seconds] is unknown or not finite. Fractions are truncated. */
    fun spokenDuration(seconds: Double?): String? {
        if (seconds == null || seconds.isNaN() || seconds.isInfinite()) return null
        val total = seconds.toLong().coerceAtLeast(0)
        val parts = ArrayList<String>(3)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        if (h > 0) parts += if (h == 1L) "1 hora" else "$h horas"
        if (m > 0) parts += if (m == 1L) "1 minuto" else "$m minutos"
        if (s > 0 || parts.isEmpty()) parts += if (s == 1L) "1 segundo" else "$s segundos"
        return when (parts.size) {
            1 -> parts[0]
            2 -> "${parts[0]} y ${parts[1]}"
            else -> "${parts[0]}, ${parts[1]} y ${parts[2]}"
        }
    }

    /** Playback position fraction in 0..1; 0 when unknown. */
    fun progressFraction(elapsed: Double?, duration: Double?): Float {
        if (elapsed == null || duration == null || !(duration > 0.0) || elapsed.isNaN()) return 0f
        return (elapsed / duration).coerceIn(0.0, 1.0).toFloat()
    }

    fun describeProgress(elapsed: Double?, duration: Double?): String {
        val e = spokenDuration(elapsed) ?: return "Progreso no disponible"
        val d = if (duration != null && duration > 0.0) spokenDuration(duration) else null
        return if (d == null) "$e transcurridos" else "$e de $d"
    }

    fun describeVolume(volume: Double?): String {
        if (volume == null || volume.isNaN()) return "Volumen no disponible"
        return "Volumen ${(volume.coerceIn(0.0, 1.0) * 100).roundToInt()} por ciento"
    }

    /**
     * Status line for screen readers so that the meaning never relies on the dot colour: errors always say so,
     * and the typographic "·" and "…" (read aloud as symbols) become plain punctuation.
     */
    fun describeStatus(text: String, kind: StatusKind): String {
        val clean = text.replace(" · ", ", ").replace("·", ",").replace("…", "").trim()
        return when {
            kind != StatusKind.ERROR -> clean
            clean.startsWith("Error", ignoreCase = true) -> clean
            else -> "Problema: $clean"
        }
    }

    fun describeCount(count: Int): String = when {
        count <= 0 -> "Sin avisos"
        count == 1 -> "1 aviso"
        else -> "$count avisos"
    }

    /** Accessible name of a call button; iPhone-provided labels are kept when they add information. */
    fun describeCallAction(label: String?, positive: Boolean): String {
        val base = if (positive) "Aceptar llamada" else "Rechazar llamada"
        val l = label?.trim().orEmpty()
        return if (l.isEmpty() || base.contains(l, ignoreCase = true)) base else "$base, $l"
    }

    fun describeIncomingCall(source: String, caller: String): String {
        val s = source.trim()
        val c = caller.trim()
        val head = if (s.isEmpty()) "Llamada entrante" else "Llamada entrante de $s"
        return if (c.isEmpty() || c.equals(s, ignoreCase = true)) head else "$head. $c"
    }
}
