# EchoMind (native) — architecture

A native Kotlin + Jetpack Compose rewrite of EchoMind. One process, one long-running foreground service, plain constructor wiring (`Graph` in `App.kt`), Room for everything durable. Research behind every choice is in `docs/research/`.

## The pipeline

```
 mic ──► Recorder ──► 2-min WAV windows ──► audio queue (Room)
          │  (silent windows deleted unheard)        │
          │                                           ▼
 MicGuard: call / other app → gap + window cut   Transcriber ── engine per feature ──► utterances (+FTS4, transliterated key)
                                                      │  (Google on-device │ sherpa-onnx │ Sarvam │ Gemini │ ElevenLabs │ Deepgram │ Groq)
 dialer call recordings ──► CallImporter ──► queue    │  voice-print → "me" / S1, S2…
                                                      ▼
                                                 Sessionizer ──► conversations (gap / max length / one per call)
                                                      │
                          AI session mode decides ──► Enricher (one structured call per conversation part)
                                                      ├─ title · summary · mood · key points
                                                      ├─ people → entities (spelling-tolerant linking)
                                                      ├─ facts → Memory.reconcile (add / update / replace / skip; bi-temporal)
                                                      ├─ tasks → review inbox → reminders (exact alarms)
                                                      └─ note updates → living notes (bullet patches + revisions)
                                                      ▼
                                   evening: Journal (diary, loose ends, tomorrow, things to improve, metrics)
                                            + Memory.consolidate (profile, people summaries, archive, fade)
 Ask: hybrid retrieval (FTS on phonetic keys + EmbeddingGemma vectors + facts + notes, RRF + recency) → cited answer
```

Every unit of work waits for the **Governor** (duty cycle at the user's utilisation %, 20-min sustained-load break, thermal status + headroom forecast, battery temperature, battery floor, charging).

## Process & background model

| Piece | How it stays alive |
|---|---|
| Listening | `CaptureService`, FGS type `microphone`. Started only from user-visible contexts (app, notification action, QS tile via trampoline activity, widget, shortcut). Pause releases the mic but keeps the service, so resume needs no new tap. No app wake lock while recording (audioserver holds one). |
| Processing after stop | Same service as `mediaProcessing` (6 h/day budget, `onTimeout` stops cleanly), partial wake lock only while working. |
| No service running | `ProcessWorker` (WorkManager, battery-not-low, optional charging) drains the queue in ≤9-min runs. |
| Silent failure guard | `WatchdogWorker` every 15 min: wanted-but-not-listening → "tap to resume" notification; imports calls; schedules catch-up. Heartbeat gaps become timeline gaps. Exit reasons logged at start. |
| Reboot / update | `BootReceiver` re-arms reminders + daily alarms and posts "tap to resume" (Android forbids starting the mic from boot). |
| Calls | Mic is silenced during calls (detected via recording callback + audio mode). The dialer's recording is read from MediaStore (`READ_MEDIA_AUDIO`), decoded to 16 kHz WAV, transcribed as its own conversation with the contact name from the filename. |

## Memory, ground truth and provenance

- **Utterances are never rewritten by AI.** Everything derived (conversation, fact, task, note revision) stores the utterance ids it came from; the model cites `L1…Ln` line numbers and code maps them back.
- **Facts are bi-temporal**: replaced facts get `validTo` + `supersededBy`; archived ones keep a reason. Users can pin, archive, restore, delete, add.
- **Living notes** change only through small edits (add/update/remove a bullet) recorded as revisions with a reason; user-written lines are protected; notes can be locked.
- **Profile** (core memory, ≤ ~1200 chars) leads every prompt; refreshed nightly.

## Privacy

- Master switch `LOCAL_ONLY` / `CLOUD_ALLOWED`, and a provider per feature (`Feature` × `Providers`). In local-only mode, routing falls back to local engines **and** `Cloud.send` refuses — two independent guards.
- Every cloud request goes through `Cloud.send`: amber banner while in flight + a row in the data ledger (`egress` table).
- First cloud use per feature asks for consent, describing exactly what is sent.
- Keys: AES-GCM with an Android Keystore key. Backups and device transfer are disabled (`data_extraction_rules.xml`).
- Audio is deleted right after transcription (optional keep-N-hours for playback); dialer originals are never touched.

## AI providers

| Kind | Local | Cloud |
|---|---|---|
| Speech | Google on-device recogniser (fed our PCM at 3× via `EXTRA_AUDIO_SOURCE`); sherpa-onnx Whisper small/turbo, Parakeet v2, Omnilingual 300M (+ Silero VAD) | Sarvam saaras:v4, Gemini (general model + schema), ElevenLabs Scribe v2, Deepgram Nova-3, Groq Whisper |
| LLM | LiteRT-LM 0.18.1 with Gemma 4 E2B / Qwen 3.5 / Gemma 4 E4B, JSON-schema constrained decoding, unloaded after 3 idle min | Gemini (`responseJsonSchema`), Claude (official Java SDK, `output_config.format`, low effort), any OpenAI-compatible server (OpenAI, Groq, OpenRouter with ZDR, Ollama) |
| Embeddings | EmbeddingGemma 2 270M, 256-d Matryoshka, int8, brute-force cosine | — |
| Voice ID | 3D-Speaker ERes2Net via sherpa-onnx; owner enrolment + per-conversation clustering | — |

## UI

Compose + Material 3 Expressive (1.5.0-beta01), Navigation 3. Tabs: **Today** (listening card with live level + last lines, setup health, background work + ETA, review banner, coming up, latest conversations, journal), **Timeline** (day strip, conversations + honest gaps, full-text search), **Ask** (cited answers, sources sheet → exact transcript line, history), **Tasks** (Review / To do / Done, reminder presets & picker), **Library** (Notes, Memory, People, Journal, Data ledger). Settings cover providers & keys, models, listening, voice, calls, AI sessions, power & vivo, notifications, data.

Surfaces: ongoing notification (Pause 30 min, Add note via inline reply, Stop), reminders (Done, In 1 hour), morning brief, evening recap, grouped suggestions (≤ every 2 h, never at night), Quick Settings tile, Glance widget, dynamic shortcuts, share-to-EchoMind, assist app.

## Package map

```
capture/  Recorder, MicGuard, SpeechFinder (energy VAD), Wav, AudioDecoder, AudioInputs, CaptureService, CaptureController
stt/      SpeechEngine, AndroidSpeech, SherpaSpeech, VoiceId, CloudSpeech (Sarvam, ElevenLabs, Deepgram, Groq, Gemini)
ai/       Llm, LocalLlm (LiteRT-LM), CloudLlm (Gemini, OpenAI-compatible, Anthropic), Ai router, Prompts, Schema(s)
brain/    Brain (scheduler), Transcriber, Sessionizer, Enricher, Memory, Notes (+NotePatch), Tasks, Journal, Ask, Embedder, TextNorm, When, Vectors
calls/    CallImporter
models/   ModelCatalog, ModelManager (DownloadManager + tar.bz2 unpack)
notify/   Notifier, ReminderAlarms, receivers
work/     Governor, WatchdogWorker, ProcessWorker, DailyWorker
surfaces/ Quick Settings tile, Glance widget, StartListeningActivity
data/     Room entities, DAOs, Db
core/     Settings (+Feature, AiSession), Providers, Secrets, Cloud
ui/       theme, components, screens, Nav (routes), AppRoot
```
