package com.antivocale.app.audio.live

import kotlin.math.sqrt

/**
 * Live mode: cuts a continuous microphone stream into PHRASES that an offline
 * recognizer (GigaAM first of all) can decode one at a time, so text appears
 * with a 5..10 s latency instead of after the whole recording.
 *
 * Pure Kotlin (no Android, no native): every frame arrives with a speech flag
 * from a [SpeechDetector]; this class only decides WHERE to cut.
 *
 * Cut rules, in priority order:
 *  1. Long pause ([Config.longPauseSeconds]) after speech: the phrase is
 *     finished whatever its length (a short utterance followed by silence must
 *     not wait for more speech to reach the minimum).
 *  2. Short pause ([Config.shortPauseSeconds]) once the phrase holds at least
 *     [Config.minPhraseSeconds]: the natural phrase boundary inside 5..10 s.
 *  3. Hard ceiling ([Config.maxPhraseSeconds]) with no pause: cut at the
 *     QUIETEST frame of the last [Config.cutSearchSeconds] (least likely to
 *     split a word) and carry the remainder into the next phrase. No overlap,
 *     so no duplicated words between phrases.
 *
 * Silence before the onset is kept as a short pre-roll (the recognizer needs
 * the attack of the first syllable); trailing silence beyond
 * [Config.trailingKeepSeconds] is dropped. Phrases with less than
 * [Config.minSpeechSeconds] of speech (clicks, coughs) are discarded.
 *
 * Not thread-safe: one producer (the capture loop) owns an instance.
 */
class PhraseSegmenter(val config: Config = Config()) {

    data class Config(
        val sampleRate: Int = 16_000,
        val minPhraseSeconds: Float = 5f,
        val maxPhraseSeconds: Float = 10f,
        val shortPauseSeconds: Float = 0.3f,
        val longPauseSeconds: Float = 0.8f,
        val minSpeechSeconds: Float = 0.3f,
        val preRollSeconds: Float = 0.25f,
        val trailingKeepSeconds: Float = 0.25f,
        val cutSearchSeconds: Float = 3f,
    ) {
        init {
            require(sampleRate > 0) { "sampleRate must be positive" }
            require(minPhraseSeconds in 0f..maxPhraseSeconds) { "minPhraseSeconds must be in 0..maxPhraseSeconds" }
            require(shortPauseSeconds in 0f..longPauseSeconds) { "shortPauseSeconds must be <= longPauseSeconds" }
            require(longPauseSeconds < maxPhraseSeconds) { "longPauseSeconds must be < maxPhraseSeconds" }
            require(cutSearchSeconds > 0f && cutSearchSeconds < maxPhraseSeconds) {
                "cutSearchSeconds must be in (0, maxPhraseSeconds)"
            }
        }

        internal fun samples(seconds: Float): Long = (seconds * sampleRate).toLong()
    }

    enum class CutReason { PAUSE, LONG_PAUSE, MAX_LENGTH, FLUSH }

    /**
     * One phrase ready for decoding.
     * @param startSample absolute position of the first sample in the stream
     *   (0 = first sample ever accepted), for the phrase's timestamp.
     */
    class Phrase(
        val samples: FloatArray,
        val startSample: Long,
        val reason: CutReason,
        val speechSamples: Long,
    ) {
        fun durationSeconds(sampleRate: Int): Float = samples.size.toFloat() / sampleRate
        fun startSeconds(sampleRate: Int): Float = startSample.toFloat() / sampleRate
    }

    private class Frame(val samples: FloatArray, val speech: Boolean, val energy: Float)

    private val minPhrase = config.samples(config.minPhraseSeconds)
    private val maxPhrase = config.samples(config.maxPhraseSeconds)
    private val shortPause = config.samples(config.shortPauseSeconds)
    private val longPause = config.samples(config.longPauseSeconds)
    private val minSpeech = config.samples(config.minSpeechSeconds)
    private val preRollMax = config.samples(config.preRollSeconds)
    private val trailingKeep = config.samples(config.trailingKeepSeconds)
    private val cutSearch = config.samples(config.cutSearchSeconds)

    private val preRoll = ArrayDeque<Frame>()
    private var preRollSamples = 0L
    private val frames = ArrayList<Frame>()
    private var bufferedSamples = 0L
    private var speechSamples = 0L
    /** Samples of non-speech at the end of [frames] since the last speech frame. */
    private var silenceRun = 0L
    /** Absolute stream position of frames[0]. */
    private var bufferStart = 0L
    /** Absolute stream position of the next sample to arrive. */
    private var streamPosition = 0L

    /** True while a phrase is being accumulated (speech onset seen, not yet cut). */
    val isInPhrase: Boolean get() = frames.isNotEmpty()

    /** Seconds of audio held in the phrase being accumulated. */
    val bufferedSeconds: Float get() = bufferedSamples.toFloat() / config.sampleRate

    /**
     * Feeds one frame (any length, typically 512 samples = 32 ms at 16 kHz).
     * @return a finished phrase when this frame closed one, else null.
     */
    fun accept(frame: FloatArray, isSpeech: Boolean): Phrase? {
        if (frame.isEmpty()) return null
        val f = Frame(frame.copyOf(), isSpeech, rms(frame))
        val frameStart = streamPosition
        streamPosition += frame.size

        if (frames.isEmpty()) {
            if (!isSpeech) {
                pushPreRoll(f)
                return null
            }
            // Onset: the pre-roll becomes the phrase head.
            bufferStart = frameStart - preRollSamples
            preRoll.forEach { append(it) }
            preRoll.clear()
            preRollSamples = 0
            silenceRun = 0
        }
        append(f)
        silenceRun = if (isSpeech) 0 else silenceRun + frame.size
        return maybeCut()
    }

