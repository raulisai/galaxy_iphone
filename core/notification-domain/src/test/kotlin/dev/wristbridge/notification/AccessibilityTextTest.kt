package dev.wristbridge.notification

import dev.wristbridge.notification.AccessibilityText.StatusKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** P3-01/P3-02 TalkBack strings. All values are synthetic. */
class AccessibilityTextTest {
    private val a = AccessibilityText

    @Test fun t01_switchState() {
        assertEquals("Activado", a.switchState(true))
        assertEquals("Desactivado", a.switchState(false))
    }

    @Test fun t02_spokenDuration() {
        assertEquals("0 segundos", a.spokenDuration(0.0))
        assertEquals("1 segundo", a.spokenDuration(1.9))
        assertEquals("1 minuto y 5 segundos", a.spokenDuration(65.0))
        assertEquals("2 minutos", a.spokenDuration(120.0))
        assertEquals("1 hora, 1 minuto y 1 segundo", a.spokenDuration(3661.0))
        assertEquals("0 segundos", a.spokenDuration(-4.0))
        assertNull(a.spokenDuration(null))
        assertNull(a.spokenDuration(Double.NaN))
        assertNull(a.spokenDuration(Double.POSITIVE_INFINITY))
    }

    @Test fun t03_progress() {
        assertEquals("1 minuto y 5 segundos de 3 minutos y 20 segundos", a.describeProgress(65.0, 200.0))
        assertEquals("30 segundos transcurridos", a.describeProgress(30.0, null))
        assertEquals("30 segundos transcurridos", a.describeProgress(30.0, 0.0))
        assertEquals("Progreso no disponible", a.describeProgress(null, 200.0))
    }

    @Test fun t04_progressFraction() {
        assertEquals(0.5f, a.progressFraction(50.0, 100.0))
        assertEquals(1f, a.progressFraction(500.0, 100.0))
        assertEquals(0f, a.progressFraction(-5.0, 100.0))
        assertEquals(0f, a.progressFraction(5.0, null))
        assertEquals(0f, a.progressFraction(5.0, 0.0))
    }

    @Test fun t05_volume() {
        assertEquals("Volumen 50 por ciento", a.describeVolume(0.5))
        assertEquals("Volumen 100 por ciento", a.describeVolume(3.0))
        assertEquals("Volumen 0 por ciento", a.describeVolume(-1.0))
        assertEquals("Volumen no disponible", a.describeVolume(null))
    }

    @Test fun t06_status() {
        assertEquals("Listo, avisos y música", a.describeStatus("Listo · avisos y música", StatusKind.OK))
        assertEquals("Conectando", a.describeStatus("Conectando…", StatusKind.NEUTRAL))
        assertEquals("Problema: Bluetooth apagado", a.describeStatus("Bluetooth apagado", StatusKind.ERROR))
        assertEquals("Error: gatt 133", a.describeStatus("Error: gatt 133", StatusKind.ERROR))
    }

    @Test fun t07_count() {
        assertEquals("Sin avisos", a.describeCount(0))
        assertEquals("1 aviso", a.describeCount(1))
        assertEquals("12 avisos", a.describeCount(12))
    }

    @Test fun t08_callAction() {
        assertEquals("Rechazar llamada", a.describeCallAction("Rechazar", false))
        assertEquals("Rechazar llamada", a.describeCallAction(null, false))
        assertEquals("Aceptar llamada", a.describeCallAction(" ", true))
        assertEquals("Aceptar llamada, Answer", a.describeCallAction("Answer", true))
    }

    @Test fun t09_incomingCall() {
        assertEquals("Llamada entrante de Teléfono. Ana", a.describeIncomingCall("Teléfono", "Ana"))
        assertEquals("Llamada entrante de Teléfono", a.describeIncomingCall("Teléfono", "teléfono"))
        assertEquals("Llamada entrante", a.describeIncomingCall("", ""))
    }

    @Test fun t10_playback() {
        assertEquals("Reproduciendo", a.playbackState(true))
        assertEquals("En pausa", a.playbackState(false))
    }
}
