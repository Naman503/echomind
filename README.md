<div align="center">

<img src="docs/images/icon.svg" width="112" height="112" alt="EchoMind icon: a sound wave on an indigo tile">

# EchoMind

**A private second brain that listens for you.**<br>
EchoMind transcribes your day on the phone and turns what was said into notes, tasks, people, plans and a journal.<br>Audio never leaves the device unless you ask it to.

[![CI](https://github.com/Naman503/echomind/actions/workflows/ci.yml/badge.svg)](https://github.com/Naman503/echomind/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Naman503/echomind?color=5361B8)](https://github.com/Naman503/echomind/releases)
![Android 12+](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)
![On-device AI](https://img.shields.io/badge/AI-on--device-22C55E)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

</div>

---

## 📑 Contents

[Why](#-why) · [Highlights](#-highlights) · [Features](#-features-in-detail) · [Install](#-install) · [Privacy](#-privacy) · [Phone health](#-phone-health-and-limits) · [How it works](#-how-it-works) · [Build](#-build-from-source) · [License](#-license)

## 💡 Why

Most of what matters in a day is said out loud: a promise to a friend, a name you'll forget, an idea while you're walking. It's gone by evening.

Cloud "AI recorders" solve that by uploading everything you hear. EchoMind does it on the phone instead:

- **Speech recognition, speaker grouping, sound tagging and the language model all run locally.** That includes Hindi, English and Hinglish.
- **Cloud AI is optional and per-feature.** When you turn it on, every request is written to a ledger you can read.
- **It respects the phone.** Background work pauses when the phone is hot, the battery is low or Battery Saver is on. You set every limit yourself.

## ✨ Highlights

| | |
|---|---|
| 🎙️ **Always-on capture** | Listens in the background, cuts silence, and transcribes with on-device speech models |
| 🧠 **Notes, tasks and a journal** | Turns conversations into notes, tasks with due dates, and a day journal with a productivity rating |
| 👥 **People memory** | Remembers names, roles, how you met, and who said what. Can recognise people by their voice. |
| 📅 **Calendar and plans** | A day/week/month calendar with your device events, event prep, and plans built from your memory |
| 💬 **Ask anything** | Chat with your memory, with thinking modes, citations, and optional private web search |
| 🗣️ **Natural read-aloud** | About 90 open voices (Kokoro, Supertonic, Kitten, Piper) plus system voices. Bluetooth-aware. |
| 🎵 **Songs** | Keeps clips of you singing, out of your notes, and can name the song with a poster, lyrics and links |
| 🩺 **Phone health** | Live temperature, thermal headroom and battery, with danger-zone sliders for every limit |
| 🧩 **Widgets** | Eight configurable home-screen widgets with real previews in the picker |

## 🔍 Features in detail

### Capture and transcription
- A foreground service with a rich status notification: what it's doing, battery temperature, and why it's paused.
- On-device speech recognition with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). Speakers are grouped and labelled, and a voiceprint of your own voice tells "you" from "others".
- Transcripts are shown newest first. Hindi in Devanagari is transliterated for search, so `rangrez` finds `रंगरेज़`.
- **Recordings library:** keep the audio of a conversation and choose how long to keep it. Clips are swept automatically when they expire.
- **Ambient timeline (optional):** a sound tagger notes speech, music, traffic, typing and other sounds, and shows them as a timeline of your day.

### Organising
- The AI writes notes, tasks, tags and folders, and suggests a folder tree.
- **People:** a profile for each person: role, organisation, circle, relation to you, how you met, and a running story. Links between people are tracked too.
- **Tasks:** shows Today, Upcoming and All, with completed tasks included. The AI can suggest "looks done" when you say you finished something.
- **Library:** tiles for every section, and all notes grouped by folder.

### Calendar, journal and check-ins
- A calendar with a journal per day, a productivity score with a reason, and your own rating.
- Reads your device calendar and prepares you for upcoming events from what you know about the people involved.
- **Plans:** month, week and day plans built from your memory and calendar.
- **Voice check-ins:** scheduled question-and-answer journaling. EchoMind asks, you answer out loud, and the answers go into the day's journal. Questions, schedule and voice are all configurable.

### Ask and AI
- Ask questions about your memory: "what did Rahul say about the trip?"
- **Providers:** on-device Gemma (LiteRT-LM), or your own key for Claude, Gemini or OpenAI. Keys are stored encrypted and never logged.
- **Quality setting** (fast / balanced / best) maps to each provider's thinking or reasoning level.
- **The assistant can act:** it can add tasks, plan your day, or change a whitelisted setting when you ask it to.
- **Private web search (optional):** names, emails and numbers are scrubbed from the query before it is sent. Wikipedia is the default provider.

### Read-aloud
- A voice library of open, natural voices: Kokoro, Supertonic (English and Hindi), Kitten, VITS/Piper. System voices from Google and Microsoft engines are listed too, if installed.
- Detects Bluetooth earbuds. If they aren't connected, it asks before playing on the speaker.
- Adjustable pace, pauses, pitch, loudness boost, and audio focus behaviour.

### Songs
- When you sing, EchoMind keeps that part of the recording as a separate clip. Song lyrics never become notes or tasks.
- **Song naming (optional, online):** it guesses on the phone, checks candidates against the iTunes catalogue and LRCLIB lyrics, and picks a song only when the lyrics clearly match. Then it adds the album, year, poster and links.
- Re-scan old recordings, rename songs yourself, or turn the feature off entirely.

### Insights and developer tools
- **Dashboard:** how much you talked versus others, per person, across the day.
- **Developer screen:** live engine state, the processing queue, Governor decisions, the cloud ledger, and the database.
- **Models screen:** download, switch and remove speech, voice and language models, with sizes shown.

### Widgets
Listen, Mic, Tasks, Agenda, Notes, Day, Ask and Quick actions. Each one can be resized, reconfigured and themed, and the picker shows a real preview.

## ⬇️ Install

1. Download the APK from [Releases](https://github.com/Naman503/echomind/releases), or [build it yourself](#-build-from-source).
2. Open it and allow your browser to install apps.
3. Grant the microphone and notification permissions, and allow background running.<br><sub>Vivo / iQOO, Xiaomi, Oppo and Samsung phones kill background apps aggressively. Turn off battery optimisation for EchoMind.</sub>
4. Open **Settings → Models** and download a speech model. Optionally, download a voice and the on-device language model too.

Requires **Android 12 or newer** (API 31). A phone with 8 GB of RAM or more is recommended for the on-device language model.

## 🔒 Privacy

- **Audio and transcripts stay on the phone.** Recognition, speaker grouping, sound tagging and the default AI all run locally.
- **Nothing is sent without your choice.** Cloud AI, web search and song naming are each off until you turn them on.
- **Every network request is logged.** The cloud ledger shows what was sent, to whom, and why.
- **No accounts, analytics or ads.**
- **API keys** are stored with Android Keystore encryption.

**Why the app has internet access:** model downloads, and the optional features above. Nothing else.

## 🩺 Phone health and limits

The **Governor** decides when heavy work, like transcription and AI, may run. Each rule is a slider with colour-coded danger zones, so you can see when a value is risky:

| Limit | What it does |
|---|---|
| Max battery temperature, and the cool-down target | Pause above the max, resume once below the cool-down target |
| Thermal status and headroom | Uses Android's thermal APIs. An emergency thermal level always stops work. |
| Minimum battery | Don't process below this charge level |
| CPU use and threads, on battery and when charging | How hard to push the phone |
| Work/break cycle | Rest for a while after a long run |
| Battery Saver | Optionally pause while it's on |

There's also **"ignore limits for a while"** for when you need results now. The Health page and the home screen show the current state.

## 🛠️ How it works

```
mic ─▶ Recorder ─▶ VAD / sound tagger ─▶ speech recognition ─▶ speaker grouping
                                     │                               │
                                     └─▶ song spans ─▶ song clips    ▼
                                                    Sessionizer ─▶ Enricher (AI)
                                                                       │
                        notes · tasks · people · events · journal ◀────┘
                                          │
                         Ask · Plans · Calendar · Widgets · Read-aloud
```

- **Stack:** Kotlin, Jetpack Compose (Material 3 Expressive), Navigation 3, Room, WorkManager, and Glance widgets.
- **Engines:** sherpa-onnx (speech recognition, sound tagging, text-to-speech) and LiteRT-LM (Gemma).
- **Cloud SDKs (optional):** the Anthropic Java SDK, Gemini and OpenAI.

For more detail, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and the research notes in [docs/research](docs/research).

## 🧑‍💻 Build from source

You need JDK 17 and the Android SDK.

```bash
git clone https://github.com/Naman503/echomind.git
cd echomind
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/EchoMind-1.0.0-release.apk
```

- The release build is signed with the debug key, so you can install it over a local build.
- Add `-Pdiag=true` to make a debuggable release build, so you can inspect the app's data with `adb shell run-as`.
- The sherpa-onnx AAR is vendored in `app/libs`. Models are downloaded inside the app and are not part of the repo.

## 📄 License

[MIT](LICENSE) © 2026 Naman
