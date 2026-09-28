package com.antivocale.app.audio.live

import com.antivocale.app.audio.live.PhraseSegmenter.CutReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseSegmenterTest {

    private val sr = 16_000
    private val frame = 512

    /** One scripted stretch of audio: [seconds] long, speech or not, at [amplitude]. */
    private data class Span(val seconds: Float, val speech: Boolean, val amplitude: Float = if (speech) 0.3f else 0.001f)

    private fun speech(s: Float, amp: Float = 0.3f) = Span(s, true, amp)
    private fun silence(s: Float) = Span(s, false)

    private fun run(seg: PhraseSegmenter, vararg spans: Span, flush: Boolean = false): List<PhraseSegmenter.Phrase> {
        val out = mutableListOf<PhraseSegmenter.Phrase>()
        for (span in spans) {
            var remaining = (span.seconds * sr).toInt()
            while (remaining > 0) {
                val n = minOf(frame, remaining)
                val samples = FloatArray(n) { i -> if (i % 2 == 0) span.amplitude else -span.amplitude }
                seg.accept(samples, span.speech)?.let(out::add)
                remaining -= n
            }
        }
        if (flush) seg.flush()?.let(out::add)
        return out
    }

    private fun PhraseSegmenter.Phrase.seconds() = durationSeconds(sr)

    @Test
    fun `short utterance is emitted after a long pause`() {
        val seg = PhraseSegmenter()
        val phrases = run(seg, silence(1f), speech(2f), silence(1.2f))
        assertEquals(1, phrases.size)
        val p = phrases[0]
        assertEquals(CutReason.LONG_PAUSE, p.reason)
        // 0.25 s pre-roll + 2 s speech + <= 0.25 s trailing silence.
        assertTrue("duration ${p.seconds()}", p.seconds() in 2.2f..2.6f)
        assertEquals(1f - 0.25f, p.startSeconds(sr), 0.05f)
    }

    @Test
    fun `short pause splits once the phrase reaches the minimum`() {
        val seg = PhraseSegmenter()
        val phrases = run(seg, speech(6f), silence(0.4f), speech(3f), silence(1.2f))
        assertEquals(2, phrases.size)
        assertEquals(CutReason.PAUSE, phrases[0].reason)
        assertTrue(phrases[0].seconds() in 6f..6.4f)
        assertEquals(CutReason.LONG_PAUSE, phrases[1].reason)
    }

    @Test
    fun `short pause below the minimum keeps accumulating`() {
        val seg = PhraseSegmenter()
        val phrases = run(seg, speech(3f), silence(0.4f), speech(3f), silence(1.2f))
        assertEquals(1, phrases.size)
        assertTrue("duration ${phrases[0].seconds()}", phrases[0].seconds() in 6.3f..6.8f)
    }

    @Test
    fun `continuous speech is cut at the ceiling and nothing is lost or duplicated`() {
        val seg = PhraseSegmenter()
        val phrases = run(seg, speech(25f), flush = true)
        assertTrue(phrases.size >= 3)
        phrases.forEach { assertTrue("phrase ${it.seconds()}s over the ceiling", it.seconds() <= 10f + 0.05f) }
        phrases.dropLast(1).forEach { assertEquals(CutReason.MAX_LENGTH, it.reason) }
        // Contiguous, non-overlapping coverage of the whole stream.
        var expectedStart = 0L
        for (p in phrases) {
            assertEquals(expectedStart, p.startSample)
            expectedStart += p.samples.size
        }
        assertEquals((25f * sr).toLong(), expectedStart)
    }

    @Test
    fun `ceiling cut lands on the quietest point of the search window`() {
        val seg = PhraseSegmenter()
        // A quiet (but still speech-flagged) dip at 8.5 s: the best place to cut.
        val phrases = run(seg, speech(8.5f), speech(0.1f, amp = 0.02f), speech(4f), silence(1.2f))
        val first = phrases.first()
        assertEquals(CutReason.MAX_LENGTH, first.reason)
        assertTrue("cut at ${first.seconds()}s", first.seconds() in 8.5f..8.65f)
    }

    @Test
    fun `a click shorter than the minimum speech is discarded`() {
        val seg = PhraseSegmenter()
        val phrases = run(seg, silence(0.5f), speech(0.1f), silence(1.5f), flush = true)
        assertTrue(phrases.isEmpty())
    }

    @Test
    fun `flush returns the pending phrase and then nothing`() {
        val seg = PhraseSegmenter()
        assertTrue(run(seg, speech(3f)).isEmpty())
        assertTrue(seg.isInPhrase)
        val p = seg.flush()
        assertEquals(CutReason.FLUSH, p?.reason)
        assertNull(seg.flush())
    }

    @Test
    fun `phrases never overlap across pauses`() {
        val seg = PhraseSegmenter()
        val phrases = run(
            seg,
            speech(2f), silence(1f), speech(7f), silence(0.5f), speech(12f), silence(2f), speech(1f),
            flush = true,
        )
        for ((a, b) in phrases.zipWithNext()) {
            assertTrue(a.startSample + a.samples.size <= b.startSample)
        }
        phrases.forEach { assertTrue(it.seconds() <= 10.05f) }
    }
}
