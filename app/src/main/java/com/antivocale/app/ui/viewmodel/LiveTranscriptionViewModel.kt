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
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antivocale.app.R
import com.antivocale.app.audio.live.PhraseSegmenter
import com.antivocale.app.audio.live.SileroSpeechDetector
import com.antivocale.app.audio.live.SpeechDetector
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.ProcessingContextConverter
import com.antivocale.app.data.local.TimedSegmentsConverter
import com.antivocale.app.data.local.toEntity
import com.antivocale.app.service.InferenceService
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.ProcessingContext
import com.antivocale.app.transcription.SherpaModelManager
import com.antivocale.app.transcription.TimedSegment
import com.antivocale.app.transcription.TranscriptionOrchestrator
import com.antivocale.app.transcription.diarization.DiarizationModels
import com.antivocale.app.transcription.diarization.OnlineSpeakerTracker
import com.antivocale.app.transcription.diarization.SpeakerEmbeddings
import com.antivocale.app.transcription.diarization.SpeakerIdentityStore
import com.antivocale.app.ui.live.LiveTranscriptFormat
import com.antivocale.app.util.TranscriptFileSaver
import com.antivocale.app.util.TranscriptSignature
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * Live mode: microphone -> streaming VAD -> [PhraseSegmenter] (phrases of a
 * few seconds, 10 s max) -> one offline decode per phrase. GigaAM is
 * preferred whenever it is installed (fast offline RNNT, Russian); otherwise
 * the user's selected backend is used.
 *
 * Speaker roles (optional, on by default): each decoded phrase is embedded
 * with the titanet model the file diarization uses and clustered online
 * ([OnlineSpeakerTracker]); enrolled voiceprints name the clusters when
 * speaker identification is enabled in Settings.
 *
 * Every session is saved as it goes: the History row is upserted after each
 * phrase (so a killed app loses at most the phrase in flight), and at the end
 * the transcript is written to the auto-save folder when one is configured.
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
    private val logDao: LogDao,
    private val speakerIdentityStore: SpeakerIdentityStore,
) : ViewModel() {

    enum class Status { IDLE, LOADING, LISTENING, FINISHING, ERROR }

    enum class ErrorKind { NO_MODEL, MICROPHONE, BUSY, DECODE, ROLES_UNAVAILABLE }

    data class LiveUiState(
        val status: Status = Status.IDLE,
        val backendName: String? = null,
        /** One cue per decoded phrase; times are offsets from the session start. */
        val segments: List<TimedSegment> = emptyList(),
        val sessionStartWallMs: Long = 0L,
        val pendingPhrases: Int = 0,
        val speaking: Boolean = false,
        /** Seconds held in the phrase being accumulated (0 when between phrases). */
        val bufferedSeconds: Float = 0f,
        val level: Float = 0f,
        val rolesEnabled: Boolean = true,
        /** True while the speaker model is downloaded/loaded at session start. */
        val preparingRoles: Boolean = false,
        val speakerCount: Int = 0,
        /** The session's History row exists (it is updated after every phrase). */
        val savedToHistory: Boolean = false,
        /** Display name of the file written to the auto-save folder at the end. */
        val savedFileName: String? = null,
        val error: ErrorKind? = null,
        val errorDetail: String? = null,
    ) {
        val hasText: Boolean get() = segments.isNotEmpty()
        val plainText: String get() = LiveTranscriptFormat.plain(segments)
        val timedText: String get() = LiveTranscriptFormat.timed(sessionStartWallMs, segments)
        val isRunning: Boolean get() = status == Status.LOADING || status == Status.LISTENING || status == Status.FINISHING
    }

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(LiveUiState(rolesEnabled = prefs.getBoolean(KEY_ROLES, true)))
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    @Volatile private var stopRequested = false

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun setRolesEnabled(enabled: Boolean) {
        if (_state.value.isRunning) return
        prefs.edit { putBoolean(KEY_ROLES, enabled) }
        _state.update { it.copy(rolesEnabled = enabled) }
    }

    /** Starts a NEW session (the previous one is already saved in History). */
    fun start() {
        // A session whose capture failed can still be draining its queue.
        if (_state.value.isRunning || sessionJob?.isActive == true) return
        if (!hasMicPermission()) {
            fail(ErrorKind.MICROPHONE, null)
            return
        }
        stopRequested = false
        val roles = _state.value.rolesEnabled
        _state.update {
            LiveUiState(
                status = Status.LOADING,
                backendName = it.backendName,
                rolesEnabled = roles,
                sessionStartWallMs = System.currentTimeMillis(),
            )
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
            _state.update { it.copy(backendName = backend.displayName) }
            val speakers = if (roles) prepareSpeakers() else null
            _state.update { it.copy(status = Status.LISTENING, preparingRoles = false) }

            val session = Session(
                taskId = "live-${UUID.randomUUID()}",
                rowId = UUID.randomUUID().toString(),
                backendId = backendId,
                modelName = backend.displayName,
            )
            launch { decodeLoop(session, phrases, speakers) }
            launch(Dispatchers.IO) {
                try {
                    captureLoop(phrases, roles)
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
            _state.update { it.copy(status = Status.IDLE, preparingRoles = false) }
            return
        }
        _state.update { it.copy(status = Status.FINISHING, speaking = false, level = 0f) }
    }

    /** GigaAM when installed (the live mode's primary target), else the user's backend. */
    private suspend fun resolveBackendId(): String {
        val gigaam = runCatching {
            SherpaModelManager.of(BuiltInBackendIds.GIGAAM).resolveActiveModelPath(appContext)
        }.getOrNull()
        return if (gigaam != null) BuiltInBackendIds.GIGAAM else preferencesManager.transcriptionBackend.first()
    }

    /** Speaker model + tracker; null (roles off for this session) when they cannot load. */
    private class Speakers(val embeddings: SpeakerEmbeddings, val tracker: OnlineSpeakerTracker)

    private suspend fun prepareSpeakers(): Speakers? {
        _state.update { it.copy(preparingRoles = true) }
        return runCatching {
            // First use downloads the shared diarization models (~47 MB), the
            // same files the History speaker labels use.
            DiarizationModels.ensureDownloaded(appContext).getOrThrow()
            val threads = preferencesManager.threadCount.first()
            val embeddings = SpeakerEmbeddings.create(DiarizationModels.embeddingFile(appContext), threads).getOrThrow()
            val identities = if (preferencesManager.speakerIdEnabled.first()) {
                runCatching { speakerIdentityStore.list() }.getOrDefault(emptyList())
            } else emptyList()
            Speakers(embeddings, OnlineSpeakerTracker(identities = identities))
        }.onFailure { e ->
            Log.w(TAG, "Speaker roles unavailable, continuing without them", e)
            _state.update { it.copy(error = ErrorKind.ROLES_UNAVAILABLE, errorDetail = e.message) }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission") // checked in start(); a revoke mid-session throws, caught below
    private suspend fun captureLoop(out: Channel<PhraseSegmenter.Phrase>, roles: Boolean) {
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
        // With roles on, a phrase closes at a short pause after 3 s instead of
        // 5 s: speaker turns usually sit on such pauses, and a phrase mixing
        // two voices can carry only one label.
        val config = PhraseSegmenter.Config(
            sampleRate = sampleRate,
            minPhraseSeconds = if (roles) ROLES_MIN_PHRASE_SECONDS else PhraseSegmenter.Config().minPhraseSeconds,
        )
        val segmenter = PhraseSegmenter(config)
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

    /** Per-session bookkeeping owned by the decode loop. */
    private class Session(
        val taskId: String,
        val rowId: String,
        val backendId: String,
        val modelName: String,
        var decodeMs: Long = 0L,
        var phraseCount: Int = 0,
    )

    private suspend fun decodeLoop(
        session: Session,
        input: Channel<PhraseSegmenter.Phrase>,
        speakers: Speakers?,
    ) {
        val sampleRate = SileroSpeechDetector.SAMPLE_RATE
        try {
            for (phrase in input) {
                // Never swap the resident model under a running file transcription:
                // wait for it to finish (capture keeps queueing meanwhile).
                if (InferenceService.isTranscribing.value) {
                    InferenceService.isTranscribing.first { !it }
                }
                val started = System.currentTimeMillis()
                val result = orchestrator.ensureLiveBackend(appContext, session.backendId)
                    .mapCatching { backend ->
                        backend.transcribeAudio(phrase.samples, sampleRate, "").getOrThrow()
                    }
                val text = result.getOrNull()?.text?.trim().orEmpty()
                val assignment = if (text.isNotEmpty() && speakers != null) {
                    runCatching {
                        val embedding = speakers.embeddings.compute(phrase.samples, sampleRate)
                        speakers.tracker.assign(embedding, weight = phrase.speechSamples.toFloat() / sampleRate)
                    }.onFailure { Log.w(TAG, "Phrase embedding failed: ${it.message}") }.getOrNull()
                } else null
                session.decodeMs += System.currentTimeMillis() - started
                session.phraseCount++

                _state.update { s ->
                    val segments = if (text.isNotEmpty()) {
                        val startMs = (phrase.startSeconds(sampleRate) * 1000).toLong()
                        s.segments + TimedSegment(
                            startMs = startMs,
                            endMs = startMs + (phrase.durationSeconds(sampleRate) * 1000).toLong(),
                            text = text,
                            speaker = assignment?.speaker,
                            speakerName = assignment?.name,
                        )
                    } else s.segments
                    s.copy(
                        segments = segments,
                        speakerCount = speakers?.tracker?.speakerCount ?: 0,
                        pendingPhrases = (s.pendingPhrases - 1).coerceAtLeast(0),
                    )
                }
                result.exceptionOrNull()?.let { e ->
                    Log.w(TAG, "Phrase decode failed", e)
                    _state.update { it.copy(error = ErrorKind.DECODE, errorDetail = e.message) }
                }
                if (text.isNotEmpty()) saveToHistory(session)
            }
        } finally {
            speakers?.embeddings?.release()
            // The file export must survive the screen closing mid-finish.
            withContext(NonCancellable) { saveToFolder() }
            _state.update {
                if (it.status == Status.ERROR) it else it.copy(status = Status.IDLE, pendingPhrases = 0)
            }
        }
    }

    /** Upserts the session's History row (REPLACE on the same primary key). */
    private suspend fun saveToHistory(session: Session) {
        val s = _state.value
        if (s.segments.isEmpty()) return
        runCatching {
            logDao.insert(
                LogEntry(
                    id = session.rowId,
                    taskId = session.taskId,
                    timestamp = s.sessionStartWallMs,
                    type = LogEntry.Type.AUDIO,
                    status = LogEntry.Status.SUCCESS,
                    result = LiveTranscriptFormat.joined(s.segments),
                    durationMs = session.decodeMs,
                    audioDurationSeconds = s.segments.last().endMs / 1000.0,
                    modelName = session.modelName,
                    segments = TimedSegmentsConverter.toJson(s.segments),
                    processingContext = ProcessingContextConverter.toJson(
                        ProcessingContext(
                            decodePath = ProcessingContext.DECODE_PATH_LIVE,
                            totalChunks = session.phraseCount,
                            backendId = session.backendId,
                        )
                    ),
                ).toEntity()
            )
        }.onSuccess {
            if (!s.savedToHistory) _state.update { it.copy(savedToHistory = true) }
        }.onFailure { Log.w(TAG, "History save failed", it) }
    }

    /** End of session: the transcript file in the user's auto-save folder, when one is set. */
    private suspend fun saveToFolder() {
        val s = _state.value
        if (s.segments.isEmpty()) return
        val folder = preferencesManager.outputFolderUri.first()
        if (folder.isNullOrBlank()) return
        val signature = TranscriptSignature.effectiveSpec(
            preferencesManager, appContext.getString(R.string.signature_default_text))
        val name = withContext(Dispatchers.IO) {
            TranscriptFileSaver.saveAuto(
                appContext,
                folder,
                preferencesManager.transcriptExportFormat.first(),
                LiveTranscriptFormat.joined(s.segments),
                s.segments,
                failedChunkCount = 0,
                signature = signature.text,
                signaturePosition = signature.position,
            )
        }
        if (name != null) {
            Log.i(TAG, "Live session saved to folder: $name")
            _state.update { it.copy(savedFileName = name) }
        }
    }

    private fun fail(kind: ErrorKind, detail: String?) {
        stopRequested = true
        _state.update {
            it.copy(status = Status.ERROR, error = kind, errorDetail = detail, speaking = false,
                level = 0f, preparingRoles = false)
        }
    }

    override fun onCleared() {
        stopRequested = true
        sessionJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val TAG = "LiveTranscription"
        const val PREFS = "live_mode"
        const val KEY_ROLES = "roles_enabled"
        const val UI_UPDATE_MS = 100L
        const val LEVEL_GAIN = 8f
        const val ROLES_MIN_PHRASE_SECONDS = 3f
    }
}
