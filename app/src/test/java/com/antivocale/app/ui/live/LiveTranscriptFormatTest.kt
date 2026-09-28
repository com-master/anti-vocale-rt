package com.antivocale.app.ui.live

import com.antivocale.app.transcription.TimedSegment
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class LiveTranscriptFormatTest {

    private val utc = TimeZone.getTimeZone("UTC")
    // 2026-09-28 14:03:00 UTC
    private val start = 1_790_604_180_000L

    @Test
    fun `timed copy stamps each phrase with its wall-clock start and speaker`() {
        val segments = listOf(
            TimedSegment(0, 4_000, "Привет.", speaker = 0),
            TimedSegment(12_500, 16_000, "Здравствуйте.", speaker = 1, speakerName = "Анна"),
            TimedSegment(17_000, 20_000, "Как дела?", speaker = 1, speakerName = "Анна"),
        )
        assertEquals(
            "[14:03:00] SPEAKER 1: Привет.\n[14:03:12] Анна: Здравствуйте.\n[14:03:17] Анна: Как дела?",
            LiveTranscriptFormat.timed(start, segments, utc),
        )
    }

    @Test
    fun `plain copy puts speaker turns on their own lines`() {
        val segments = listOf(
            TimedSegment(0, 4_000, "Раз.", speaker = 0),
            TimedSegment(4_000, 8_000, "Два.", speaker = 0),
            TimedSegment(8_000, 9_000, "Три.", speaker = 1),
        )
        assertEquals("SPEAKER 1: Раз.\nДва.\nSPEAKER 2: Три.", LiveTranscriptFormat.plain(segments))
    }

    @Test
    fun `without roles the copy has no labels`() {
        val segments = listOf(TimedSegment(0, 1_000, "Раз."), TimedSegment(61_000, 62_000, "Два."))
        assertEquals("Раз. Два.", LiveTranscriptFormat.plain(segments))
        assertEquals("[14:03:00] Раз.\n[14:04:01] Два.", LiveTranscriptFormat.timed(start, segments, utc))
    }
}
