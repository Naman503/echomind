package app.echomind.stt

import android.util.Base64
import app.echomind.capture.Wav
import app.echomind.core.Cloud
import app.echomind.core.Feature
import app.echomind.core.Secrets
import app.echomind.util.Js
import app.echomind.util.arr
import app.echomind.util.dbl
import app.echomind.util.obj
import app.echomind.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Cloud transcription. Every engine sends only the speech piece it was given
 * (silence was already cut away), through [Cloud.send] so the indicator and the
 * data ledger always know.
 */
abstract class CloudSpeech(
    protected val cloud: Cloud,
    protected val secrets: Secrets,
    private val keyName: String,
    private val feature: () -> Feature,
) : SpeechEngine {
    protected fun key(): String = secrets.get(keyName) ?: throw SpeechException("no_key", "Add your $keyName API key in Settings → Providers", retryable = false)

    protected fun wavSlice(req: SpeechRequest): ByteArray {
        val samples = Wav.readSamples(req.file, req.startMs, req.endMs)
        val tmp = File.createTempFile("piece", ".wav")
        try {
            Wav.write(tmp, samples)
            return tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    protected suspend fun post(request: Request, bytes: Long, what: String): JsonObject = withContext(Dispatchers.IO) {
        cloud.send(feature(), id, bytes, what) {
            cloud.http.newCall(request).execute().use { r ->
                val body = r.body.string()
                if (!r.isSuccessful) {
                    val retry = r.code == 429 || r.code >= 500
                    throw SpeechException("http_${r.code}", "$id: HTTP ${r.code} ${body.take(200)}", retryable = retry)
                }
                Js.parseObject(body)
            }
        }
    }

    protected fun lang(req: SpeechRequest) = req.languages.firstOrNull()?.substringBefore('-')
}

/** Sarvam AI — best measured Hindi/Hinglish. REST takes ≤30 s per request. */
class SarvamSpeech(cloud: Cloud, secrets: Secrets, feature: () -> Feature) : CloudSpeech(cloud, secrets, "sarvam", feature) {
    override val id = "sarvam"
    override val maxPieceMs = 28_000L

    override suspend fun transcribe(req: SpeechRequest): List<SpokenLine> {
        val wav = wavSlice(req)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "piece.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "saaras:v4")
            .addFormDataPart("mode", "codemix")
            .addFormDataPart("language_code", if (req.languages.size == 1) req.languages.first() else "unknown")
            .addFormDataPart("with_timestamps", "true")
            .build()
        val json = post(
            Request.Builder().url("https://api.sarvam.ai/speech-to-text").header("api-subscription-key", key()).post(body).build(),
            wav.size.toLong(), "${(req.endMs - req.startMs) / 1000}s of speech",
        )
        val language = json.str("language_code")
        val ts = json.obj("timestamps")
        val chunks = ts?.arr("words")?.map { it.str().orEmpty() }.orEmpty()
        val starts = ts?.arr("start_time_seconds")?.map { it.dbl() }.orEmpty()
        val ends = ts?.arr("end_time_seconds")?.map { it.dbl() }.orEmpty()
        if (chunks.isNotEmpty() && chunks.size == starts.size && chunks.size == ends.size) {
            return chunks.indices.mapNotNull { i ->
                val t = chunks[i].trim()
                if (t.isEmpty()) null
                else SpokenLine(req.startMs + (starts[i] * 1000).toLong(), req.startMs + (ends[i] * 1000).toLong(), t, lang = language)
            }
        }
        val text = json.str("transcript")?.trim().orEmpty()
        return if (text.isEmpty()) emptyList() else listOf(SpokenLine(req.startMs, req.endMs, text, lang = language))
    }
}

/** ElevenLabs Scribe v2 — word timings and speakers. */
class ElevenLabsSpeech(cloud: Cloud, secrets: Secrets, feature: () -> Feature) : CloudSpeech(cloud, secrets, "elevenlabs", feature) {
    override val id = "elevenlabs"
    override val maxPieceMs = 120_000L

    override suspend fun transcribe(req: SpeechRequest): List<SpokenLine> {
        val wav = wavSlice(req)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model_id", "scribe_v2")
            .addFormDataPart("file", "piece.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("diarize", "true")
            .addFormDataPart("timestamps_granularity", "word")
            .addFormDataPart("tag_audio_events", "false")
            .apply { if (req.languages.size == 1) addFormDataPart("language_code", lang(req)!!) }
            .build()
        val json = post(
            Request.Builder().url("https://api.elevenlabs.io/v1/speech-to-text").header("xi-api-key", key()).post(body).build(),
            wav.size.toLong(), "${(req.endMs - req.startMs) / 1000}s of speech",
        )
        val language = json.str("language_code")
        val words = json.arr("words").orEmpty().mapNotNull { it as? JsonObject }.filter { it.str("type") == "word" }
        if (words.isEmpty()) return json.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(SpokenLine(req.startMs, req.endMs, it, lang = language)) }.orEmpty()
        return groupWords(words.map { w ->
            Word(w.str("text").orEmpty(), w.dbl("start") ?: 0.0, w.dbl("end") ?: 0.0, w.str("speaker_id"))
        }, req.startMs, language)
    }
}

/** Deepgram Nova-3 — Hindi/English code-switching, utterances with speakers. */
class DeepgramSpeech(cloud: Cloud, secrets: Secrets, feature: () -> Feature) : CloudSpeech(cloud, secrets, "deepgram", feature) {
    override val id = "deepgram"
    override val maxPieceMs = 120_000L

    override suspend fun transcribe(req: SpeechRequest): List<SpokenLine> {
        val wav = wavSlice(req)
        val language = if (req.languages.size == 1) lang(req) else "multi"
        val url = "https://api.deepgram.com/v1/listen?model=nova-3&language=$language&diarize_model=latest&punctuate=true&utterances=true&smart_format=true"
        val json = post(
            Request.Builder().url(url).header("Authorization", "Token ${key()}").post(wav.toRequestBody("audio/wav".toMediaType())).build(),
            wav.size.toLong(), "${(req.endMs - req.startMs) / 1000}s of speech",
        )
        val utterances = json.obj("results")?.arr("utterances").orEmpty().mapNotNull { it as? JsonObject }
        return utterances.mapNotNull { u ->
            val t = u.str("transcript")?.trim().orEmpty()
            if (t.isEmpty()) null
            else SpokenLine(
                req.startMs + ((u.dbl("start") ?: 0.0) * 1000).toLong(),
                req.startMs + ((u.dbl("end") ?: 0.0) * 1000).toLong(),
                t,
                speaker = u["speaker"]?.let { "S${it.str()?.toIntOrNull()?.plus(1) ?: 1}" },
            )
        }
    }
}

/** Groq-hosted Whisper large-v3-turbo — cheapest and fastest. */
class GroqSpeech(cloud: Cloud, secrets: Secrets, feature: () -> Feature) : CloudSpeech(cloud, secrets, "groq", feature) {
    override val id = "groq_stt"
    override val maxPieceMs = 120_000L

    override suspend fun transcribe(req: SpeechRequest): List<SpokenLine> {
        val wav = wavSlice(req)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "piece.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("temperature", "0")
            .apply { if (req.languages.size == 1) addFormDataPart("language", lang(req)!!) }
            .build()
        val json = post(
            Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions").header("Authorization", "Bearer ${key()}").post(body).build(),
            wav.size.toLong(), "${(req.endMs - req.startMs) / 1000}s of speech",
        )
        return json.arr("segments").orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { s ->
            val t = s.str("text")?.trim().orEmpty()
            val noSpeech = s.dbl("no_speech_prob") ?: 0.0
            if (t.isEmpty() || noSpeech > 0.7 || SherpaSpeech.isHallucination(t)) null
            else SpokenLine(req.startMs + ((s.dbl("start") ?: 0.0) * 1000).toLong(), req.startMs + ((s.dbl("end") ?: 0.0) * 1000).toLong(), t, lang = json.str("language"))
        }
    }
}

/**
 * Gemini: audio in, timed lines out, through a general model with a JSON
 * schema (the dedicated transcribe model has been unreliable over REST).
 */
class GeminiSpeech(cloud: Cloud, secrets: Secrets, feature: () -> Feature, private val model: () -> String) : CloudSpeech(cloud, secrets, "gemini", feature) {
    override val id = "gemini_stt"
    override val maxPieceMs = 120_000L

    override suspend fun transcribe(req: SpeechRequest): List<SpokenLine> {
        val wav = wavSlice(req)
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("lines") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("start") { put("type", "number"); put("description", "seconds from the start of the clip") }
                            putJsonObject("end") { put("type", "number") }
                            putJsonObject("speaker") { put("type", "string"); put("description", "S1, S2… by voice") }
                            putJsonObject("text") { put("type", "string") }
                        }
                        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("start")); add(kotlinx.serialization.json.JsonPrimitive("end")); add(kotlinx.serialization.json.JsonPrimitive("speaker")); add(kotlinx.serialization.json.JsonPrimitive("text")) }
                    }
                }
            }
            putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("lines")) }
        }
        val prompt = "Transcribe this audio verbatim, one line per utterance, with start/end times in seconds and a speaker label per distinct voice. " +
            "Speech may be ${req.languages.joinToString(" / ")} or code-mixed Hindi-English: write Hindi words in Devanagari and English words in Latin script, exactly as spoken. " +
            "Do not translate, summarise or invent. Return no lines for silence, music or noise."
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    putJsonArray("parts") {
                        add(buildJsonObject { putJsonObject("inlineData") { put("mimeType", "audio/wav"); put("data", Base64.encodeToString(wav, Base64.NO_WRAP)) } })
                        add(buildJsonObject { put("text", prompt) })
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseJsonSchema", schema)
                put("temperature", 0)
            }
        }.toString()
        val json = post(
            Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/${model()}:generateContent")
                .header("x-goog-api-key", key()).post(body.toRequestBody(JSON)).build(),
            wav.size.toLong(), "${(req.endMs - req.startMs) / 1000}s of speech",
        )
        val text = Js.geminiText(json) ?: return emptyList()
        return Js.parseLenient(text)?.arr("lines").orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { l ->
            val t = l.str("text")?.trim().orEmpty()
            if (t.isEmpty()) null
            else SpokenLine(req.startMs + ((l.dbl("start") ?: 0.0) * 1000).toLong(), req.startMs + ((l.dbl("end") ?: 0.0) * 1000).toLong(), t, speaker = l.str("speaker"))
        }
    }

    companion object {
        val JSON = "application/json".toMediaType()
    }
}

data class Word(val text: String, val start: Double, val end: Double, val speaker: String?)

/** Words → lines: a new line on a speaker change, a pause over 0.8 s, or ~25 words. Speakers become S1, S2… in order of appearance. */
fun groupWords(words: List<Word>, offsetMs: Long, lang: String?): List<SpokenLine> {
    val names = LinkedHashMap<String, String>()
    val out = ArrayList<SpokenLine>()
    var cur = ArrayList<Word>()
    fun flush() {
        if (cur.isEmpty()) return
        val text = cur.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+([,.?!।])"), "$1").trim()
        val speaker = cur.first().speaker?.let { s -> names.getOrPut(s) { "S${names.size + 1}" } }
        if (text.isNotEmpty()) out.add(SpokenLine(offsetMs + (cur.first().start * 1000).toLong(), offsetMs + (cur.last().end * 1000).toLong(), text, speaker = speaker, lang = lang))
        cur = ArrayList()
    }
    for (w in words) {
        val last = cur.lastOrNull()
        if (last != null && (w.speaker != last.speaker || w.start - last.end > 0.8 || cur.size >= 25)) flush()
        cur.add(w)
    }
    flush()
    return out
}
