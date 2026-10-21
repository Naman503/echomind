# Cloud API wire formats (raw OkHttp reference)

Researched 2026-10-21 against live vendor docs. `[UNVERIFIED]` marks anything I could not confirm on an official page. JSON shapes list only the fields we need.

---

## Speech-to-text

### 1. Sarvam AI — `saaras:v4`

Docs: https://docs.sarvam.ai/api-reference-docs/speech-to-text/transcribe · batch: https://docs.sarvam.ai/api/api-guides-tutorials/speech-to-text/batch-api

**REST (sync) endpoint:** `POST https://api.sarvam.ai/speech-to-text` (multipart). Auth: header `api-subscription-key: <key>`.

| field | values |
|---|---|
| `file` (required) | WAV, MP3, AAC, AIFF, OGG, OPUS, FLAC, MP4/M4A, AMR, WMA, WebM, PCM. Works best at 16 kHz. Multi-channel audio is merged to mono. |
| `model` | `saaras:v4` (default, recommended) or `saaras:v3`. **saarika is no longer listed anywhere in the docs.** |
| `mode` | `transcribe` (default), `translate`, `verbatim`, `translit`, `codemix`. The REST page says "Only applicable when using saaras:v3 model". The batch reference and the models page say it applies to v3 **and** v4. Test it against v4. |
| `language_code` | `unknown` (auto-detect), `hi-IN`, `en-IN`, `bn-IN`, `kn-IN`, `ml-IN`, `mr-IN`, `od-IN`, `pa-IN`, `ta-IN`, `te-IN`, `gu-IN`, plus 12 more `xx-IN` codes |
| `with_timestamps` | `true`/`false`. Timestamps are **chunk-level only**; there are no word-level timestamps. |
| `input_audio_codec` | Only needed for raw PCM (`pcm_s16le`, `pcm_l16`, `pcm_raw`; 16 kHz only). Other formats are auto-detected. |
| `keyterms` | A JSON-encoded string array, ≤50 terms of ≤64 chars each (v4 only) |

**Limits:** the FAQ says "Maximum duration: 30 seconds per request". No file-size limit is stated. Whether going over 30 s returns a hard error is [UNVERIFIED]. **Diarization is not available on REST.**

**Response:**
```json
{ "request_id": "…", "transcript": "…", "language_code": "hi-IN",
  "language_probability": 0.97,
  "timestamps": { "words": ["chunk text", …], "start_time_seconds": [0.0, …], "end_time_seconds": [2.1, …] } }
```
`timestamps.words` holds phrase chunks, not single words. The three arrays are parallel. `language_probability` appears only when the language was auto-detected. Errors return `{error:{message, code, request_id}}`.

```bash
curl -X POST https://api.sarvam.ai/speech-to-text -H "api-subscription-key: $SARVAM_KEY" \
  -F file=@clip.wav -F model=saaras:v4 -F mode=codemix -F language_code=unknown -F with_timestamps=true
```

**Batch (for audio over 30 s, up to 2 h per file, 20 files per job).** All calls are JSON and use the same auth header.
1. `POST /speech-to-text/job/v1` with body `{"job_parameters":{"model":"saaras:v4","mode":"transcribe","language_code":"unknown","with_timestamps":true,"with_diarization":true,"num_speakers":2},"callback":{"url":…,"auth_token":…}}`. Returns **202** `{job_id, job_state:"Accepted", storage_container_type}`.
2. `POST /speech-to-text/job/v1/upload-files` with body `{"job_id":…,"files":["call.m4a"]}`. Returns `{upload_urls:{"call.m4a":{"file_url":"https://…blob.core.windows.net/…?sas"}}}`. PUT the bytes to `file_url`. Azure SAS uploads normally need the header `x-ms-blob-type: BlockBlob` [UNVERIFIED in Sarvam docs].
3. `POST /speech-to-text/job/v1/{job_id}/start` (no body).
4. `GET /speech-to-text/job/v1/{job_id}/status` returns `job_state` (`Accepted|Pending|Running|Completed|Failed`) and `job_details[].outputs[].file_name` (e.g. `0.json`), with `job_details[].state` set to `Success|API Error|Internal Server Error`.
5. `POST /speech-to-text/job/v1/download-files` with body `{"job_id":…,"files":["0.json"]}`. Returns `download_urls{name:{file_url}}`; GET that URL.

