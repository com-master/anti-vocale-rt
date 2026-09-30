package com.antivocale.app.audio.live

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Live mode loudness handling for quiet speakers (pure Kotlin, unit-tested).
 *
 * Two independent stages:
 *  - [applyGain]: the user's microphone gain, applied to every captured frame
 *    BEFORE the speech detector, so quiet speech can open a phrase at all;
 *  - [normalizePhrase]: per-phrase automatic leveling right before decoding,
 *    so the recognizer always sees speech at a steady level whatever the
 *    distance to the microphone.
 */
object AudioLevel {

    /** Speech RMS the recognizer is fed (about -20 dBFS, typical close-mic dictation). */
    const val TARGET_RMS = 0.1f
    /** Leveling never amplifies more than this (+20 dB): beyond it, it is mostly noise. */
    const val MAX_NORMALIZE_GAIN = 10f
    /** Peak ceiling after leveling, so the loudest syllable is not clipped. */
    const val PEAK_CEILING = 0.95f

    const val MIN_GAIN = 1f
    const val MAX_GAIN = 10f

    /**
     * Multiplies [frame] in place by [gain]; samples beyond 0.8 are compressed
     * with a tanh knee instead of hard-clipped, so a loud burst after raising
     * the gain distorts gently instead of turning into square waves.
     */
    fun applyGain(frame: FloatArray, gain: Float) {
        if (gain == 1f) return
        for (i in frame.indices) frame[i] = softClip(frame[i] * gain)
    }

    internal fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        // Maps (KNEE, inf) smoothly onto (KNEE, 1).
        val over = (a - KNEE) / (1f - KNEE)
        val y = KNEE + (1f - KNEE) * tanh(over.toDouble()).toFloat()
        return if (x < 0) -y else y
    }

    /**
     * Returns [samples] leveled to [TARGET_RMS] (the input is not modified).
     * The RMS is measured over the louder half of the 20 ms blocks, so pauses
     * and the silence pre-roll do not make a normal phrase look quiet. Never
     * attenuates, never exceeds [MAX_NORMALIZE_GAIN] or the [PEAK_CEILING].
     */
    fun normalizePhrase(samples: FloatArray, blockSize: Int = 320): FloatArray {
        if (samples.isEmpty()) return samples
        val speechRms = loudBlocksRms(samples, blockSize)
        if (speechRms <= 0f) return samples
        var peak = 0f
        for (s in samples) peak = maxOf(peak, abs(s))
        val gain = minOf(TARGET_RMS / speechRms, MAX_NORMALIZE_GAIN, if (peak > 0f) PEAK_CEILING / peak else MAX_NORMALIZE_GAIN)
        if (gain <= 1.05f) return samples
        return FloatArray(samples.size) { samples[it] * gain }
    }

    /** RMS over the louder half of fixed blocks. */
    internal fun loudBlocksRms(samples: FloatArray, blockSize: Int): Float {
        val energies = ArrayList<Double>(samples.size / blockSize + 1)
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + blockSize, samples.size)
            var sum = 0.0
            for (j in i until end) sum += samples[j] * samples[j]
            energies.add(sum / (end - i))
            i = end
        }
        energies.sortDescending()
        val loud = energies.subList(0, maxOf(1, energies.size / 2))
        return sqrt(loud.average()).toFloat()
    }

    /**
     * Speech-detector threshold for a 0..1 sensitivity: 0 -> 0.7 (only clear,
     * loud speech), 1 -> 0.2 (whispers, but also more false triggers on noise).
     */
    fun vadThreshold(sensitivity: Float): Float = 0.7f - 0.5f * sensitivity.coerceIn(0f, 1f)

    private const val KNEE = 0.8f
}
