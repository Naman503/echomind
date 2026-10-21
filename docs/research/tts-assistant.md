# Read-aloud TTS + proactive assistant research (Oct 2026, iQOO Z7 Pro / Dimensity 7200, sherpa-onnx 1.13.8)

Labels: **[src]** means I checked it in source code or the archive itself. **[doc]** means it comes from the linked page. **[unverified]** means secondary, inferred, or from memory, so check it on the phone.

## A1. Kokoro packs in sherpa-onnx

**The Hindi voices exist only in v1.0.** `kokoro-*-multi-lang-v1_1` is the Kokoro v1.1-zh model. It has 103 speakers: `0 af_maple, 1 af_sol, 2 bf_vale`, then `zf_001…`/`zm_…` Mandarin voices (ids 3–102). It has **no Hindi voices** [doc] ([v1_1 page](https://k2-fsa.github.io/sherpa/onnx/tts/all/Chinese-English/kokoro-multi-lang-v1_1.html)). If you load v1_1 with sid 31 you get a Chinese voice. For Hindi you must use `kokoro-int8-multi-lang-v1_0` (or the fp32 `kokoro-multi-lang-v1_0`).

Archive contents [src] (listed by streaming the release tarballs; sizes from the [release API](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models)):

| Pack (download) | Files |
|---|---|
| `kokoro-int8-multi-lang-v1_0.tar.bz2` (126 MB) | `model.int8.onnx` 109 MB, `voices.bin` 26.9 MB, `tokens.txt`, `lexicon-us-en.txt` 5.7 MB, `lexicon-gb-en.txt` 6.1 MB, `lexicon-zh.txt` 2.3 MB, `date-zh.fst`, `number-zh.fst`, `phone-zh.fst`, `espeak-ng-data/` (392 entries, includes `hi_dict`), `dict/` (jieba: `jieba.dict.utf8`, `hmm_model.utf8`, `user.dict.utf8`, `idf.utf8`, `stop_words.utf8`, `pos_dict/`), `LICENSE`, `README.md` |
| `kokoro-multi-lang-v1_0.tar.bz2` (333 MB) | Same layout, but the model is `model.onnx` 310 MB [doc] ([kokoro page](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html)) |
| `kokoro-int8-multi-lang-v1_1.tar.bz2` (140 MB) | `model.int8.onnx` 109 MB, `voices.bin` 51 MB, plus the same lexicons, fsts, `dict/` and `espeak-ng-data/` |

**v1.0 speaker IDs (53 speakers)** [doc]: 0 af_alloy, 1 af_aoede, **2 af_bella**, **3 af_heart**, 4 af_jessica, 5 af_kore, **6 af_nicole**, 7 af_nova, 8 af_river, 9 af_sarah, 10 af_sky, 11 am_adam, 12 am_echo, 13 am_eric, **14 am_fenrir**, 15 am_liam, **16 am_michael**, 17 am_onyx, **18 am_puck**, 19 am_santa, 20 bf_alice, **21 bf_emma**, 22 bf_isabella, 23 bf_lily, 24 bm_daniel, 25 bm_fable, **26 bm_george**, 27 bm_lewis, 28 ef_dora, 29 em_alex, 30 ff_siwis, **31 hf_alpha, 32 hf_beta, 33 hm_omega, 34 hm_psi**, 35 if_sara, 36 im_nicola, 37–40 jf_*, 41 jm_kumo, 42 pf_dora, 43 pm_alex, 44 pm_santa, 45–52 zf_/zm_*.

Quality grades come from [VOICES.md](https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md) [doc]. af_heart is the flagship voice (target grade A). af_bella is A-. af_nicole and bf_emma are B-. am_michael, am_fenrir and am_puck are C+. **All four Hindi voices are grade C**, trained on only "MM minutes" each, with espeak-ng `hi` as the G2P.

**How the frontend really works in 1.13.8** [src] (`offline-tts-kokoro-impl.h`, `kokoro-multi-lang-lexicon.cc` at tag v1.13.8):
- A multi-lang model (metadata `version>=2`) needs `lexicon` **or** `lang` to be non-empty. Otherwise the process calls `SHERPA_ONNX_EXIT(-1)`. `lexicon` takes comma-separated paths, e.g. `".../lexicon-us-en.txt,.../lexicon-zh.txt"`.
- The language is resolved per call as `gen_config.extra["lang"]`, then `config.kokoro.lang`, then the model metadata `voice`, which is **`"en-us"`** ([add_meta_data.py](https://github.com/k2-fsa/sherpa-onnx/blob/master/scripts/kokoro/v1.0/add_meta_data.py)).
- Text is split into Han runs (`一-鿿`, handled by the zh lexicon) and non-Han runs. Because the resolved voice is never empty, **every non-Han run, Devanagari included, is phonemized by espeak-ng using that voice**. In this version the English lexicon goes unused for non-Chinese text, and passing it mainly satisfies the "lexicon or lang" check.
- `lang` values are espeak-ng voice names: `en-us`, `en-gb`, `hi`, `es`, `fr`, `pt-br`, `it`, `ja`, `cmn`, and so on [src comment gives es/fr/pt; others unverified].
- **Consequence:** `generate(text, sid=31)` with `lang` empty phonemizes Hindi with en-us rules, which skips or garbles the text. The current `TtsModels`/`Speaker.kt` code does exactly this. Pass the language per sentence instead:

```kotlin
tts.generateWithConfigAndCallback(sentence,
  GenerationConfig(sid = 31, speed = s, extra = mapOf("lang" to "hi"))) { pcm -> /* feed AudioTrack */ 1 }
```

`generateWithConfig` and `generateWithConfigAndCallback` exist in the Kotlin API, and the JNI layer reads `extra` as `Map<String,String>` [src]. `OfflineTtsKokoroModelConfig.lengthScale` works as `speed = 1/lengthScale`. `dataDir` must point to `espeak-ng-data` in 1.13.8; master says espeak is dropped "since v2.0.0", so recheck when you upgrade.

**Hindi and Hinglish** [unverified, inferred from the code path]:
- Devanagari text works with `lang="hi"`, and it is espeak-quality G2P.
- Romanized Hindi ("kal meeting hai") under `en-us` is read as English words, with an anglicized accent. espeak `hi` on Latin script is untested.
- Best practice is to split sentences by script: Devanagari runs go to `lang=hi`, Latin runs to `lang=en-us`. Keep **the same sid** for both, because Kokoro voices are cross-lingual and a Hindi voice reading English gives Indian-accented English.
- For STT output that is romanized Hindi, either transliterate it to Devanagari first (Sarvam's `codemix` output is already split by script) or accept the English reading.

## A2. Speed and quality: Kokoro vs Piper

- No published phone benchmark for sherpa-onnx Kokoro was found. Closest data points:
  - Raspberry Pi 4 (Cortex-A72), v1_0 fp32: RTF 7.6 at 1 thread and 3.19 at 4 threads [doc] (kokoro page).
  - [muxable/rtchat#1500](https://github.com/muxable/rtchat/issues/1500) (2026-10, sherpa 1.13.8, desktop / x86 emulator, 2 threads) [doc]:

| Model | RTF (desktop / emulator) |
|---|---|
| Kokoro v1.1 fp32 | 0.37 / 0.59 |
| Kokoro v1.1 int8 | 0.96 / 1.52 |
| Piper medium fp32 | 0.04 / 0.05 |
| Piper medium int8 | 0.13 / 0.21 |
| Kitten nano fp32 | 0.07 / 0.09 |
| Supertonic 3 int8 | 0.14 / 0.20 |

- **int8 was 2.6–3.5× slower than fp32 on x86**, and int8 gains little from extra threads. Kokoro needs about 620 MB RSS. That issue also mentions reports of int8 "garbage output on ARM" [unverified].
- On a 2-core Neoverse-N1, Piper ran about 5–9× faster than Kokoro (Kokoro ran at 0.91× real time). The maintainer said: "Kokoro 82M TTS is indeed slower than Piper" ([discussion #3968](https://github.com/k2-fsa/sherpa-onnx/discussions/3968)) [doc].
- An Arabic model with the Kokoro architecture reached RTF about 0.67 at 4 threads on a Snapdragon 865/870 ([#3897](https://github.com/k2-fsa/sherpa-onnx/issues/3897)) [doc].
- **Estimate for the Dimensity 7200 (2×A715 + 6×A510)** [unverified]: Kokoro int8 at 2 threads should land around RTF 0.5–1.0, which is borderline. Piper medium int8 should be around 0.05–0.15. Benchmark both fp32 and int8 on the phone. Pipelining one sentence ahead (already in `Speaker.kt`) hides Kokoro latency only when RTF is below 1/speed.
- **Naturalness:** Kokoro English (af_heart, af_bella) is clearly ahead of Piper medium. In rtchat's ASR-proxy test, Piper libritts_r had about 22% WER against 12% for Kitten nano [doc].
- **Piper Hindi** [src: MODEL_CARD inside the archives]: all three voices are single-speaker medium models at 22,050 Hz.
  - `priyamvada` (female) and `pratham` (male) were trained by PravalX on the AI4Bharat indicnlp corpus. Licence: **CC BY-NC-SA 4.0**, so personal use only.
  - `rohan` (male) uses the IIT-Madras IndicTTS "Hindi Mono Male" data, fine-tuned from en_US-lessac.
  - No published listening review was found. Expect them to be intelligible but robotic [unverified]. They are better than Kokoro grade-C Hindi on Hindi pronunciation (an assumption: they were trained on hours of Hindi rather than minutes).
- **Piper layouts** [src]:
  - `vits-piper-hi_IN-priyamvada-medium-int8.tar.bz2` (20 MB): `hi_IN-priyamvada-medium.onnx` (17.7 MB, already int8 despite the name), `hi_IN-priyamvada-medium.onnx.json`, `tokens.txt`, `MODEL_CARD`, `espeak-ng-data/`.
  - `vits-piper-en_US-lessac-medium-int8.tar.bz2` (19 MB): `en_US-lessac-medium.onnx`, `.onnx.json`, `tokens.txt`, `MODEL_CARD`, `espeak-ng-data/`.
  - Config: `OfflineTtsVitsModelConfig(model, tokens, dataDir=espeak-ng-data)` with `lexicon=""`.

## A3. Other Hindi options

| Model | Hindi? | On-phone feasibility |
|---|---|---|
| **Supertonic 3** (`sherpa-onnx-supertonic-3-tts-int8-2026-05-11`, 122 MB) | **Yes.** `"hi"` is in `kSupertonicAvailableLangs` [src]. 31 languages ([Supertone](https://www.supertone.ai/en/work/faster-and-more-accurate-across-31-languages----introducing-supertonic-3)). | Already supported by 1.13.8 (`OfflineTtsSupertonicModelConfig`). Files: `duration_predictor.int8.onnx`, `text_encoder.int8.onnx`, `vector_estimator.int8.onnx`, `vocoder.int8.onnx`, `tts.json`, `unicode_indexer.bin`, `voice.bin` [src]. sids 0–4 are F1–F5 and 5–9 are M1–M5. Options via `extra`: `lang`, `speed` (default 1.05), `num_steps` (default 5), `max_len`, `silence_duration`. RTF is about 0.2 on the emulator [doc]. No espeak (it reads raw Unicode). Model licence is OpenRAIL-M. **Best candidate for natural Hindi. A/B it against Piper on the phone.** |
| AI4Bharat Indic-Parler-TTS | Yes | About 0.9B parameters, gated. Not realistic on a phone [doc] ([dev.co](https://dev.co/ai/llms/indic-parler-tts)). |
| AI4Bharat IndicF5 | Yes | About 350–400M parameters with flow matching. Needs reference audio. An INT8 MNN build is about 347 MB and is "not real-time on M1" ([IndicF5-MNN](https://huggingface.co/developerabu/IndicF5-MNN)). Overnight use only. |
| KittenTTS (nano/micro/mini v0.8) | **English only** | nano fp32 (60 MB) is the fastest good English voice (RTF 0.07–0.09) [doc]. Worth having as a "fast English" voice. |

## A4. Playback speed

- **AOSP limits** [src: `system/media/audio/include/system/audio.h`, `AudioResamplerPublic.h`]:
  - Hard bounds are `AUDIO_TIMESTRETCH_SPEED_MIN 0.01f` and **`AUDIO_TIMESTRETCH_SPEED_MAX 20.0f`**.
  - When the fallback mode is FAIL and the stretch mode is DEFAULT or VOICE, the Sonic limits **`TIMESTRETCH_SONIC_SPEED_MAX 6.0f`** (min 0.1) apply.
  - Pitch is allowed from 0.25 to 4.0.
  - `AudioTrack::setPlaybackRate` also rejects a speed when the track buffer is too small. `isSampleRateSpeedAllowed_l` requires `mFrameCount >= minFrameCount(speed)`. So allocate a buffer of **at least `getMinBufferSize × ceil(maxSpeed)`, or about 1 s**, otherwise high speeds fail with BAD_VALUE ("failed (buffer size)").
  - **Treat 6× as the practical ceiling.**
- Settings TTS rate range is `MIN_SPEECH_RATE 10` to `MAX_SPEECH_RATE 600`, i.e. `setSpeechRate` 0.1–**6.0** [src: AOSP `TextToSpeechSettings.java`]. The engine may clamp lower [unverified].
- **Quality:**
  - The framework's time-stretch is Sonic-based. Sonic is "optimized for speed ups of over 2X" ([waywardgeek/sonic](https://github.com/waywardgeek/sonic), Apache-2.0).
  - Model-side speed (Kokoro `speed`, Piper `lengthScale`) sounds more natural up to about 1.5–2×. Beyond that, prosody collapses or words get clipped [unverified, matches common reports].
  - Best practice for 3–5×: generate at a model speed of 1.3–1.5, then time-stretch the remainder. 4× = 1.4 × 2.86. The current `NATURAL_SPEED = 1.5` is right.
  - Strip silences: lower `silenceScale` (default 0.2) and set a shorter inter-sentence gap at speed > 2.
- **Alternatives:**
  - Run `Sonic.java` (or Media3's `SonicAudioProcessor`, which has no fixed max) on the PCM before `AudioTrack.write`. This gives deterministic quality, works past 6×, and works on OEM builds where `setPlaybackParams` misbehaves.
  - Pre-stretching also makes RTF requirements easier, because generation time stays constant while the audio gets shorter.

## A5. Background playback (Android 14/15)

- **FGS `mediaPlayback`**: needs `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, with no runtime prerequisite. On Android 15 it cannot be started from `BOOT_COMPLETED` [doc] ([FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)). The manifest already declares it.
- **Session**:
  - A platform `MediaSession` with `Notification.MediaStyle` (the current code) is enough for lock-screen controls and Bluetooth play/pause/next.
  - Media3 `MediaSessionService` needs a `Player`. For a custom AudioTrack pipeline that means wrapping it in `SimpleBasePlayer`. Only do that if you want Android Auto, Wear, or resumption.
- **Audio focus** [doc] ([audio focus](https://developer.android.com/media/optimize/audio-focus)):
  - Long read-aloud: `AUDIOFOCUS_GAIN_TRANSIENT`, which pauses music. `GAIN` would stop it.
  - Short announcements (under 10 s): `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. The current code uses MAY_DUCK for everything, so music plays ducked under long reads.
  - Use `CONTENT_TYPE_SPEECH`. It exempts you from auto-ducking and from the Android 12 fade-out.
  - **Android 15 (target 35): focus requests fail unless the app is the top app or running an FGS.** Start the FGS before the request.
  - Pick a usage: `USAGE_ASSISTANT` for spoken nudges and `USAGE_MEDIA` for read-aloud [unverified, a choice].
- **Headphones-only announcements**:
  - Check `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` for `TYPE_WIRED_HEADPHONES/HEADSET`, `TYPE_BLUETOOTH_A2DP`, `TYPE_BLE_HEADSET`, and `TYPE_USB_HEADSET`. Then pin the track with `AudioTrack.setPreferredDevice(device)`.
  - Abort on `ACTION_AUDIO_BECOMING_NOISY`.
  - Skip when `audioManager.mode` is `MODE_IN_CALL` or `MODE_IN_COMMUNICATION` (`addOnModeChangedListener`, API 31). No phone permission is needed for this.

## B6. How proactive assistants decide

- **Alexa Hunches** learn patterns from device usage. They first offered suggestions, and since Dec 2020 users can opt in to Alexa acting automatically ([Amazon dev blog](https://developer.amazon.com/en-US/blogs/alexa/device-makers/2020/12/Enable-Alexa-to-Proactively-Act-on-Hunches)). Lesson: acting on its own needs explicit opt-in, per category.
- **Siri Suggestions** work from routines plus location, time of day, and motion ([Apple](https://support.apple.com/guide/iphone/about-siri-suggestions-iph6f94af287/14.0/ios)). They are shown passively on the Lock Screen or in Search, rarely as an alert, and can be turned off per app.
- **Google Magic Cue / Gemini "Daily Brief"**: Magic Cue appears *in context*, inside the conversation where the info is needed ([9to5Google](https://9to5google.com/2025/08/20/pixel-10-magic-cue-launch/)). Daily Brief is a once-a-morning digest ("Top of mind", "Active goals") per APK teardowns ([Android Authority](https://www.androidauthority.com/gemini-daily-brief-apk-teardown-3661094/)) [leak, unverified]. Lesson: one batched digest beats many pings, and in-context surfacing beats alerts.
- **Bee** (Amazon) gives *suggested* to-dos and daily summaries, and the user confirms them ([TechCrunch](https://techcrunch.com/?p=3177560)). For Limitless I found no documented proactive logic [unverified].
- **Research:**
  - Horvitz's mixed-initiative principles: act only when expected benefit exceeds the cost of interrupting, and fall back to asking or deferring when uncertain ([CHI'99](https://erichorvitz.com/chi99horvitz.pdf)).
  - Iqbal & Horvitz: alerts are valued but disruptive, and people want fewer, more relevant ones ([CSCW'10](https://www.microsoft.com/en-us/research/publication/notifications-and-awareness-a-field-study-of-alert-usage-and-preferences/)) [from memory].
  - Adamczyk & Bailey: interrupting at task **breakpoints** lowers annoyance ([CHI'04](https://dl.acm.org/doi/10.1145/985692.985715)).
  - Okoshi's Attelia deferred notifications to breakpoints and cut cognitive load by 33–46% ([survey](https://arxiv.org/pdf/1711.10171)).
  - Mehrotra et al. (10,372 notifications) found disruption depends on task type and completion, sender, and alert type ([CHI'16](https://discovery.ucl.ac.uk/id/eprint/1502364/)).
  - Pielot et al. predicted engagement before delivery: +66.6% over baseline, and about 5× better when using the user's own history ([IMWUT'17](https://unpaywall.org/10.1145%2F3130956)).
  - In practice, good moments are screen-unlock, just after ending a call or a meeting, and after a phone-use episode. Learning per user (dismissal history) matters most.

## B7. Nudges that work for a memory assistant

1. **Question to me, not answered** ("Naman, can you send the quote?" with no reply from the user before the conversation ended). Deliver at the conversation boundary or the next unlock.
2. **Promise I made** ("I'll call you tomorrow"): create a task with a due date, then nudge 1 h before or at the stated time.
3. **Task due within 24 h with no reminder**: one nudge at 09:00 or 2 h before. Never repeat it.
4. **End-of-day loose ends**: a single digest (open promises, unanswered questions, tasks due tomorrow) at a user-set time, 21:00 by default.
5. **Capture health**: mic blocked or silence for more than 2 h while the phone is in motion, or a speech window with low SNR or ASR confidence ("audio was poor 14:10–14:40, want to add a note?"). Deliver immediately, but at most once per day.
6. **Recurrence**: "you mentioned X 3+ times in 7 days" goes into the morning digest only, never as an alert.

## B8. Voice commands with small LLMs

- **Push-to-talk** from a notification action, Quick Settings tile, widget, or headset long-press via the MediaSession `onMediaButtonEvent`. No wake word (battery and privacy).
- **Rule-first**: regex or keyword grammar for EN plus Hinglish verbs ("add/jod do", "done/ho gaya", "padh ke sunao"). If nothing matches, fall back to the local LLM with a strict JSON schema: `{intent: add_task|complete_task|set_reminder|read_tasks|read_note|read_summary|search|stop, text, when, target}`, using grammar-constrained decoding and temperature 0. `Assistant.kt` already follows this shape.
- **Confirmation**:
  - Reversible actions (add, complete) execute immediately and are announced with Undo (8 s).
  - Destructive or ambiguous ones (delete, bulk complete, more than one fuzzy match) get a spoken "say yes to confirm"; silence for 5 s cancels.
  - Echo back the resolved target ("Marking *Call Ravi* done").

## Recommended design for EchoMind

1. **Engines**:
   - English default: Kokoro **v1_0** int8, `af_heart` (sid 3). Alternatives: af_bella 2, bf_emma 21, am_michael 16.
   - Hindi default: **Supertonic 3** (`lang=hi`, sid 0 or 5) once an A/B on the phone confirms it. Until then Piper `priyamvada`/`rohan`, with Kokoro hf_alpha 31 / hm_omega 33 as a third option.
   - Fast/low-battery option: Piper lessac or Kitten nano.
   - Android `TextToSpeech` (en-IN/hi-IN) as the last fallback.
   - Drop v1_1 from the catalog, or relabel it as English/Chinese only.
2. **Fix now**:
   - Call `generateWithConfig*` with `extra["lang"]` set to `hi` or `en-us` per sentence.
   - Point the Kokoro pack at v1_0.
   - Benchmark int8 vs fp32 at 2 threads on the A715 cores and keep whichever has RTF < 0.5.
3. **Speed**:
   - UI range 0.75–5× in 0.25 steps; default 1.0 for nudges and the user's saved value for reads.
   - Model speed is `min(s, 1.5)` and the rest is time-stretch.
   - Prefer Sonic on PCM. If you use `setPlaybackParams`, size the buffer for 6×.
   - Above 2×, shorten silences (`silenceScale` 0.1, gap 40 ms).
4. **Playback**:
   - Start the FGS first, then request focus: TRANSIENT for reads, MAY_DUCK for announcements under 10 s.
   - MediaSession controls (play/pause, ±1 sentence as skip, speed as a custom action).
   - Stop on becoming-noisy.
5. **UI**:
   - A speaker button on Note, Conversation summary, Today brief, and Tasks list.
   - A mini-player bar showing the current sentence highlighted.
   - "Read my day" on the Today screen and in the widget.
   - A push-to-talk tile.
6. **Nudge rules** (the "Governor"):
   - At most **3 interrupting nudges per day**, at least **90 min apart**.
   - Quiet hours 22:00–08:00.
   - Never during a call, while recording a meeting, or in DND (`NotificationManager.currentInterruptionFilter`).
   - Deliver at breakpoints: unlock, conversation end, call end.
   - Low-confidence items go to the 21:00 digest.
   - Spoken only when headphones are connected **and** the user opted in. Otherwise a silent notification.
   - 2 consecutive dismissals of a nudge type halve its daily cap. 3 dismissals in a row turn it into digest-only for 7 days.
   - Track acted-on vs dismissed per type, and show an "Assistant" settings page with a toggle per type.
