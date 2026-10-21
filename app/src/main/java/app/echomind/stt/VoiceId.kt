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
class VoiceId(private val context: Context, private val models: ModelManager) {
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

    /** Averages voice-prints from the enrolment clips and stores the result. */
    suspend fun enroll(clips: List<ShortArray>): Boolean {
        val prints = clips.mapNotNull { embed(it) }
        if (prints.size < 2) return false
        val avg = FloatArray(prints[0].size)
        for (p in prints) for (i in avg.indices) avg[i] += p[i] / prints.size
        prefs.edit().putString("me", normalize(avg).joinToString(",")).apply()
        return true
    }

    fun forget() = prefs.edit().remove("me").apply()

    private fun me(): FloatArray? = prefs.getString("me", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()

    /** Similarity to the owner's voice, or null if not enrolled. */
    fun meScore(print: FloatArray): Float? = me()?.takeIf { it.size == print.size }?.let { cosine(it, print) }

    fun isMe(print: FloatArray) = (meScore(print) ?: 0f) >= ME_THRESHOLD

    suspend fun release() = lock.withLock { extractor?.release(); extractor = null }

    companion object {
        const val ME_THRESHOLD = 0.55f
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