    /** Ends the stream: returns whatever phrase is pending (null if none or too little speech). */
    fun flush(): Phrase? {
        if (frames.isEmpty()) return null
        return emitTrimmingSilence(CutReason.FLUSH)
    }

    /** Drops all buffered audio and restarts stream positions at 0. */
    fun reset() {
        preRoll.clear()
        preRollSamples = 0
        clearBuffer()
        streamPosition = 0
        bufferStart = 0
    }

    private fun maybeCut(): Phrase? = when {
        silenceRun >= longPause -> emitTrimmingSilence(CutReason.LONG_PAUSE)
        bufferedSamples >= minPhrase && silenceRun >= shortPause -> emitTrimmingSilence(CutReason.PAUSE)
        bufferedSamples >= maxPhrase -> cutAtQuietest()
        else -> null
    }

    private fun append(f: Frame) {
        frames.add(f)
        bufferedSamples += f.samples.size
        if (f.speech) speechSamples += f.samples.size
    }

    private fun pushPreRoll(f: Frame) {
        preRoll.addLast(f)
        preRollSamples += f.samples.size
        while (preRoll.size > 1 && preRollSamples - preRoll.first().samples.size >= preRollMax) {
            preRollSamples -= preRoll.removeFirst().samples.size
        }
        if (preRollMax == 0L) {
            preRoll.clear()
            preRollSamples = 0
        }
    }

    private fun clearBuffer() {
        frames.clear()
        bufferedSamples = 0
        speechSamples = 0
        silenceRun = 0
    }

    /** Emits the whole buffer minus trailing silence beyond [trailingKeep]. */
    private fun emitTrimmingSilence(reason: CutReason): Phrase? {
        // Walk back over the trailing silence, keeping up to trailingKeep of it.
        var keepEnd = frames.size
        var dropped = 0L
        val droppable = (silenceRun - trailingKeep).coerceAtLeast(0)
        while (keepEnd > 0 && !frames[keepEnd - 1].speech &&
            dropped + frames[keepEnd - 1].samples.size <= droppable
        ) {
            keepEnd--
            dropped += frames[keepEnd].samples.size
        }
        val kept = frames.subList(0, keepEnd).toList()
        val droppedFrames = frames.subList(keepEnd, frames.size).toList()
        val speech = speechSamples
        val start = bufferStart
        clearBuffer()
        // The dropped tail silence is the next phrase's natural pre-roll.
        droppedFrames.forEach { pushPreRoll(it) }
        return build(kept, start, reason, speech)
    }

    /** Hard ceiling: cut after the quietest frame of the search window. */
    private fun cutAtQuietest(): Phrase? {
        var windowStartIdx = frames.size
        var windowSamples = 0L
        while (windowStartIdx > 1 && windowSamples < cutSearch) {
            windowStartIdx--
            windowSamples += frames[windowStartIdx].samples.size
        }
        // Prefer non-speech frames, then the lowest energy; the last frame is
        // excluded so the head never swallows the whole buffer (the remainder
        // must carry at least one frame of context forward).
        var best = -1
        var bestScore = Float.MAX_VALUE
        for (i in windowStartIdx until frames.size - 1) {
            val fr = frames[i]
            val score = fr.energy + if (fr.speech) SPEECH_PENALTY else 0f
            if (score <= bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best < 0) best = frames.size - 2
        val head = frames.subList(0, best + 1).toList()
        val tail = frames.subList(best + 1, frames.size).toList()
        val headSamples = head.sumOf { it.samples.size.toLong() }
        val headSpeech = head.filter { it.speech }.sumOf { it.samples.size.toLong() }
        val start = bufferStart

        clearBuffer()
        bufferStart = start + headSamples
        tail.forEach { append(it) }
        silenceRun = tail.takeLastWhile { !it.speech }.sumOf { it.samples.size.toLong() }
        // A remainder that holds no speech at all is not a phrase in progress.
        if (speechSamples == 0L) {
            val silent = frames.toList()
            clearBuffer()
            silent.forEach { pushPreRoll(it) }
        }
        return build(head, start, CutReason.MAX_LENGTH, headSpeech)
    }

    private fun build(kept: List<Frame>, start: Long, reason: CutReason, speech: Long): Phrase? {
        if (speech < minSpeech || kept.isEmpty()) return null
        val total = kept.sumOf { it.samples.size }
        val out = FloatArray(total)
        var pos = 0
        for (f in kept) {
            f.samples.copyInto(out, pos)
            pos += f.samples.size
        }
        return Phrase(out, start, reason, speech)
    }

    companion object {
        /** Added to a speech frame's RMS when scoring cut points (RMS is in 0..1). */
        private const val SPEECH_PENALTY = 10f

        fun rms(frame: FloatArray): Float {
            if (frame.isEmpty()) return 0f
            var sum = 0.0
            for (s in frame) sum += s * s
            return sqrt(sum / frame.size).toFloat()
        }
    }
}
