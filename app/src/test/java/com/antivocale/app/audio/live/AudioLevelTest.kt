package com.antivocale.app.audio.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class AudioLevelTest {

    private fun tone(amplitude: Float, n: Int = 16_000) =
        FloatArray(n) { (amplitude * sin(2 * Math.PI * 220 * it / 16_000)).toFloat() }

    @Test
    fun `gain multiplies quiet samples linearly`() {
        val frame = floatArrayOf(0.01f, -0.02f, 0.05f)
        AudioLevel.applyGain(frame, 4f)
        assertEquals(0.04f, frame[0], 1e-6f)
        assertEquals(-0.08f, frame[1], 1e-6f)
        assertEquals(0.2f, frame[2], 1e-6f)
    }

    @Test
    fun `gain never pushes a sample beyond full scale and keeps the sign`() {
        val frame = floatArrayOf(0.5f, -0.9f, 1f)
        AudioLevel.applyGain(frame, 10f)
        frame.forEach { assertTrue("$it", abs(it) <= 1f) }
        assertTrue(frame[1] < 0f)
        // Monotonic above the knee: louder input stays louder.
        assertTrue(AudioLevel.softClip(2f) < AudioLevel.softClip(4f))
    }

    @Test
    fun `quiet phrase is leveled up to the target`() {
        val quiet = tone(0.02f)
        val out = AudioLevel.normalizePhrase(quiet)
        val rms = AudioLevel.loudBlocksRms(out, 320)
        assertEquals(AudioLevel.TARGET_RMS, rms, 0.01f)
        assertEquals(0.02f, quiet.maxOf { abs(it) }, 1e-4f) // input untouched
    }

    @Test
    fun `leveling is capped at +20 dB and never attenuates`() {
        val whisper = tone(0.0005f)
        val out = AudioLevel.normalizePhrase(whisper)
        assertEquals(0.0005f * AudioLevel.MAX_NORMALIZE_GAIN, out.maxOf { abs(it) }, 1e-4f)
        val loud = tone(0.5f)
        assertSame(loud, AudioLevel.normalizePhrase(loud))
    }

    @Test
    fun `pauses inside the phrase do not inflate the gain`() {
        // Half speech at 0.14 peak (~0.1 rms), half silence: already on target.
        val phrase = tone(0.14f, 8_000) + FloatArray(8_000)
        assertSame(phrase, AudioLevel.normalizePhrase(phrase))
    }

    @Test
    fun `sensitivity maps onto the detector threshold range`() {
        assertEquals(0.7f, AudioLevel.vadThreshold(0f), 1e-6f)
        assertEquals(0.2f, AudioLevel.vadThreshold(1f), 1e-6f)
        assertEquals(0.4f, AudioLevel.vadThreshold(SpeechDetector.DEFAULT_SENSITIVITY), 1e-6f)
    }
}
