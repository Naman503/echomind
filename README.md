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
| 🌙 **Night review** | Every night it rereads your whole day, carefully: who you talked with, what about, what was decided, lessons, and a journal |
| 🗣️ **Speaking coach** | English corrections, better ways to say things, nervous moments and slips — each linked to the exact line |
| 🧠 **Notes, tasks and a journal** | Adds notes and tasks from your conversations automatically, with smart reminders, and keeps a day journal with a productivity rating |
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
- **Tasks are added automatically.** Notes, tasks and events from your conversations go straight onto your lists, with no approval step. If you want one, turn on **Review before adding**.
- **My tasks:** today's progress and your next reminder at the top, then Overdue, Today, Tomorrow, This week, Later and No date. There are also Upcoming, Reminders, Lists and Done views. Swipe right to complete a task, or left to reschedule it.
- **Smart reminders:** if you said a time, you're reminded at that time. If not, the AI picks a useful moment: an hour before a call, the evening before an early start, the morning of an errand. Each reminder notification has Done, Snooze and Tomorrow buttons.
- When you say you've finished something, the AI marks the task as "sounds done" so you can close it with one tap.
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

### Five tabs
**Today** (ask bar, one time-aware day card, today's tasks, next events, latest talk — customisable) · **Calendar** (calm month or week, swipeable day with its diary page and one timeline of events, tasks and conversations, plus a 30-day agenda) · **Tasks** · **Insights** (day review, book, journal, dashboard, coach, topics, plans) · **You** (notes, people, memory, recordings, songs, check-ins, privacy, settings).

### Your book
Each finished week of diary entries becomes a memoir chapter, with a title, an epigraph and its days. Each month becomes a part with a short opening. The reader has a cover, "continue reading", contents, chapter openers, reading time, text size and previous/next. Chapters are written at night, or when you tap.

### Dashboard
Week, month or year: a headline, key numbers against the period before and your 30-day usual, the most unusual day, talk per day, mood and productivity, when you talk (weekday × hour), weekday pattern, who you talked with, topics, how you speak, influences ("happier on days you talk with X") with honest confidence labels and next-day effects, streaks, and a year in pixels.

### Languages
Which language you speak, and for how long: **Hindi** (Devanagari or Hindi typed in Latin letters), **English**, or **Hinglish** (a real mix), worked out from the words on the phone, with no AI. You see it in several places:
- **Day review:** a donut of your minutes, the hours you spoke each language, what the people around you spoke, the language you use with each person, how often you switched language mid-conversation, how many different words you used, and your most used words.
- **Dashboard:** the same for a week, month or year, plus the language you spoke each day.
- **Coach:** your English share over the last two weeks.
- **Other screens:** a language bar on each conversation (next to who spoke how much), on Today and on Insights.

### Things you talked about
Places, movies, shows, books, music, tech and tools, products, companies, food, events and games are noted from your conversations, so you can find "that series Rahul suggested" later. Every mention keeps the exact words, who said them and when.
- **Grounded:** a name is kept only if it really is on the line it cites, in either script. Names are matched across spellings and scripts, so "Panchayat", "Panchayath" and "पंचायत" are one thing, and so are "Koramangala" and "Kormangala".
- **Status:** each thing can be marked want to watch, read or visit, recommended, done, liked or didn't like. This is taken from what was said, and you can change it.
- **Where to find them:**
  - the **Things** page, with search, kinds and a "to watch / try" filter;
  - each conversation and day review;
  - **Ask** ("which show did Rahul suggest?").
- **Older conversations** are read at night, one at a time.
- **Online look-up (optional):** only the name is sent to Wikidata, for a one-line description. It is logged in the data ledger.

### Voice accuracy
Which lines are yours is decided per voice, not per line. All lines in a conversation are grouped by voice, and a group counts as you when its voice as a whole sounds like you, because more audio gives a more reliable answer.
- **Known people:** a voice closer to someone you've talked to before is never labelled as you.
- **Short lines** take the speaker of the lines right around them.
- **Automatic threshold:** every night it is re-fitted to how your voice and other voices actually scored over the last two weeks.
- **Your voice → Check accuracy:**
  - the separation between your voice and others;
  - the current threshold;
  - the lines it is least sure about, which you can answer with "Me" or "Not me". Each answer teaches the model, and lines you've confirmed are never changed again.

### Charts everywhere
Shared chart pieces are used across the app: donuts, progress rings, stacked bars, sparklines, columns per hour and icon number chips.
- **Today widget:** talk by hour, a language bar and task progress (switch them on or off in the widget settings).
- **Tasks:** a progress line in the widget, and a chart of tasks finished in the last seven days.
- **Evening recap notification:** expands to a day card with talk, conversations, people and tasks, talk by hour and your language split.

### Travel mode
For trips and long days out, EchoMind rests so the battery lasts. Listening stops (or keeps recording for later, if you choose), and there is no transcription, AI or night review, even on the charger. The AI model is freed from memory. Reminders still ring. A persistent notification has an **I'm back** button, and a Quick Settings tile switches it from the shade.
- **Turns on by itself if you forget:** you add your places (several homes, work, others, each with a radius). About every 30 minutes, a low-power Wi-Fi/mobile-network location (never GPS) checks whether you're far from all of them and not on your home–work commute. It needs two readings 20 minutes apart (or clearly fast movement), so one odd reading never switches it. Once it's on, there are no location checks at all. "Not travelling" undoes it for 12 hours.
- **Planned trips** switch it by date with no location. EchoMind can **suggest home and work** from where the phone usually is (on the phone only, two weeks). It can **notice you're home** from locations other apps already got, at no extra battery cost. Travel days are marked in the calendar and the diary.

### Places, weather and map
- **Location history (opt-in):** EchoMind notes an approximate location every 15, 30 or 60 minutes (Wi-Fi and mobile networks, never GPS), kept on the phone for as long as you choose. From that it works out your **visits** (where you stayed 15 minutes or more), named after your places or, if you allow it, by OpenStreetMap.
- **What happened where:** each conversation shows where it happened. The day review, diary and Ask know the day's route ("Home until 9:40 AM → Office → Toit"), and the map lists the conversations at each place.
- **Weather (opt-in):** hourly weather from Open-Meteo, using a location rounded to about 1 km, goes into the diary ("rain around 4–6 PM"), the day review and the morning brief.
- **Map:** a full-screen OpenStreetMap view (osmdroid, no account) with your timeline in a pull-up sheet. It shows your path, numbered stops (a chat badge where something was said), your places with home/work icons, a start flag, a "you are here" dot with its accuracy circle, and a scale bar. Map styles: auto, light, dark or terrain. Tap a stop to fly to it, or long-press to save a place.
- **Accuracy:** with precise location allowed, readings are at Wi-Fi level (no constant GPS); otherwise Android limits them to about ±2 km, and the map says so. Stops lean on the best readings.
- **Travel profiles:** *rest completely*, *only note where I go* (for the trip map), or *record and note places, process later*, plus *catch up while charging*.

### Nothing twice, nothing made up
- Read-aloud never repeats itself. The same check against near-duplicates is used for nudges, plans and tasks, and duplicate open tasks merge on their own.
- Every task, event, fact, person and note point the AI extracts must be backed by the transcript: a real quote, real lines, and names that were actually said. The coach may only quote your real words. If the diary or a chapter names someone who isn't in the day's notes, it is rewritten.

### Night review and speaking coach
- **Night review:** from the hour you choose (11 PM by default) until 7 AM, EchoMind reads the day again, slowly and one step at a time, under the same heat and battery rules. Days it missed are caught up first. You can also tap **Review now**.
- **Careful reading on the phone:** each conversation is read in pieces that fit the on-device model, with one focused job per pass: what it was about, then tasks and plans, then people and facts. The model's context is 8K tokens by default (it falls back to 4K if the model can't take more).
- **Day review page:** journal, people you talked with (and for how long), topics, decisions, scores for confidence and clarity, lessons and patterns, follow-ups, and every conversation.
- **Speaking coach:** English grammar and word-choice fixes, clearer or more confident ways to say things, moments you sounded nervous (with a tip), and things you said that were wrong. Each point opens the line it came from. Trends over two weeks show pace, filler words, confidence and your most common mistakes.
- **Exact measures:** words per minute, filler words (English and Hindi), restarts, talk time per person and the Hindi/English mix are counted directly, without AI.
- **Insights hub:** one page for the day review, coach, topics, people, journal, dashboard, plans, memory, check-ins and songs.
- **Fully adjustable:** night window, charging-only, rereading older conversations, which coaching kinds to show, strictness, points per conversation, hidden sections, and the model's context size.
- **A real diary:** every night's journal is written in your own voice ("I…"), like a diary page read aloud in a film. Choose cinematic, reflective or simple, written as you or as a friend, short to long. It is shown on a serif page, and the facts always come from what was really said.
- **Context it should know:** an "About me" note (your work, projects and people) is read with every conversation, and a list of misheard words ("not taking → note-taking") is fixed before the AI reads anything.
- **Ask** uses the reviews too: "what did I discuss yesterday?", "what English mistakes do I keep making?"

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
- **Nothing you say is sent without your choice.** Cloud AI, web search and song naming are each off until you turn them on.
- **Every network request is logged.** The cloud ledger shows what was sent, to whom, and why.
- **No accounts or ads.** EchoMind shares **anonymous usage counts** (app opens, listening, Ask use, setup steps, the AI provider name, crash types) with a random id. Never anything you said, wrote or asked. It is part of using the app (shown once at first launch); to stop, request it in *Settings → Phone & data → Usage sharing*. It stops once the developer approves the request, and what was shared is then deleted. Each report shows in the Data ledger.
- **API keys** are stored with Android Keystore encryption.

**Why the app has internet access:** model downloads, anonymous usage counts, and the optional features above. Nothing else.

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
