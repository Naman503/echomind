# Speech-to-text research (Oct 2026, for iQOO Z7 Pro / Dimensity 7200)

Hindi accuracy reference: Voice of India benchmark (Josh Talks + AI4Bharat, Apr 2026), OI-WER (lower is better):
Sarvam Audio 5.0 · Gemini 3 Pro 6.0 · Saarika 2.5 6.2 · ElevenLabs Scribe v2 7.7 · IndicConformer 8.2 · Gemini 3 Flash 8.3 · Gemma E4B 11.2 · Deepgram Nova-3 13.0 · AssemblyAI 19.3 · GPT-4o-mini-transcribe 19.6 · GPT-4o-transcribe 34.0. Whisper hallucinates English on Hindi clips.

## Local (private)
| Engine | Notes |
|---|---|
| **Android on-device SpeechRecognizer** (Google Speech Services) | Already proven on this phone with en-IN + hi-IN packs, fed from our own WAV through `EXTRA_AUDIO_SOURCE` + `EXTRA_SEGMENTED_SESSION` at 3× real time (faster drops words). No model download, low CPU. No diarization; word timing (`EXTRA_REQUEST_WORD_TIMING`, API 34) only if the service fills it. |
| **sherpa-onnx 1.13.8** (Apache-2.0, AAR from GitHub releases, Kotlin API) | One runtime for: Silero VAD v6 / ten-vad, offline ASR (NeMo CTC/TDT, Whisper, Moonshine, Omnilingual, Qwen3-ASR…), token timestamps, offline speaker diarization (pyannote-seg-3.0 + 3D-Speaker), speaker embedding manager (enrol "me"), spoken-language ID. CPU only on MediaTek. |
| IndicConformer (AI4Bharat, hi ~120M / multi 600M) | Best open Hindi (8.2). Devanagari output. Needs ONNX export; community packs unverified. |
| Parakeet-TDT-0.6B v2 | Excellent English, word timestamps, ~640 MB int8. |
| Whisper (whisper.cpp 1.9.5 / sherpa) | small q5 ≈ RTF 0.4–0.8 on A715; turbo too slow all day. Hindi weak below large; Hinglish fine-tune (Oriserve) only overnight. |
| Vosk small-hi / en-in | 40 MB, streaming, dated accuracy — last resort. |
| ML Kit GenAI speech | Basic mode ≈ SpeechRecognizer, fed at real-time speed only; Advanced = Pixel only. Skip. |

## Cloud (data leaves the phone)
| Service | $/audio hour | Hindi | Timestamps / diarization |
|---|---|---|---|
| **Sarvam saaras:v4** | ₹30 (~$0.34), ₹45 with diarization | best Indic; `codemix` output (English in Latin, Hindi in Devanagari) | chunk-level; diarization in Batch API. REST ≤30 s per request. India-hosted. |
| **Gemini `gemini-3.5-transcribe`** | ~$0.30 | strong | word timestamps, diarization ≤8 speakers; ≤30 min per request with those on |
| **ElevenLabs Scribe v2** | $0.22 | 7.7 | word timestamps, up to 32 speakers |
| Deepgram Nova-3 multilingual | $0.31 | 13.0 | word + diarization |
| **Groq whisper-large-v3-turbo** | $0.04 | Whisper-level | segment + word; cheapest/fastest, English-heavy audio |
| OpenAI gpt-4o-transcribe | $0.36 | poor (34) | avoid for Hindi |

## Capture
AudioRecord 16 kHz mono (VOICE_RECOGNITION) in a microphone FGS; VAD gate so only speech is kept (16 h awake → 2–5 h speech); wall-clock stamps per window, engine times are offsets. Calls: mic is silenced for other apps → import the dialer's `.m4a`, decode with MediaExtractor + MediaCodec, resample to 16 kHz; two speakers, "me" via enrolment.

## Decision for EchoMind
- Local default: **Android on-device recogniser** (zero download, proven here).
- Local advanced (optional download): **sherpa-onnx** — Silero VAD, Whisper/Parakeet/Indic models, diarization + "me" enrolment.
- Cloud: **Sarvam** (best Hindi/Hinglish), **Gemini transcribe** (timestamps + speakers), **ElevenLabs Scribe**, **Groq Whisper** (cheapest), **Deepgram**.