The output JSON has the REST fields plus `diarized_transcript.entries[]: {transcript, start_time_seconds, end_time_seconds, speaker_id:"0"}`. Diarization is labelled beta, supports up to 20 speakers, and is priced separately. Batch `timestamps` uses the key `chunks`, where REST uses `words`.

### 2. ElevenLabs Scribe v2

Docs: https://elevenlabs.io/docs/api-reference/speech-to-text/convert

`POST https://api.elevenlabs.io/v1/speech-to-text` (multipart). Auth: `xi-api-key: <key>`. The optional query param `enable_logging=false` turns on zero-retention, which is enterprise-only.

Fields:
- `model_id=scribe_v2` (required)
- `file`, or `source_url` (`cloud_storage_url` is deprecated)
- `language_code`: ISO-639-1 or 639-3; omit to auto-detect
- `diarize` (bool); `num_speakers` (≤32); `diarization_threshold`
- `timestamps_granularity`: `word` | `character` (multichannel text also mentions `none`)
- `tag_audio_events` (bool)
- `file_format`: `pcm_s16le_16` | `other`
- `temperature` (0–2); `seed`; `no_verbatim` (bool, scribe_v2 only)
- `keyterms` (≤1000; adds a 20% surcharge)
- `use_multi_channel`; `webhook`

Limits: file < 5 GB, minimum 100 ms. No maximum duration is stated.

Response (200):
```json
{ "language_code": "en", "language_probability": 0.98, "text": "…",
  "words": [ { "text": "Hello", "start": 0.0, "end": 0.5, "type": "word", "speaker_id": "speaker_1", "logprob": -0.12,
               "characters": [ {"text":"H","start":0.0,"end":0.1} ] } ],
  "transcription_id": "…", "audio_duration_secs": 12.3 }
```
`type` is one of `word`, `spacing`, or `audio_event`; drop `spacing` entries when building segments. `start`, `end`, and `speaker_id` are nullable. `characters` is filled only when granularity is `character`. If `webhook=true`, the response is a 202.
```bash
curl -X POST https://api.elevenlabs.io/v1/speech-to-text -H "xi-api-key: $XI_KEY" \
  -F model_id=scribe_v2 -F file=@clip.wav -F diarize=true -F timestamps_granularity=word -F tag_audio_events=false
```

### 3. Deepgram Nova-3 (pre-recorded)

Docs: https://developers.deepgram.com/reference/speech-to-text/listen-pre-recorded · https://developers.deepgram.com/docs/pre-recorded-audio · https://developers.deepgram.com/docs/diarization · https://developers.deepgram.com/docs/multilingual-code-switching

`POST https://api.deepgram.com/v1/listen?model=nova-3&language=multi&diarize_model=latest&punctuate=true&utterances=true&smart_format=true`
- Auth: `Authorization: Token <key>` (`Bearer` is for JWTs only).
- Body is the raw bytes with `Content-Type: audio/wav` (or `audio/mp4` / `audio/m4a` for AAC [UNVERIFIED exact type]), or JSON `{"url":…}`.
- `diarize=true` is **deprecated** but still works, and it always routes to the v1 diarizer. Prefer `diarize_model=latest`, which uses v2 for batch. **Sending both parameters is rejected.**
- `language=multi` covers en, es, fr, de, **hi**, ru, pt, ja, it, nl. Nova-3 also accepts monolingual `hi`. There is no Hinglish or `hi-Latn` option for nova-3.
- Other params: `utt_split` (default 0.8 s) and `detect_language`.

Limits: 2 GB per file. A request that runs longer than 10 minutes (Nova models) returns **504**.

Response:
```json
{ "metadata": { "request_id": "…", "duration": 41.2 },
  "results": {
    "channels": [ { "alternatives": [ { "transcript": "…", "confidence": 0.98, "languages": ["hi","en"],
        "words": [ { "word": "…", "start": 0.1, "end": 0.4, "confidence": 0.99, "speaker": 0, "speaker_confidence": 0.8, "language": "hi" } ] } ] } ],
    "utterances": [ { "id": "uuid", "start": 0.1, "end": 3.2, "confidence": 0.95, "channel": 0, "transcript": "…", "speaker": 0,
        "words": [ { "word": "…", "punctuated_word": "…", "start": 0.1, "end": 0.4, "speaker": 0 } ] } ] } }
```
`languages` and `words[].language` appear only with `language=multi`. Per-utterance language is [UNVERIFIED].
```bash
curl -X POST 'https://api.deepgram.com/v1/listen?model=nova-3&language=multi&diarize_model=latest&punctuate=true&utterances=true&smart_format=true' \
  -H "Authorization: Token $DG_KEY" -H 'Content-Type: audio/wav' --data-binary @clip.wav
```

