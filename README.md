# EchoMind

**A private second brain for Android: it listens to your day, transcribes it in English, Hindi or Hinglish, and turns it into living notes, reminders, a daily journal and a memory you can ask anything.**

Native Kotlin + Jetpack Compose. Runs fully on the phone, or uses cloud AI for the features you choose — and always shows when data leaves.

## Highlights

| | |
|---|---|
| All-day listening | A microphone foreground service that survives the screen being off; silence is never stored; audio is deleted once transcribed. |
| Speech in your languages | Google's on-device recogniser (no download), open models (Whisper, Parakeet, Omnilingual) or cloud engines (Sarvam for Hindi, Gemini, ElevenLabs, Deepgram, Groq). |
| Calls too | The mic is blocked during calls, so EchoMind imports your dialer's call recordings afterwards and labels them with the contact. |
| Living notes | Notes per topic that update themselves with small, traceable edits — every change has a reason and links to the moment it came from. |
| Memory | People, facts, plans and preferences, reviewed nightly: kept, updated, replaced (with history) or let go. |
| Tasks & reminders | Commitments land in a review inbox; accepted ones get exact reminders with Done / Snooze. "Remind me…" said out loud works within a minute. |
| Journal | An evening diary with loose ends, plans for tomorrow and a few gentle things to improve, plus daily numbers. |
| Ask | "What did Rahul say about the invoice?" — answers only from your own data, with citations that open the exact transcript line. |
| Your choice of AI | On-device Gemma 4 / Qwen 3.5 via LiteRT-LM, or Gemini, Claude, OpenAI-compatible (Groq, OpenRouter, Ollama) with your own key. |
| Kind to the phone | A governor paces work by your utilisation setting, heat forecasts, battery temperature and charge. |

## Privacy

- **Local only** mode: nothing personal ever leaves the phone (enforced twice: routing and the network gate).
- Cloud per feature, with first-use consent, an amber banner whenever data is sent, and a Data ledger of every request.
- API keys encrypted with an Android Keystore key; backups disabled.

## Build

```bash
./gradlew :app:assembleRelease
```

Requires JDK 17 and the Android SDK (compileSdk 37). The sherpa-onnx AAR lives in `app/libs/`. The release build is signed with the debug key unless you configure your own.

## Docs

- [Architecture](docs/ARCHITECTURE.md)
- Research: [speech](docs/research/speech.md) · [AI & memory](docs/research/ai-memory.md) · [Android platform](docs/research/platform.md) · [UX](docs/research/ux.md) · [cloud APIs](docs/research/cloud-apis.md)

## License

MIT © 2026 Naman
