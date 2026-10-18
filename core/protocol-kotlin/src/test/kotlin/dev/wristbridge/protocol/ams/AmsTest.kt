package dev.wristbridge.protocol.ams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Byte layouts written from the AMS specification; values are synthetic. */
class AmsTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun upd(entity: Int, attr: Int, value: String, flags: Int = 0) =
        byteArrayOf(entity.toByte(), attr.toByte(), flags.toByte()) + value.toByteArray(Charsets.UTF_8)

    @Test
    fun m01_registrationAndCommandsEncodeAsSpecified() {
        assertEquals("00000102", hex(AmsCodec.registerPlayer))
        assertEquals("0100010203", hex(AmsCodec.registerQueue))
        assertEquals("0200010203", hex(AmsCodec.registerTrack))
        assertEquals("02", hex(AmsCodec.remoteCommand(RemoteCommand.TOGGLE_PLAY_PAUSE)))
        assertEquals("0202", hex(AmsCodec.entityAttributeRequest(AmsEntity.TRACK, TrackAttribute.TITLE)))
    }

    @Test
    fun m02_entityUpdateParsingIsBoundedAndUtf8() {
        assertNull(AmsCodec.parseEntityUpdate(byteArrayOf(2, 2)))
        val u = AmsCodec.parseEntityUpdate(upd(2, 2, "Canción sintética ♪", flags = 1))!!
        assertEquals(EntityUpdate(2, 2, true, "Canción sintética ♪"), u)
        assertEquals("", AmsCodec.parseEntityUpdate(byteArrayOf(0, 0, 0))!!.value)
    }

    @Test
    fun m03_supportedCommandsIgnoreUnknownIds() {
        val set = AmsCodec.parseSupportedCommands(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 0x7F))
        assertEquals(7, set.size)
        assertTrue(RemoteCommand.VOLUME_DOWN in set)
    }

    @Test
    fun m04_reducerTracksPlayerTrackAndTruncation() {
        var s = MediaState()
        s = s.apply(AmsCodec.parseEntityUpdate(upd(0, 0, "Música"))!!, 0)
        s = s.apply(AmsCodec.parseEntityUpdate(upd(0, 1, "1,1.0,10.5"))!!, 1_000_000_000)
        s = s.apply(AmsCodec.parseEntityUpdate(upd(0, 2, "0.75"))!!, 0)
        s = s.apply(AmsCodec.parseEntityUpdate(upd(2, 0, "Artista"))!!, 0)
        s = s.apply(AmsCodec.parseEntityUpdate(upd(2, 2, "Título lar", flags = 1))!!, 0)
        s = s.apply(AmsCodec.parseEntityUpdate(upd(2, 3, "200.25"))!!, 0)
        assertTrue(s.hasPlayer)
        assertTrue(s.isPlaying)
        assertEquals(0.75, s.volume)
        assertEquals("Artista", s.artist)
        assertEquals(200.25, s.durationSeconds)
        assertEquals(setOf(2 to 2), s.truncated)
        // Elapsed extrapolates with the playback rate from the monotonic anchor.
        assertEquals(15.5, s.elapsedAt(6_000_000_000))
        s = s.apply(EntityUpdate(2, 2, false, "Título largo completo"), 0)
        assertTrue(s.truncated.isEmpty())
        assertEquals("Título largo completo", s.title)
    }

    @Test
    fun m05_pausedDoesNotAdvanceAndMalformedKeepsPrevious() {
        var s = MediaState().apply(EntityUpdate(0, 1, false, "0,0.0,42"), 0)
        assertEquals(PlaybackState.PAUSED, s.playback)
        assertEquals(42.0, s.elapsedAt(60_000_000_000))
        s = s.apply(EntityUpdate(0, 2, false, "no-numero"), 0)
        assertNull(s.volume)
        s = s.apply(EntityUpdate(0, 1, false, "x,y"), 5)
        assertEquals(PlaybackState.PAUSED, s.playback)
        assertEquals(42.0, s.elapsedSeconds)
    }

    @Test
    fun m06_emptyPlayerNameClearsStaleTrack() {
        var s = MediaState(
            playerName = "Música", title = "T", artist = "A", playback = PlaybackState.PLAYING,
            supportedCommands = setOf(RemoteCommand.PLAY), volume = 0.5,
        )
        s = s.apply(EntityUpdate(0, 0, false, ""), 0)
        assertFalse(s.hasPlayer)
        assertEquals("", s.title)
        assertFalse(s.isPlaying)
        assertEquals(setOf(RemoteCommand.PLAY), s.supportedCommands)
        assertEquals(0.5, s.volume)
    }

    @Test
    fun m07_elapsedIsCappedAndTimeIsFormatted() {
        val s = MediaState(playback = PlaybackState.PLAYING, rate = 1.0, elapsedSeconds = 10.0, durationSeconds = 12.0)
        assertEquals(12.0, s.elapsedAt(60_000_000_000))
        assertEquals("--:--", formatMediaTime(null))
        assertEquals("3:05", formatMediaTime(185.9))
        assertEquals("1:01:01", formatMediaTime(3661.0))
    }
}