### 4. Groq Whisper (OpenAI-compatible)

Docs: https://console.groq.com/docs/speech-to-text · https://console.groq.com/docs/api-reference

`POST https://api.groq.com/openai/v1/audio/transcriptions` (multipart). Auth: `Authorization: Bearer <key>`.

Fields:
- `file`, or `url`
- `model`: `whisper-large-v3-turbo` (transcription only) or `whisper-large-v3`. Both are still current.
- `language`: ISO-639-1, e.g. `hi`
- `prompt`: ≤224 tokens
- `response_format`: `json` | `verbose_json` | `text`
- `timestamp_granularities[]`: `segment` and/or `word`; needs `verbose_json`. Send the field twice to get both.
- `temperature`: default 0

Formats: flac, mp3, mp4, mpeg, mpga, **m4a**, ogg, wav, webm. Size limit is 25 MB on the free tier and 100 MB on the dev tier; use `url` for anything larger. Every request is billed as at least 10 s.

`verbose_json` response:
```json
{ "task": "transcribe", "language": "hindi", "duration": 12.4, "text": "…",
  "segments": [ { "id": 0, "seek": 0, "start": 0.0, "end": 4.2, "text": "…", "tokens": [ … ], "temperature": 0.0,
                  "avg_logprob": -0.2, "compression_ratio": 1.3, "no_speech_prob": 0.01 } ],
  "words": [ { "word": "…", "start": 0.0, "end": 0.3 } ], "x_groq": { "id": "req_…" } }
```
The `segments[]` fields are documented. `task`, `language`, `duration`, and `words[]` follow the OpenAI shape but are [UNVERIFIED] on Groq's page. Whisper has no diarization.
```bash
curl https://api.groq.com/openai/v1/audio/transcriptions -H "Authorization: Bearer $GROQ_KEY" \
  -F file=@clip.wav -F model=whisper-large-v3-turbo -F response_format=verbose_json -F 'timestamp_granularities[]=segment'
```

### 5a. Gemini 3.5 Transcribe (dedicated STT model)

Docs: https://ai.google.dev/gemini-api/docs/models/gemini-3.5-transcribe · https://ai.google.dev/gemini-api/docs/transcribe · REST ref https://ai.google.dev/api/generate-content (AudioTranscriptionConfig)

- Model IDs: **`gemini-3.5-transcribe`** for files and `gemini-3.5-transcribe-live` for streaming. Both are listed as **Stable**.
- Limits: up to 1 h per request, or **30 min when diarization or word timestamps are on**.
- Diarization supports ≤8 speakers; 3 or more is experimental.
- Auto-detects 85+ languages and handles code-mixing.
- `customVocabulary` (≤1000 terms) **cannot be combined with** diarization or timestamps. `SMART` mode cannot be combined with either of them.

There are two documented request paths. They use **different field names**:

**(i) Interactions API.** This is what the transcribe guide uses. Endpoint: `POST https://generativelanguage.googleapis.com/v1beta/interactions`. Note that the migration guide shows `/v1beta2/interactions` instead [UNVERIFIED which is canonical].
```json
{ "model": "gemini-3.5-transcribe",
  "input": [ { "type": "audio", "uri": "<Files API uri>", "mime_type": "audio/wav" } ],
  "generation_config": { "transcription_config": {
      "language_codes": ["hi-IN","en-IN"],
      "mode": { "type": "verbatim", "diarization_mode": "speaker", "timestamp_granularities": ["word"] } } } }
```
- For smart mode, send `"mode":"smart"` instead.
- Inline audio as `{"type":"audio","data":"<b64>","mime_type":…}` is documented for Interactions in general (audio guide) but is not shown for the transcribe model [UNVERIFIED].
- Response: `{id, status:"completed", steps:[{type:"model_output", content:[{type:"text", text:"…", annotations:[{type:"word_info", text, speaker:"spk_1", start_offset:"0.100s", end_offset:"0.450s"}]}]}]}`.
- The response has no detected-language field.

