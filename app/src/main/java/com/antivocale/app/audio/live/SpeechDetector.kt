package com.antivocale.app.audio.live

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Live mode: per-frame speech/non-speech decision feeding [PhraseSegmenter].
 * Implementations keep their own state across frames (hysteresis).
 */
interface SpeechDetector : AutoCloseable {
    fun isSpeech(frame: FloatArray): Boolean
    override fun close() {}

    companion object {
        private const val TAG = "SpeechDetector"

        /**
         * Silero VAD (the bundled model), falling back to energy when it cannot
         * load. [sensitivity] 0..1 maps to the Silero threshold through
         * [AudioLevel.vadThreshold] (higher = quieter speech counts).
         */
        fun create(context: Context, sensitivity: Float = DEFAULT_SENSITIVITY, threads: Int = 1): SpeechDetector =
            runCatching { SileroSpeechDetector(context, threads, AudioLevel.vadThreshold(sensitivity)) }
                .onFailure { Log.w(TAG, "Silero VAD unavailable, using the energy detector", it) }
                .getOrElse {
                    // Same direction for the fallback: 0.02 (insensitive) .. 0.004 (sensitive).
                    EnergySpeechDetector(minThreshold = 0.02f - 0.016f * sensitivity.coerceIn(0f, 1f))
                }

        /** 0.6 -> Silero threshold 0.4: a bit more permissive than the file path's 0.5. */
        const val DEFAULT_SENSITIVITY = 0.6f
    }
}

/**
 * Streaming Silero VAD through sherpa-onnx (the same bundled model the file
 * path's [com.antivocale.app.audio.VadProcessor] uses). The VAD's own silence
 * hysteresis is kept short: pause lengths are the segmenter's decision.
 */
class SileroSpeechDetector(context: Context, threads: Int, threshold: Float = 0.5f) : SpeechDetector {
    private val vad = Vad(
        context.assets,
        VadModelConfig().apply {
            sileroVadModelConfig = SileroVadModelConfig(
                model = MODEL_PATH,
                threshold = threshold,
                minSilenceDuration = 0.1f,
                minSpeechDuration = 0.1f,
                windowSize = WINDOW_SIZE,
                maxSpeechDuration = 60f,
            )
            tenVadModelConfig = TenVadModelConfig()
            sampleRate = SAMPLE_RATE
            numThreads = threads
            provider = "cpu"
            debug = false
        }
    )

    override fun isSpeech(frame: FloatArray): Boolean {
        vad.acceptWaveform(frame)
        val speech = vad.isSpeechDetected()
        // Segments are not used here (the segmenter owns the cuts); drain the
        // queue so it does not grow for the whole session.
        while (!vad.empty()) vad.pop()
        return speech
    }

    override fun close() = vad.release()

    companion object {
        const val MODEL_PATH = "models/silero_vad.int8.onnx"
        const val SAMPLE_RATE = 16_000
        /** Silero's native window at 16 kHz (32 ms); the capture loop reads this size. */
        const val WINDOW_SIZE = 512
    }
}

/**
 * Fallback detector: RMS against an adaptive noise floor, with a short hangover
 * so word-internal dips do not count as pauses.
 */
class EnergySpeechDetector(
    private val minThreshold: Float = 0.01f,
    private val floorRatio: Float = 3f,
    private val hangoverFrames: Int = 4,
) : SpeechDetector {
    private var noiseFloor = minThreshold / floorRatio
    private var hangover = 0

    override fun isSpeech(frame: FloatArray): Boolean {
        val rms = PhraseSegmenter.rms(frame)
        val loud = rms > maxOf(minThreshold, noiseFloor * floorRatio)
        if (!loud) {
            // Track the floor on quiet frames only (slow rise, fast fall).
            noiseFloor = if (rms < noiseFloor) rms * 0.5f + noiseFloor * 0.5f else noiseFloor * 0.95f + rms * 0.05f
        }
        hangover = if (loud) hangoverFrames else (hangover - 1).coerceAtLeast(0)
        return loud || hangover > 0
    }
}
