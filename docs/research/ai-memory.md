# AI, memory & search research (Oct 2026, iQOO Z7 Pro)

## On-device LLM
- **LiteRT-LM** `com.google.ai.edge.litertlm:litertlm-android:0.18.1` — Google's current on-device runtime (MediaPipe LLM Inference is maintenance-only). Kotlin API: `Engine(EngineConfig(modelPath, Backend.CPU(threads)|GPU(), cacheDir))`, `createConversation(ConversationConfig(systemInstruction, samplerConfig, enableResponseFormat=true, maxOutputToken))`, `sendMessage(..., responseFormat = ResponseFormat.json(schema))` (llguidance constrained decoding), streaming `sendMessageAsync` → Flow<Message>, tools via ToolSet. `EmbeddingEngine` for embeddings. No NPU on Dimensity 7200 (NeuroPilot list starts at 7300); benchmark CPU vs GPU (OpenCL) on first run — GPU is not always faster.
- Manifest: `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` (+ libvndksupport.so) for GPU.
- Models (ungated, Apache-2.0, litert-community on Hugging Face):
  - **Gemma 4 E2B-it** `gemma-4-E2B-it.litertlm` 2.58 GB — best fit; 140+ languages, function calling, 32k ctx; est. ~120–200 prefill / 8–14 decode tok/s here.
  - Qwen3.5-2B int8 1.97 GB (A/B for Hindi), Qwen3.5-0.8B 0.96 GB (fast tier), Qwen3-1.7B 0.98 GB.
  - Gemma 4 E4B 3.65 GB — overnight only.
- Rules for this phone: prompts ≤3k tokens, outputs ≤400; one narrow task per call; never run ASR and LLM together; close the engine after a batch.
- Gemini Nano (ML Kit GenAI) — not supported on this phone.

## Embeddings
**EmbeddingGemma 2 Text 270M** `.litertlm` (165 MB, Apache-2.0, ungated) via LiteRT-LM EmbeddingEngine, Matryoshka 256 dims, ~27 ms/text on flagship CPU. Prefixes: `task: search result | query: …` / `title: … | text: …`. Fallback multilingual-e5-small (ONNX).

## Storage & search
- Room 2.8.5 / Room 3.0.3; bundled SQLite (`androidx.sqlite:sqlite-bundled:2.7.1`) has FTS5 (unicode61 + trigram tokenizers). sqlite-vec is alpha → we brute-force int8 vectors in Kotlin instead (100k × 256 B = 25 MB, tens of ms).
- Hybrid retrieval: FTS5 bm25 top-50 + vector top-50 → Reciprocal Rank Fusion (k=60) × recency decay + entity boost. Trigram FTS catches Hinglish/ASR spelling variants (Rahul/Raahul/राहुल) + alias table.

## Memory architecture (borrowed)
- Mem0: per candidate fact, retrieve similar → LLM picks ADD / UPDATE / INVALIDATE / NOOP.
- Zep/Graphiti: bi-temporal facts (valid_from/valid_to, superseded_by) — invalidate, never delete.
- Letta/MemGPT: small always-in-prompt core memory (≤400 tokens) + searchable archive.
- A-MEM: linked notes that evolve. LangMem: nightly reflection.
- Pipeline: utterances (ground truth, FTS immediately) → episode close (gap > 3 min / ~1,500 words / 30 min) → one extraction call with **line ids L1..Ln** (model cites lines, code maps to timestamps) → deterministic entity linking → fact reconciliation → **note patches** (append/replace/strike per section, stored as revisions with reasons + source lines) → tasks into a review inbox → nightly: diary, entity summaries, core memory, suggestions with evidence.
- Ask: parse time range/entities → hybrid retrieval over utterances, chunks, facts → ±3 lines context ≤2.5k tokens → answer with [L#] citations validated in code.

## Cloud LLMs (BYOK, direct from phone)
| Provider | Model | $/1M in/out | Data |
|---|---|---|---|
| Gemini | gemini-3.5-flash-lite | 0.30 / 2.50 | free tier may train; paid not |
| Gemini | gemini-3.8-flash | 0.75 / 3.75 | same |
| Anthropic | claude-haiku-5-5 | 0.10 / 0.50 | not trained on by default |
| Anthropic | claude-sonnet-5-5 | 2 / 10 | same |
| OpenAI | gpt-5.4-nano / mini | 0.20/1.25, 0.75/4.50 | not trained on by default |
| Groq | gpt-oss-20b/120b | 0.075/0.30, 0.15/0.60 | check data controls |
| OpenRouter | any | pass-through | `provider: {zdr: true}` |
Typical day ≈ 80k in + 10k out tokens → $0.01–0.05/day. Send only retrieved context. Keys encrypted with an Android Keystore AES-GCM key.
