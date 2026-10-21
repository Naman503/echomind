package app.echomind.brain

import android.content.Context
import android.util.Log
import app.echomind.data.Db
import app.echomind.data.VectorRow
import app.echomind.models.ModelManager
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Meaning-based search vectors from EmbeddingGemma 2 (on the phone, 165 MB),
 * truncated to 256 dimensions and stored as int8. Lets "the dentist thing"
 * find "doctor ne teeth ke liye Friday bola". Unloaded a minute after use.
 */
class Embedder(private val context: Context, private val models: ModelManager, private val db: Db, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private var engine: EmbeddingEngine? = null
    private var idle: Job? = null

    fun ready() = models.isInstalled(MODEL)

    private fun engine(): EmbeddingEngine {
        engine?.let { return it }
        val file = models.path(MODEL) ?: error("embedding model not installed")
        return EmbeddingEngine(EmbeddingEngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(threadCount = 2), cacheDir = context.cacheDir.absolutePath))
            .also { it.initialize(); engine = it }
    }

    private suspend fun <T> withEngine(block: (EmbeddingEngine) -> T): T? = withContext(Dispatchers.Default) {
        if (!ready()) return@withContext null
        lock.withLock {
            idle?.cancel()
            try {
                block(engine())
            } catch (e: Exception) {
                Log.w("EchoEmbedder", "embedding failed", e); null
            } finally {
                idle = scope.launch {
                    delay(60_000)
                    lock.withLock { engine?.close(); engine = null }
                }
            }
        }
    }

    private val options = EmbeddingOptions(normalize = true, outputSize = DIMS)

    suspend fun query(text: String): FloatArray? = withEngine {
        it.computeEmbedding(listOf(InputData.Text("task: search result | query: $text")), options).embedding
    }

    suspend fun documents(texts: List<Pair<String, String>>): List<FloatArray>? = withEngine { e ->
        texts.map { (title, text) -> e.computeEmbedding(listOf(InputData.Text("title: $title | text: $text")), options).embedding }
    }

    /** Embeds and stores [items] (kind, ownerId, title, text). */
    suspend fun index(items: List<Item>) {
        if (items.isEmpty() || !ready()) return
        for (batch in items.chunked(16)) {
            val vectors = documents(batch.map { it.title to it.text.take(1_500) }) ?: return
            val now = System.currentTimeMillis()
            batch.zip(vectors).forEach { (item, v) ->
                val (bytes, scale) = Vectors.quantize(v)
                db.vectors().put(VectorRow(item.kind, item.ownerId, bytes, scale, MODEL, now))
            }
        }
    }

    data class Item(val kind: String, val ownerId: Long, val title: String, val text: String)

    companion object {
        const val MODEL = "embeddinggemma-2"
        const val DIMS = 256
    }
}
