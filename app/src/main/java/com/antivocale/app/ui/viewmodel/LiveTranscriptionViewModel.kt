package com.antivocale.app.ui.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antivocale.app.audio.live.PhraseSegmenter
import com.antivocale.app.audio.live.SileroSpeechDetector
import com.antivocale.app.audio.live.SpeechDetector
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.service.InferenceService
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.SherpaModelManager
import com.antivocale.app.transcription.TranscriptionOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Live mode: microphone -> streaming VAD -> [PhraseSegmenter] (5..10 s
 * phrases) -> one offline decode per phrase. GigaAM is preferred whenever it
 * is installed (fast offline RNNT, Russian); otherwise the user's selected
 * backend is used, since any backend's transcribeAudio accepts a phrase.
 *
 * Two coroutines: the capture loop (AudioRecord reads + VAD + cuts) never
 * waits on the recognizer, and the decode loop drains the phrase queue in
 * order. A slow model therefore grows the queue ([LiveUiState.pendingPhrases])
 * instead of dropping audio.
 */
@HiltViewModel
class LiveTranscriptionViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val orchestrator: TranscriptionOrchestrator,
    private val preferencesManager: PreferencesManager,
) : ViewModel() {

    enum class Status { IDLE, LOADING, LISTENING, FINISHING, ERROR }

    enum class ErrorKind { NO_MODEL, MICROPHONE, BUSY, DECODE }

    data class LivePhrase(
        val startSeconds: Float,
        val durationSeconds: Float,
        val text: String,
    )

    data class LiveUiState(
        val status: Status = Status.IDLE,
        val backendName: String? = null,
        val phrases: List<LivePhrase> = emptyList(),
        val pendingPhrases: Int = 0,
        val speaking: Boolean = false,
        /** Seconds held in the phrase being accumulated (0 when between phrases). */
        val bufferedSeconds: Float = 0f,
        val level: Float = 0f,
        val error: ErrorKind? = null,
        val errorDetail: String? = null,
    ) {
        val fullText: String get() = phrases.joinToString(" ") { it.text }.trim()
        val isRunning: Boolean get() = status == Status.LOADING || status == Status.LISTENING || status == Status.FINISHING
    }

    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    @Volatile private var stopRequested = false

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        // A session whose capture failed can still be draining its queue.
        if (_state.value.isRunning || sessionJob?.isActive == true) return
        if (!hasMicPermission()) {
            fail(ErrorKind.MICROPHONE, null)
            return
        }
        stopRequested = false
        _state.update {
            it.copy(status = Status.LOADING, error = null, errorDetail = null, pendingPhrases = 0,
                speaking = false, bufferedSeconds = 0f, level = 0f)
        }
        val phrases = Channel<PhraseSegmenter.Phrase>(Channel.UNLIMITED)

        // Off the main thread: a cold model load and every decode are heavy native calls.
        sessionJob = viewModelScope.launch(Dispatchers.Default) {
            if (InferenceService.isTranscribing.value) {
                fail(ErrorKind.BUSY, null)
                return@launch
            }
            val backendId = resolveBackendId()
            val backend = orchestrator.ensureLiveBackend(appContext, backendId).getOrElse { e ->
                Log.e(TAG, "Live backend load failed: $backendId", e)
                fail(ErrorKind.NO_MODEL, e.message)
                return@launch
            }
            _state.update { it.copy(backendName = backend.displayName, status = Status.LISTENING) }

            launch { decodeLoop(backendId, phrases) }
            launch(Dispatchers.IO) {
                try {
                    captureLoop(phrases)
                } finally {
                    phrases.close()
                }
            }
        }
    }

    /** Stops capture; phrases already queued (and the pending tail) are still decoded. */
    fun stop() {
        if (!_state.value.isRunning) return
        stopRequested = true
        if (_state.value.status == Status.LOADING) {
            // Nothing captured yet: cancel outright.
            sessionJob?.cancel()
            _state.update { it.copy(status = Status.IDLE) }
            return
        }
        _state.update { it.copy(status = Status.FINISHING, speaking = false, level = 0f) }
    }

    fun clear() {
        if (_state.value.isRunning) return
        _state.update { LiveUiState(backendName = it.backendName) }
    }

    /** GigaAM when installed (the live mode's primary target), else the user's backend. */
    private suspend fun resolveBackendId(): String {
        val gigaam = runCatching {
            SherpaModelManager.of(BuiltInBackendIds.GIGAAM).resolveActiveModelPath(appContext)
        }.getOrNull()
        return if (gigaam != null) BuiltInBackendIds.GIGAAM else preferencesManager.transcriptionBackend.first()
    }

    @SuppressLint("MissingPermission") // checked in start(); a revoke mid-session throws, caught below
    private suspend fun captureLoop(out: Channel<PhraseSegmenter.Phrase>) {
        val sampleRate = SileroSpeechDetector.SAMPLE_RATE
        val frameSize = SileroSpeechDetector.WINDOW_SIZE
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, frameSize * 2 * 8),
            ).also { check(it.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" } }
        } catch (e: Exception) {
            Log.e(TAG, "Microphone unavailable", e)
            fail(ErrorKind.MICROPHONE, e.message)
            return
        }

        val detector = SpeechDetector.create(appContext)
        val segmenter = PhraseSegmenter(PhraseSegmenter.Config(sampleRate = sampleRate))
        val pcm = ShortArray(frameSize)
        val frame = FloatArray(frameSize)
        var lastUiUpdate = 0L
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive && !stopRequested) {
                val read = record.read(pcm, 0, frameSize)
                if (read <= 0) {
                    if (read < 0) error("AudioRecord.read failed: $read")
                    continue
                }
                val samples = if (read == frameSize) frame else FloatArray(read)
                for (i in 0 until read) samples[i] = pcm[i] / 32768f
                val speech = detector.isSpeech(samples)
                segmenter.accept(samples, speech)?.let { enqueue(out, it) }

                val now = System.currentTimeMillis()
                if (now - lastUiUpdate >= UI_UPDATE_MS) {
                    lastUiUpdate = now
                    val level = (PhraseSegmenter.rms(samples) * LEVEL_GAIN).coerceIn(0f, 1f)
                    _state.update {
                        it.copy(speaking = speech, bufferedSeconds = segmenter.bufferedSeconds, level = level)
                    }
                }
            }
            segmenter.flush()?.let { enqueue(out, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            segmenter.flush()?.let { enqueue(out, it) }
            fail(ErrorKind.MICROPHONE, e.message)
        } finally {
            runCatching { record.stop() }
            record.release()
            detector.close()
            _state.update { it.copy(speaking = false, bufferedSeconds = 0f, level = 0f) }
        }
    }

    private fun enqueue(out: Channel<PhraseSegmenter.Phrase>, phrase: PhraseSegmenter.Phrase) {
        Log.d(TAG, "Phrase ${"%.1f".format(phrase.durationSeconds(SileroSpeechDetector.SAMPLE_RATE))}s " +
            "at ${"%.1f".format(phrase.startSeconds(SileroSpeechDetector.SAMPLE_RATE))}s (${phrase.reason})")
        if (out.trySend(phrase).isSuccess) {
            _state.update { it.copy(pendingPhrases = it.pendingPhrases + 1) }
        }
    }

    private suspend fun decodeLoop(backendId: String, input: Channel<PhraseSegmenter.Phrase>) {
        val sampleRate = SileroSpeechDetector.SAMPLE_RATE
        for (phrase in input) {
            // Never swap the resident model under a running file transcription:
            // wait for it to finish (capture keeps queueing meanwhile).
            if (InferenceService.isTranscribing.value) {
                InferenceService.isTranscribing.first { !it }
            }
            val result = orchestrator.ensureLiveBackend(appContext, backendId)
                .mapCatching { backend ->
                    backend.transcribeAudio(phrase.samples, sampleRate, "").getOrThrow()
                }
            _state.update { s ->
                val text = result.getOrNull()?.text?.trim().orEmpty()
                val phrases = if (text.isNotEmpty()) {
                    s.phrases + LivePhrase(
                        startSeconds = phrase.startSeconds(sampleRate),
                        durationSeconds = phrase.durationSeconds(sampleRate),
                        text = text,
                    )
                } else s.phrases
                s.copy(phrases = phrases, pendingPhrases = (s.pendingPhrases - 1).coerceAtLeast(0))
            }
            result.exceptionOrNull()?.let { e ->
                Log.w(TAG, "Phrase decode failed", e)
                _state.update { it.copy(error = ErrorKind.DECODE, errorDetail = e.message) }
            }
        }
        _state.update {
            if (it.status == Status.ERROR) it else it.copy(status = Status.IDLE, pendingPhrases = 0)
        }
    }

    private fun fail(kind: ErrorKind, detail: String?) {
        stopRequested = true
        _state.update { it.copy(status = Status.ERROR, error = kind, errorDetail = detail, speaking = false, level = 0f) }
    }

    override fun onCleared() {
        stopRequested = true
        sessionJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val TAG = "LiveTranscription"
        const val UI_UPDATE_MS = 100L
        const val LEVEL_GAIN = 8f
    }
}