**(ii) generateContent.** Endpoint: `POST …/v1beta/models/gemini-3.5-transcribe:generateContent`.
```json
{ "contents": [ { "parts": [ { "inlineData": { "mimeType": "audio/wav", "data": "<b64>" } } ] } ],
  "generationConfig": { "audioTranscriptionConfig": {
      "languageCodes": ["hi-IN"], "wordTimestamp": true, "diarization": true, "mode": "VERBATIM" } } }
```
The response puts the output in `candidates[0].content.parts[].audioTranscription = {text, speakerLabel:"spk_1", words:[{word, startOffset:"3.5s", endOffset}]}`. For multi-speaker audio there is one part per speaker segment. Read `audioTranscription` rather than `text` [UNVERIFIED end-to-end].

⚠ A forum report from late Aug 2026 says `gemini-3.5-transcribe` returns **empty output (HTTP 200, `parts:[{}]`)** on every REST path, and there is no official reply: https://discuss.ai.google.dev/t/gemini-3-5-transcribe-returns-empty-transcription-http-200-zero-output-tokens-on-all-documented-rest-paths/179937. The poster's workaround was `gemini-3.5-flash-lite` with a prompt and temperature 0. Test this before shipping. Offsets are proto Duration strings (`"3.5s"`), so parse them by stripping the trailing `s`.

### 5b. Gemini generateContent with inline audio (general models)

Docs: https://ai.google.dev/gemini-api/docs/audio · https://ai.google.dev/gemini-api/docs/file-input-methods · https://ai.google.dev/api/generate-content

- Part: `{"inlineData":{"mimeType":"audio/wav","data":"<base64>"}}`. Google's own curl samples use snake_case (`inline_data`/`mime_type`, `system_instruction`, `response_mime_type`). The proto-JSON mapping accepts **both casings**, so pick one and stay consistent.
- Supported mimes: `audio/wav`, `audio/mp3`, `audio/aiff`, `audio/aac`, `audio/ogg`, `audio/flac`, plus MPEG, M4A, L16, Opus, ALAW, MULAW, and WebM.
- Audio is billed at 32 tokens/s; up to 9.5 h per prompt.
- **Inline size limit: the docs disagree.** The audio guide says 20 MB total request. The file-input-methods page says 100 MB per request (50 MB for PDFs). Budget for 20 MB. A 30-min AAC at 64 kbps is about 14 MB raw and about 19 MB in base64, which is borderline, so use the Files API above roughly 14 MB raw. Files API uploads are ≤2 GB and expire after 48 h.
- Structured JSON in `generationConfig`:
  - `responseJsonSchema` is the field to use, together with `responseMimeType:"application/json"`.
  - `responseSchema` (the OpenAPI subset) is **deprecated**.
  - `_responseJsonSchema` is deprecated too.
  - The reference now says "Use `responseFormat` instead": `generationConfig.responseFormat = {"text":{"mimeType":"APPLICATION_JSON","schema":{…}}}`. This field is new and I have not seen it in curl examples [UNVERIFIED in practice].
  - `responseJsonSchema` and `responseSchema` are mutually exclusive.
  - Supported keywords: `$defs`, `$ref`, `type`, `format`, `title`, `description`, `enum`, `items`, `prefixItems`, `min/maxItems`, `minimum`/`maximum`, `anyOf`/`oneOf`, `properties`, `additionalProperties`, `required`, `propertyOrdering`.

**Current Gemini model IDs (models page, Oct 2026):**
- **`gemini-3.5-flash-lite`** (cheap/fast, 1M in / 65,536 out, stable)
- **`gemini-3.8-flash`** (default recommendation)
- `gemini-3.6-flash`, `gemini-3.1-flash-lite`, `gemini-4-argon` (top tier)
- `gemini-3.1-pro-preview`
- 2.5 models are restricted to existing users.
- `gemini-3.1-flash-lite-preview` and `gemini-3-pro-preview` are **shut down**.
- An alias like `gemini-flash-latest` exists as a concept; `gemini-flash-lite-latest` is not listed [UNVERIFIED].

---

## LLM text APIs

### 6. Gemini generateContent (text)

