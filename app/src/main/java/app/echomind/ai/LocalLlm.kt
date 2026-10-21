package app.echomind.ai

import android.content.Context
import android.util.Log
import app.echomind.models.ModelManager
import app.echomind.util.Js
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * The on-phone model, run by Google's LiteRT-LM. One model is loaded at a
 * time, one request runs at a time, and the model is unloaded after a few
 * idle minutes so 1.5–2.5 GB of RAM is not held all day (low-memory kills of
 * the whole app were the main background failure before).
 *
 * Output is constrained to the JSON schema by the runtime's grammar, so the
 * model cannot ramble or break the format.
 */
class LocalLlm(
    private val context: Context,
    private val models: ModelManager,
    private val scope: CoroutineScope,
    private val modelId: () -> String,
    private val backend: () -> String,
    private val threads: () -> Int,
) : Llm {
    override val id = "local"
    override val local = true
    override val promptBudgetChars = 7_000

    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedKey: String? = null
    private var idleJob: Job? = null

    fun installedModel(): String? = listOf(modelId(), "gemma-4-e2b", "qwen3.5-2b", "qwen3.5-0.8b", "gemma-4-e4b").firstOrNull { models.isInstalled(it) }

    private fun engine(): Engine {
        val id = installedModel() ?: throw LlmException("No on-device AI model is downloaded. Get one in Settings → On-device models.", retryable = false)
        val file = models.path(id)!!
        val useGpu = backend() == "gpu"
        val key = "$id|$useGpu|${threads()}"
        engine?.let { if (loadedKey == key) return it }
        engine?.close()
        engine = null
        val cfg = EngineConfig(
            modelPath = file.absolutePath,
            backend = if (useGpu) Backend.GPU() else Backend.CPU(threadCount = threads()),
            maxNumTokens = 4096,
            cacheDir = context.cacheDir.absolutePath,
        )
        val started = System.currentTimeMillis()
        return Engine(cfg).also {
            it.initialize()
            Log.i(TAG, "loaded $id (${if (useGpu) "GPU" else "CPU×${threads()}"}) in ${System.currentTimeMillis() - started} ms")
            engine = it
            loadedKey = key
        }
    }

    private fun scheduleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            lock.withLock {
                engine?.close()
                engine = null
                loadedKey = null
                Log.i(TAG, "unloaded after idle")
            }
        }
    }

    suspend fun release() = lock.withLock {
        idleJob?.cancel()
        engine?.close(); engine = null; loadedKey = null
    }

    override suspend fun json(req: LlmRequest): JsonObject = withContext(Dispatchers.Default) {
        lock.withLock {
            idleJob?.cancel()
            try {
                val e = engine()
                val text = runCatching { generate(e, req, constrained = req.schema != null) }.getOrElse { first ->
                    Log.w(TAG, "constrained run failed: ${first.message}")
                    // Grammar-constrained decoding occasionally stalls on a schema;
                    // a plain run plus lenient parsing is the fallback.
                    generate(e, req, constrained = false)
                }
                Js.parseLenient(text) ?: run {
                    val retry = generate(e, req, constrained = false)
                    Js.parseLenient(retry) ?: throw LlmException("The on-device model did not return usable output")
                }
            } finally {
                scheduleRelease()
            }
        }
    }

    private suspend fun generate(e: Engine, req: LlmRequest, constrained: Boolean): String {
        val system = if (constrained || req.schema == null) req.system
        else req.system + "\n\nReply with only a JSON object matching this schema:\n" + req.schema
        val conversation = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = req.temperature),
                enableResponseFormat = constrained,
                maxOutputToken = req.maxTokens,
            ),
        )
        return conversation.use { c ->
            // The native call blocks; a watchdog cancels it if it runs away.
            val watchdog = scope.launch { delay(TIMEOUT_MS); c.cancelProcess() }
            try {
                val reply = if (constrained) {
                    c.sendMessage(Contents.of(req.user), responseFormat = ResponseFormat.json(req.schema.toString()))
                } else {
                    c.sendMessage(Contents.of(req.user))
                }
                reply.toString()
            } finally {
                watchdog.cancel()
            }
        }
    }

    override fun stream(req: LlmRequest): Flow<String> = flow {
        lock.withLock {
            idleJob?.cancel()
            try {
                val e = engine()
                val conversation = e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(req.system),
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = req.temperature),
                        maxOutputToken = req.maxTokens,
                    ),
                )
                conversation.use { c ->
                    try {
                        c.sendMessageAsync(Contents.of(req.user)).collect { emit(it.toString()) }
                    } finally {
                        // Stops generation if the reader went away mid-answer.
                        runCatching { c.cancelProcess() }
                    }
                }
            } finally {
                scheduleRelease()
            }
        }
    }.flowOn(Dispatchers.Default)

    data class Bench(val model: String, val backend: String, val loadMs: Long, val firstTokenMs: Long, val chunksPerSec: Double)

    /** Loads the model fresh and times a short answer, so CPU and GPU can be compared on this phone. */
    suspend fun benchmark(): Bench = withContext(Dispatchers.Default) {
        lock.withLock {
            idleJob?.cancel()
            engine?.close(); engine = null; loadedKey = null
            try {
                val t0 = System.currentTimeMillis()
                val e = engine()
                val loadMs = System.currentTimeMillis() - t0
                val c = e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3), maxOutputToken = 120))
                var first = 0L
                var chunks = 0
                val t1 = System.currentTimeMillis()
                c.use {
                    it.sendMessageAsync(Contents.of("In three short sentences, say what a good morning routine looks like.")).collect {
                        if (first == 0L) first = System.currentTimeMillis() - t1
                        chunks++
                    }
                }
                val genMs = (System.currentTimeMillis() - t1 - first).coerceAtLeast(1)
                Bench(installedModel() ?: "", if (backend() == "gpu") "GPU" else "CPU × ${threads()}", loadMs, first, chunks * 1000.0 / genMs)
            } finally {
                scheduleRelease()
            }
        }
    }

    companion object {
        private const val TAG = "EchoLocalLlm"
        const val IDLE_RELEASE_MS = 3 * 60_000L
        const val TIMEOUT_MS = 8 * 60_000L
    }
}
