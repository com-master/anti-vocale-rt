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

## Limits

- Foreground only: the screen keeps itself on while listening; Android blocks
  microphone capture for background apps without a microphone foreground
  service, which this mode does not start.
- Live results are not written to History; use Copy or Share.