Docs: https://ai.google.dev/api/generate-content · https://ai.google.dev/gemini-api/docs/thinking. Google now recommends the Interactions API for new work, but generateContent "remains fully supported" (https://ai.google.dev/gemini-api/docs/migrate-to-interactions).

`POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`, header `x-goog-api-key: <key>`.
```json
{ "systemInstruction": { "parts": [ { "text": "You are…" } ] },
  "contents": [ { "role": "user", "parts": [ { "text": "…" } ] } ],
  "generationConfig": { "temperature": 0.2, "maxOutputTokens": 2048,
    "responseMimeType": "application/json", "responseJsonSchema": { "type": "object", … },
    "thinkingConfig": { "thinkingLevel": "low", "includeThoughts": false } } }
```
- `thinkingConfig` fields are `includeThoughts`, `thinkingBudget` (int), and `thinkingLevel`.
  - The `thinkingLevel` enum is `MINIMAL|LOW|MEDIUM|HIGH`. Doc curl examples use lowercase; whether uppercase is also accepted is [UNVERIFIED], so send lowercase.
  - `thinkingLevel` is "Recommended for Gemini 3 or later models", and using it on earlier models errors.
  - `thinkingBudget` (0 = off, -1 = dynamic) is the 2.5-era knob. Don't send both.
- Per model:
  - **3.5-flash-lite** accepts minimal/low/medium/high, default **minimal**.
  - **3.8-flash** accepts low/medium/high, default **medium**, and has no minimal.
- **`temperature` is "no longer supported and is silently ignored on Gemini 3.6 Flash and newer"**, which includes 3.8-flash.
- Reading the response:
  - Concatenate `candidates[0].content.parts[i].text` for every part where `thought != true`.
  - Check `candidates[0].finishReason`: `STOP`, `MAX_TOKENS`, `SAFETY`, or the new `CONTINUATION`, which is followed by a `continuationToken`.
  - If `candidates` is empty, check `promptFeedback`.
  - `usageMetadata` has `promptTokenCount`, `candidatesTokenCount`, `thoughtsTokenCount`, and `totalTokenCount`.

**Streaming:** `POST …/{model}:streamGenerateContent?alt=sse`.
- Each SSE event is a single `data: {GenerateContentResponse chunk}` line followed by a blank line.
- There is no `event:` line and no `[DONE]` sentinel; the stream ends when the HTTP body closes.
- Each chunk's `candidates[0].content.parts[].text` is a text delta. The last chunk carries `finishReason` and `usageMetadata`.
```bash
curl "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent" \
  -H "x-goog-api-key: $GEMINI_KEY" -H 'Content-Type: application/json' \
  -d '{"contents":[{"parts":[{"text":"Summarise: …"}]}],"generationConfig":{"responseMimeType":"application/json","responseJsonSchema":{"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"]},"thinkingConfig":{"thinkingLevel":"minimal"}}}'
```
For reference, the Interactions-API equivalent: `POST /v1beta/interactions` with `{model, system_instruction, input, generation_config:{thinking_level}, response_format:{…}, stream:true}`. Its SSE events are `step.start`, `step.delta` (`delta.type:"text"`, `delta.text`), `step.stop`, and `interaction.completed`. The `response_format` shape differs between pages (object vs array) [UNVERIFIED]. Server-side state is on by default (`store:true`); send `store:false` for privacy.

### 7. Anthropic Messages API

Docs: https://platform.claude.com/docs/en/build-with-claude/structured-outputs · https://platform.claude.com/docs/en/about-claude/models/overview · https://platform.claude.com/docs/en/build-with-claude/streaming

`POST https://api.anthropic.com/v1/messages`, headers `x-api-key: <key>`, `anthropic-version: 2023-06-01`, `content-type: application/json`.

- **Structured outputs are GA.** Use `"output_config": {"format": {"type": "json_schema", "schema": {…}}}` with **no beta header**. `output_format` is deprecated; it only works with the beta header `structured-outputs-2025-11-13` and returns 400 without it.
- Schema rules:
  - Every object needs `"additionalProperties": false`.
  - Unsupported keywords return a 400: `minimum`/`maximum`/`multipleOf`, `minLength`/`maxLength`, `minItems` > 1, and recursive schemas.
  - `enum`, `const`, `anyOf`, `$ref`/`$defs`, simple `pattern`, and the common string formats are supported.
