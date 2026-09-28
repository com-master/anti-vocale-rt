# Live dictation (phrase-by-phrase decoding)

Near-real-time dictation from the microphone on top of the OFFLINE
recognizers, GigaAM v3 first of all. There is no streaming decoder involved:
the stream is cut into phrases and every phrase is decoded as a short clip,
so the text trails the voice by roughly one phrase (5..10 s).

Entry point: the microphone button above the "+" FAB on the History tab opens
`ui/live/LiveTranscriptionActivity`.

## Pipeline

```
AudioRecord (16 kHz mono, 512-sample frames, VOICE_RECOGNITION source)
  -> SpeechDetector        (streaming Silero VAD, energy fallback)
  -> PhraseSegmenter       (where to cut: pure Kotlin, unit-tested)
  -> Channel<Phrase>       (unbounded: a slow model grows the queue, audio is never dropped)
  -> TranscriptionOrchestrator.ensureLiveBackend + backend.transcribeAudio (one decode per phrase)
```

Capture and decoding run in separate coroutines (`LiveTranscriptionViewModel`),
so a decode never blocks the microphone.

## Cut rules (`audio/live/PhraseSegmenter.kt`)

| Rule | Default | Effect |
|------|---------|--------|
| Long pause | 0.8 s | Ends the phrase whatever its length (a short sentence is not held back) |
| Short pause, once the phrase has `minPhraseSeconds` | 0.3 s after 5 s | The natural phrase boundary inside the 5..10 s window |
| Hard ceiling | 10 s | Cut at the quietest frame of the last 3 s, the remainder starts the next phrase (no overlap, no duplicated words) |
| Pre-roll / trailing silence kept | 0.25 s / 0.25 s | The first syllable's attack is not clipped; long silences are not decoded |
| Minimum speech | 0.3 s | Clicks and coughs are discarded |

GigaAM's catalog `tailPadSeconds` (1 s of silence) is still appended by
`SherpaBackend` to every phrase, exactly as for file chunks.

## Model choice

GigaAM when installed (`SherpaModelManager.of("gigaam")` resolves a valid
directory), otherwise the backend selected in Settings. Any backend works
through `transcribeAudio`; the offline transducers (GigaAM, Parakeet) are the
ones fast enough for the 5..10 s latency on a phone.

## Coexistence with file transcription

The live screen refuses to start while `InferenceService` is transcribing, and
the decode loop waits for a running file transcription to finish before
decoding the next phrase (capture keeps queueing meanwhile), so the resident
model is never swapped under a running file request.
`ensureLiveBackend` is called before every phrase, so a keep-alive idle unload
or a file request that loaded another model in between is recovered by a reload.

## Speaker roles

The "Roles" chip (on by default, remembered) labels every phrase with a
speaker. Each decoded phrase is embedded with the titanet model the file
diarization already uses (`DiarizationModels`, downloaded on first use, about
47 MB) and clustered online by `OnlineSpeakerTracker`: cosine similarity
against each speaker's running centroid, a new speaker below 0.5, at most 8.
With speaker identification enabled in Settings, enrolled voiceprints name the
clusters (`SpeakerNamer.bestMatch`, the shared 0.60 line); otherwise the
labels are the app's usual `SPEAKER N`.

A phrase carries ONE label, so with roles on the segmenter closes a phrase at
a short pause after 3 s instead of 5 s (speaker turns usually sit on such
pauses). Two voices with no pause between them inside one phrase still get
one label.

## Copy and autosave

- Copy: plain text, speaker turns on their own lines.
- Copy with time / Share: one line per phrase with the wall-clock start,
  `[14:03:12] SPEAKER 1: text`.
- Every session is a History row (decode path `live_phrases`), upserted after
  each decoded phrase with the phrases as timed cues, so History, its exports
  and the speaker rendering work as for a file. At the end of the session the
  transcript is also written to the auto-save folder when one is configured,
  in the chosen export format (TXT / timed TXT / SRT / VTT).
- Each Start begins a new session and a new row.

## Limits

- Foreground only: the screen keeps itself on while listening; Android blocks
  microphone capture for background apps without a microphone foreground
  service, which this mode does not start. Closing the screen mid-session
  keeps what was decoded (History row, folder file) and drops the queue.
