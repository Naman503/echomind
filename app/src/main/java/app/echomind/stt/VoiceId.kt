package app.echomind.stt

import android.content.Context
import app.echomind.capture.Wav
import app.echomind.models.ModelManager
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * "Was that me?" — a voice-print of the owner, enrolled from a few short
 * recordings, compared with each transcript line. Lines that are not the
 * owner are grouped into Speaker 1, 2… per conversation, which the AI can
 * later name from context ("Rahul said…").
 *
 * Runs on the transcription path while the audio still exists; only the
 * 512-number voice-print is kept, never the audio.
 */
class VoiceId(private val context: Context, private val models: ModelManager, private val sensitivity: () -> String = { "normal" }) {
    private val lock = Mutex()
    private var extractor: SpeakerEmbeddingExtractor? = null
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    fun ready() = models.isInstalled("voice-id")
    fun enrolled() = prefs.contains("me")

    private fun load(): SpeakerEmbeddingExtractor? {
        extractor?.let { return it }
        val file = models.path("voice-id") ?: return null
        return SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = file.absolutePath, numThreads = 2)).also { extractor = it }
    }

    suspend fun embed(samples: ShortArray): FloatArray? = withContext(Dispatchers.Default) {
        if (samples.size < Wav.SAMPLE_RATE) return@withContext null // under 1 s says little about a voice
        lock.withLock {
            val ex = load() ?: return@withLock null
            val stream = ex.createStream()
            try {
                stream.acceptWaveform(FloatArray(samples.size) { samples[it] / 32768f }, Wav.SAMPLE_RATE)
                stream.inputFinished()
                if (!ex.isReady(stream)) null else normalize(ex.compute(stream))
            } finally {
                stream.release()
            }
        }
    }

    suspend fun embed(file: File, startMs: Long, endMs: Long): FloatArray? = embed(Wav.readSamples(file, startMs, endMs))

    /**
     * Learns the owner's voice from enrolment clips. Each clip is kept as its own
     * sample (a phone on the desk and in a pocket sound different), and the
     * threshold is calibrated from how alike the owner's own clips are.
     */
    suspend fun enroll(clips: List<ShortArray>, append: Boolean = false): Boolean {
        val prints = clips.mapNotNull { embed(it) }
        if (prints.size < (if (append) 1 else 2)) return false
        val bank = (if (append) bank("me_bank") else emptyList()) + prints
        save("me_bank", bank.takeLast(BANK))
        recompute()
        return true
    }

    /** "That line was me" / "that wasn't me": the voice model learns from corrections and confident lines. */
    fun learn(print: FloatArray, isMe: Boolean) {
        val key = if (isMe) "me_bank" else "me_neg"
        val bank = bank(key)
        if (bank.any { cosine(it, print) > 0.93f }) return // nothing new
        save(key, (bank + print).takeLast(BANK))
        if (isMe) recompute()
    }

    fun samples() = bank("me_bank").size
    fun corrections() = bank("me_neg").size

    fun forget() = prefs.edit().remove("me").remove("me_bank").remove("me_neg").remove("me_thr").apply()

    private fun recompute() {
        val bank = bank("me_bank")
        if (bank.isEmpty()) return
        val avg = FloatArray(bank[0].size)
        for (p in bank) for (i in avg.indices) avg[i] += p[i] / bank.size
        // Calibrate: a little below how alike the owner's own samples are, within sane bounds.
        val pairs = bank.indices.flatMap { i -> (i + 1 until bank.size).map { j -> cosine(bank[i], bank[j]) } }
        val thr = if (pairs.isEmpty()) ME_THRESHOLD else (pairs.sorted()[pairs.size / 5] - 0.08f).coerceIn(0.45f, 0.65f)
        prefs.edit().putString("me", normalize(avg).joinToString(",")).putFloat("me_thr", thr).apply()
    }

    private fun bank(key: String): List<FloatArray> = prefs.getString(key, null)?.split(';')?.mapNotNull { p ->
        p.split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray().takeIf { it.isNotEmpty() }
    } ?: listOfNotNull(if (key == "me_bank") me() else null)

    private fun save(key: String, list: List<FloatArray>) = prefs.edit().putString(key, list.joinToString(";") { it.joinToString(",") }).apply()

    private fun me(): FloatArray? = prefs.getString("me", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()

    /** The owner's calibrated threshold, shifted by the sensitivity setting. */
    fun threshold(): Float = prefs.getFloat("me_thr", ME_THRESHOLD) + when (sensitivity()) { "strict" -> 0.06f; "lenient" -> -0.06f; else -> 0f }

    /** Similarity to the owner's voice: the closer of the average and the best two samples. */
    fun meScore(print: FloatArray): Float? {
        val c = me()?.takeIf { it.size == print.size } ?: return null
        val top = bank("me_bank").filter { it.size == print.size }.map { cosine(it, print) }.sortedDescending().take(2)
        return maxOf(cosine(c, print), if (top.isEmpty()) 0f else top.average().toFloat())
    }

    private fun notMeScore(print: FloatArray): Float = bank("me_neg").filter { it.size == print.size }.maxOfOrNull { cosine(it, print) } ?: 0f

    /** Owner when close enough — and clearly closer than to voices you said weren't you. */
    fun isMe(print: FloatArray): Boolean {
        val s = meScore(print) ?: return false
        return s >= threshold() && s - notMeScore(print) > 0.04f
    }

    /** Confident enough to learn from by itself (well above the threshold). */
    fun surelyMe(print: FloatArray) = (meScore(print) ?: 0f) >= threshold() + 0.12f && isMe(print)

    suspend fun release() = lock.withLock { extractor?.release(); extractor = null }

    companion object {
        const val ME_THRESHOLD = 0.55f
        const val PERSON_THRESHOLD = 0.6f
        private const val BANK = 40
        const val SAME_THRESHOLD = 0.6f

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0f; var na = 0f; var nb = 0f
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na == 0f || nb == 0f) 0f else dot / (sqrt(na) * sqrt(nb))
        }

        fun normalize(v: FloatArray): FloatArray {
            var n = 0f
            for (x in v) n += x * x
            n = sqrt(n)
            return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
        }

        /**
         * Greedy clustering of voice-prints into speakers: each print joins the
         * closest existing speaker above [SAME_THRESHOLD], else starts a new one.
         * Returns a speaker index per input (null input → null).
         */
        fun cluster(prints: List<FloatArray?>): List<Int?> {
            val centroids = ArrayList<FloatArray>()
            val counts = ArrayList<Int>()
            return prints.map { p ->
                if (p == null) return@map null
                var best = -1
                var bestScore = SAME_THRESHOLD
                centroids.forEachIndexed { i, c -> val s = cosine(c, p); if (s > bestScore) { bestScore = s; best = i } }
                if (best < 0) {
                    centroids.add(p.copyOf()); counts.add(1); centroids.size - 1
                } else {
                    val n = counts[best]
                    val c = centroids[best]
                    for (i in c.indices) c[i] = (c[i] * n + p[i]) / (n + 1)
                    counts[best] = n + 1
                    best
                }
            }
        }
    }
}