- Current IDs, all dateless pinned snapshots:

| Model ID | Price per MTok (in / out) | Notes |
|---|---|---|
| **`claude-haiku-5-5`** | from $0.10 / $0.50 (up to 100K-token prompts) | Fastest |
| `claude-sonnet-5-5` | $2 / $10 | |
| `claude-opus-5-5` | $4 / $20 | |
| `claude-fable-5-1` | $10 / $50 | |

  All four have a 1M context window and 128K max output.
- **Thinking:**
  - Thinking is adaptive and **on by default**. Haiku 5.5's default effort is `medium`.
  - For cheap/fast calls, set `"output_config": {"effort": "low", "format": …}`.
  - `thinking: {type:"disabled"}` is accepted on Haiku 5.5 only at effort ≤ high. It returns a **400** on Opus 5.5 and Sonnet 5.5.
  - `temperature` returns a 400 on these models if set to a non-default value. Don't send it.
  - Assistant prefill is rejected; use `output_config.format` instead.
- **Reading the response:**
  - Collect `content[]` blocks where `type == "text"` and read their `.text`. Skip `thinking` blocks; the docs say to find the text block by type rather than reading `content[0]`.
  - Check `stop_reason`: `end_turn`, `max_tokens` (the JSON may be truncated), or `refusal` (the output may not match the schema).
- **Streaming:** add `"stream": true`. The SSE lines look like `event: <name>` followed by `data: {json}`. Event order:
  1. `message_start`
  2. `content_block_start`
  3. (`ping`)
  4. `content_block_delta`*
  5. `content_block_stop`
  6. `message_delta`, which carries `stop_reason` and `usage.output_tokens`
  7. `message_stop`

  Text arrives as `delta.type == "text_delta"` in `delta.text`. Ignore `thinking_delta` and `signature_delta`. Errors arrive in-stream as `event: error` with a body like `{"type":"error","error":{"type":"overloaded_error",…}}`.
```bash
curl https://api.anthropic.com/v1/messages -H "x-api-key: $ANTHROPIC_API_KEY" -H "anthropic-version: 2023-06-01" -H "content-type: application/json" \
  -d '{"model":"claude-haiku-5-5","max_tokens":2048,"system":"You extract tasks.","output_config":{"effort":"low","format":{"type":"json_schema","schema":{"type":"object","properties":{"tasks":{"type":"array","items":{"type":"string"}}},"required":["tasks"],"additionalProperties":false}}},"messages":[{"role":"user","content":"…"}]}'
```

### 8. OpenAI-compatible chat completions

Request shape for all four providers: `POST …/chat/completions`, `Authorization: Bearer <key>`, body `{model, messages, stream, response_format}`.

Streaming format is the same everywhere:
- Each SSE line is `data: {"choices":[{"index":0,"delta":{"content":"…"},"finish_reason":null}]}`.
- The stream ends with `data: [DONE]`.
- With `stream_options:{include_usage:true}`, a final chunk carries `usage` (on OpenAI its `choices` is empty).
- Skip lines starting with `:`; these are SSE comments (OpenRouter sends keep-alive comments).

Structured outputs: `"response_format":{"type":"json_schema","json_schema":{"name":"x","strict":true,"schema":{…}}}`.

| Provider | Base URL | json_schema support |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | Yes. Strict mode needs every property in `required` and `additionalProperties:false`. Docs: https://developers.openai.com/api/docs/guides/structured-outputs |
| Groq | `https://api.groq.com/openai/v1` | Yes, but **only** on `openai/gpt-oss-20b`, `openai/gpt-oss-120b`, `qwen/qwen3.8-27b`, `meta-models/muse-spark-open` (strict or best-effort). Other models only get `{"type":"json_object"}`. **Streaming and tool use are not supported together with structured outputs**, and best-effort mode can return a 400 on mismatch. Docs: https://console.groq.com/docs/structured-outputs |
| OpenRouter | `https://openrouter.ai/api/v1` | Yes, per endpoint/provider. Add `"provider":{"require_parameters":true}` so requests only route to endpoints that honour it. Streaming works. Docs: https://openrouter.ai/docs/features/structured-outputs |
| Ollama | `http://localhost:11434/v1` (local, key ignored) / `https://ollama.com/v1` (cloud, `Authorization: Bearer $OLLAMA_API_KEY`) | `response_format` is listed as supported. The docs confirm structured outputs work "via `response_format`" but show no `json_schema` example [UNVERIFIED exact shape; the native `/api/chat` takes `format: <schema>`]. Docs: https://docs.ollama.com/api/openai-compatibility |

