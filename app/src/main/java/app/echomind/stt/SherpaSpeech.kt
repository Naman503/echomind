package app.echomind.stt

import android.util.Log
import app.echomind.capture.Wav
import app.echomind.models.ModelManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Open speech models run by EchoMind itself through sherpa-onnx: Whisper
 * (99 languages, handles Hindi/English mixing), Parakeet (fast English with
 * word timings) and Omnilingual (every Indian language). CPU only — the
 * Dimensity 7200's NPU is not supported by any of these runtimes.
 *
 * Silero VAD (when installed) cuts each piece into utterances, so lines get
 * real start/end times and Whisper never sees its 30-second limit.
 */
class SherpaSpeech(private val models: ModelManager, private val modelId: () -> String, private val threads: () -> Int) : SpeechEngine {
    override val id = "sherpa"
    override val maxPieceMs = 60_000L

    private val lock = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var loadedKey: String? = null
    private var vad: Vad? = null
    @Volatile private var lastUse = 0L

    fun installedModel(): String? = listOf(modelId(), "whisper-small", "omnilingual-300m", "whisper-turbo", "parakeet-v2")
        .firstOrNull { models.isInstalled(it) }

    override suspend fun transcribe(req: SpeechRequest): List<SpokenLine> = withContext(Dispatchers.Default) {
        lock.withLock {
            val model = installedModel() ?: throw SpeechException("no_model", "No on-device speech model is downloaded", retryable = false)
            val rec = recognizerFor(model, req.languages)
            lastUse = System.currentTimeMillis()
            val samples = Wav.readSamples(req.file, req.startMs, req.endMs)
            val floats = FloatArray(samples.size) { samples[it] / 32768f }
            val lines = ArrayList<SpokenLine>()
            for ((offset, chunk) in segments(floats)) {
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(chunk, Wav.SAMPLE_RATE)
                    rec.decode(stream)
                    val r = rec.getResult(stream)
                    val text = r.text.trim()
                    if (text.isEmpty() || isHallucination(text)) continue
                    val startMs = req.startMs + offset * 1000L / Wav.SAMPLE_RATE
                    val endMs = startMs + chunk.size * 1000L / Wav.SAMPLE_RATE
                    lines.add(SpokenLine(startMs, endMs, text, lang = r.lang.ifBlank { null }?.trim('<', '>', '|')))
                } finally {
                    stream.release()
                }
            }
            lines
        }
    }

    /** Speech segments as (sampleOffset, samples), ≤ 25 s each. */
    private fun segments(floats: FloatArray): List<Pair<Int, FloatArray>> {
        val v = vadOrNull() ?: return chunked(floats)
        v.reset()
        val out = ArrayList<Pair<Int, FloatArray>>()
        var i = 0
        val window = 512
        while (i + window <= floats.size) {
            v.acceptWaveform(floats.copyOfRange(i, i + window))
            while (!v.empty()) {
                val seg = v.front()
                out.add(seg.start to seg.samples)
                v.pop()
            }
            i += window
        }
        v.flush()
        while (!v.empty()) {
            val seg = v.front()
            out.add(seg.start to seg.samples)
            v.pop()
        }
        // VAD found nothing but the energy detector did: trust the piece.
        return out.ifEmpty { chunked(floats) }
    }

    private fun chunked(floats: FloatArray): List<Pair<Int, FloatArray>> {
        val size = 25 * Wav.SAMPLE_RATE
        return (floats.indices step size).map { it to floats.copyOfRange(it, minOf(floats.size, it + size)) }
    }

    private fun vadOrNull(): Vad? {
        vad?.let { return it }
        val file = models.path("silero-vad") ?: return null
        return try {
            Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = file.absolutePath,
                        threshold = 0.45f,
                        minSilenceDuration = 0.5f,
                        minSpeechDuration = 0.25f,
                        maxSpeechDuration = 25f,
                    ),
                    sampleRate = Wav.SAMPLE_RATE,
                    numThreads = 1,
                ),
            ).also { vad = it }
        } catch (e: Exception) {
            Log.w(TAG, "VAD failed to load", e); null
        }
    }

    private fun recognizerFor(model: String, languages: List<String>): OfflineRecognizer {
        val lang = if (languages.size == 1) languages.first().substringBefore('-') else ""
        val key = "$model|$lang|${threads()}"
        recognizer?.let { if (loadedKey == key) return it }
        recognizer?.release()
        recognizer = null
        val config = when {
            model.startsWith("whisper") -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = need(model) { "encoder" in it && it.endsWith(".onnx") },
                    decoder = need(model) { "decoder" in it && it.endsWith(".onnx") },
                    language = lang,
                    task = "transcribe",
                    tailPaddings = 500,
                ),
                tokens = need(model) { it.endsWith("tokens.txt") },
                modelType = "whisper",
            )
            model == "parakeet-v2" -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = need(model) { it.startsWith("encoder") },
                    decoder = need(model) { it.startsWith("decoder") },
                    joiner = need(model) { it.startsWith("joiner") },
                ),
                tokens = need(model) { it.endsWith("tokens.txt") },
                modelType = "nemo_transducer",
            )
            else -> OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = need(model) { it.startsWith("model") && it.endsWith(".onnx") }),
                tokens = need(model) { it.endsWith("tokens.txt") },
            )
        }.apply { numThreads = threads() }
        return OfflineRecognizer(config = OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = Wav.SAMPLE_RATE, featureDim = 80), modelConfig = config))
            .also { recognizer = it; loadedKey = key }
    }

    private fun need(model: String, test: (String) -> Boolean): String =
        models.find(model, test)?.absolutePath ?: throw SpeechException("bad_model", "Model files for $model are incomplete; reinstall it", retryable = false)

    /** Frees ~0.5–1 GB after a quiet minute; the next piece reloads it. */
    suspend fun releaseIfIdle(idleMs: Long = 60_000) = lock.withLock {
        if (recognizer != null && System.currentTimeMillis() - lastUse > idleMs) {
            recognizer?.release(); recognizer = null; loadedKey = null
            vad?.release(); vad = null
        }
    }

    companion object {
        private const val TAG = "EchoSherpa"
        /** Whisper's well-known inventions on silence and music. */
        private val junk = listOf(
            "thank you for watching", "thanks for watching", "subscribe", "please like", "[music]", "(music)",
            "♪", "[silence]", "you", "bye.", "thank you.",
        )
        fun isHallucination(text: String): Boolean {
            val t = text.lowercase().trim()
            return t.length < 2 || junk.any { t == it || (t.length < 40 && t.contains(it) && it.length > 6) }
        }
    }
}