- **OpenAI models (Oct 2026):**
  - **`gpt-6-luna`** is the cheapest: $0.10 in / $0.50 out per MTok, 1.05M context, and it supports Chat Completions, structured outputs, and streaming. `reasoning_effort` accepts `none|low|medium|high|xhigh|max`, default `medium`; use `none` or `low` for cheap text.
  - `gpt-6.1-sol` costs $2 / $10. `gpt-6-astra` costs $10 / $50.
  - There is no nano tier.
  - Whether `temperature` is accepted on gpt-6-luna is [UNVERIFIED]. Use `max_completion_tokens` rather than `max_tokens` for reasoning models.
  - Docs: https://developers.openai.com/api/docs/models/gpt-6-luna
- **OpenRouter extras:**
  - Optional headers: `HTTP-Referer: <app url>` and `X-OpenRouter-Title: EchoMind` (`X-Title` is still accepted).
  - Provider object: `"provider":{"zdr":true,"data_collection":"deny","require_parameters":true,"allow_fallbacks":true}`.
  - `zdr:true` will "Restrict routing to only ZDR (Zero Data Retention) endpoints". It can only turn ZDR on, never override an account-level ZDR setting.
  - Mid-stream errors show up as a `choices[].error {code, message}` object.
  - Docs: https://openrouter.ai/docs/guides/routing/provider-selection · https://openrouter.ai/docs/api/reference/overview
```bash
curl https://openrouter.ai/api/v1/chat/completions -H "Authorization: Bearer $OR_KEY" -H "X-OpenRouter-Title: EchoMind" -H 'Content-Type: application/json' \
  -d '{"model":"openai/gpt-6-luna","stream":true,"provider":{"zdr":true,"require_parameters":true},"messages":[{"role":"user","content":"…"}],"response_format":{"type":"json_schema","json_schema":{"name":"out","strict":true,"schema":{"type":"object","properties":{"a":{"type":"string"}},"required":["a"],"additionalProperties":false}}}}'
```
(The OpenRouter slug `openai/gpt-6-luna` is [UNVERIFIED].)

### 9. Gemini embeddings

Docs: https://ai.google.dev/gemini-api/docs/embeddings · https://ai.google.dev/api/embeddings

- Models:
  - **`gemini-embedding-2`** is multimodal, takes up to 8,192 input tokens, and **does not accept `taskType`**. Instead, put the task in the text: `task: search result | query: {q}` for queries and `title: {t or none} | text: {doc}` for documents.
  - **ID conflict:** the embeddings guide says `gemini-embedding-2` (Stable); the models page says `gemini-embedding-2-preview`. Try the first and fall back to the second.
  - `gemini-embedding-001` is text-only, takes up to 2,048 tokens, and uses `taskType`: `RETRIEVAL_QUERY|RETRIEVAL_DOCUMENT|SEMANTIC_SIMILARITY|CLASSIFICATION|CLUSTERING|CODE_RETRIEVAL_QUERY|QUESTION_ANSWERING|FACT_VERIFICATION`. Optional `title` is only used with RETRIEVAL_DOCUMENT.
- Dimensions: `outputDimensionality` from 128 to 3072. The default is 3072; 768 and 1536 are recommended. v2 auto-normalizes truncated vectors; **with 001 you must L2-normalize** them yourself.
- `POST …/v1beta/models/gemini-embedding-2:embedContent` with `{"content":{"parts":[{"text":"…"}]},"outputDimensionality":768}`. Returns `{"embedding":{"values":[…]}}`.
- `POST …/models/gemini-embedding-2:batchEmbedContents` with `{"requests":[{"model":"models/gemini-embedding-2","content":{…},"outputDimensionality":768}, …]}`. Returns `{"embeddings":[{"values":[…]}, …]}`.
```bash
curl https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-2:embedContent -H "x-goog-api-key: $GEMINI_KEY" \
  -H 'Content-Type: application/json' -d '{"content":{"parts":[{"text":"task: search result | query: dentist appointment"}]},"outputDimensionality":768}'
```
