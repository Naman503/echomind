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
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.NonCancellable
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
    private val wantedContext: () -> Int = { 4096 },
) : Llm {
    override val id = "local"
    override val local = true

    /** Context size the model actually loaded with; a model that refuses a bigger one runs at 4096. */
    @Volatile var contextTokens: Int = 4096
        private set
    private var refusedContext = 0

    /**
     * Characters of transcript one request can carry. The context holds the prompt
     * and the answer together, and Hindi in Devanagari costs about twice the
     * tokens of English, so this stays well inside it.
     */
    override val promptBudgetChars get() = ((wantedContext().coerceAtMost(if (refusedContext > 0) 4096 else Int.MAX_VALUE).coerceAtLeast(4096) - 2_600) * 1.2).toInt()

    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedKey: String? = null
    private var idleJob: Job? = null

    fun installedModel(): String? = listOf(modelId(), "gemma-4-e2b", "qwen3.5-2b", "qwen3.5-0.8b", "gemma-4-e4b").firstOrNull { models.isInstalled(it) }

    private fun engine(): Engine {
        val id = installedModel() ?: throw LlmException("No on-device AI model is downloaded. Get one in Settings → On-device models.", retryable = false)
        val file = models.path(id)!!
        val useGpu = backend() == "gpu"
        // A bigger context needs more memory; when the phone is short of it, load at 4K rather than be killed.
        val freeMb = runCatching {
            android.app.ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }.availMem / (1024 * 1024)
        }.getOrDefault(Long.MAX_VALUE)
        val ctx = wantedContext().coerceIn(2048, 32768).let { if (refusedContext in 1..it) 4096 else it }.let { if (it > 4096 && freeMb < 2_500) 4096.also { if (loadedKey?.endsWith("|4096") != true) Log.i(TAG, "only $freeMb MB free: loading at 4096 tokens") } else it }
        val key = "$id|$useGpu|${threads()}|$ctx"
        engine?.let { if (loadedKey == key) return it }
        engine?.close()
        engine = null
        fun cfg(tokens: Int) = EngineConfig(
            modelPath = file.absolutePath,
            backend = if (useGpu) Backend.GPU() else Backend.CPU(threadCount = threads()),
            maxNumTokens = tokens,
            cacheDir = context.cacheDir.absolutePath,
        )
        val started = System.currentTimeMillis()
        val loaded = runCatching { Engine(cfg(ctx)).also { it.initialize() } to ctx }.getOrElse { first ->
            if (ctx <= 4096) throw first
            Log.w(TAG, "model refused a $ctx-token context, using 4096: ${first.message}")
            refusedContext = ctx
            Engine(cfg(4096)).also { it.initialize() } to 4096
        }
        contextTokens = loaded.second
        Log.i(TAG, "loaded $id (${if (useGpu) "GPU" else "CPU×${threads()}"}, ${loaded.second} tokens) in ${System.currentTimeMillis() - started} ms")
        engine = loaded.first
        loadedKey = key
        return loaded.first
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
                // Thinking first: a free run that reasons, then answers in JSON. If that answer
                // doesn't parse, the usual grammar-constrained run (no thinking) is the fallback.
                // The thinking run carries the schema as text and a thinking budget; only when it all fits.
                val thinkFits = estimateTokens(req.system) + estimateTokens(req.user) + req.schema.toString().length / 3 + req.maxTokens + thinkBudget(req) < contextTokens
                if (req.think != Think.OFF && req.schema != null && thinkFits) {
                    runCatching { Js.parseLenient(generate(e, req, constrained = false, think = true)) }
                        .onFailure { Log.w(TAG, "thinking run failed: ${it.message}") }.getOrNull()
                        ?.takeIf { usable(it, req.schema) }?.let { return@withLock it }
                }
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

    /** An answer counts only if it has most of the schema's fields and says something in at least one. */
    private fun usable(o: JsonObject, schema: JsonObject): Boolean {
        val keys = (schema["properties"] as? JsonObject)?.keys ?: return true
        val present = keys.count { it in o }
        val saysSomething = o.values.any { v -> v.toString().trim('"', ' ').let { it.isNotEmpty() && it != "[]" && it != "{}" && it != "null" && it != "false" && it != "0" } }
        return present * 2 >= keys.size && saysSomething
    }

    private val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private suspend fun generate(e: Engine, req: LlmRequest, constrained: Boolean, think: Boolean = false): String {
        val system = if (constrained || req.schema == null) req.system
        else req.system + "\n\nReply with only a JSON object matching this schema:\n" + req.schema
        val budget = thinkBudget(req)
        val conversation = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = req.temperature),
                enableResponseFormat = constrained,
                maxOutputToken = req.maxTokens + if (think) budget else 0,
                // Gemma 4 reasons in its own channel first; the reply holds only the answer.
                thinkingConfig = if (think) ThinkingConfig(true, budget) else null,
            ),
        )
        return conversation.use { c ->
            // The native call blocks; a watchdog cancels it if it runs away.
            var cancelled = false
            val watchdog = scope.launch { delay(TIMEOUT_MS); cancelled = true; c.cancelProcess() }
            try {
                val reply = if (constrained) {
                    c.sendMessage(Contents.of(req.user), responseFormat = ResponseFormat.json(req.schema.toString()))
                } else {
                    c.sendMessage(Contents.of(req.user))
                }
                reply.toString().also { if (debuggable) Log.i(TAG, "${req.purpose} [think=$think constrained=$constrained in≈${estimateTokens(system) + estimateTokens(req.user)}t] → ${it.take(600)}") }
            } finally {
                watchdog.cancel()
                // Same as in stream(): let a cancelled run wind down before the conversation closes.
                if (cancelled) withContext(NonCancellable) { delay(1_500) }
            }
        }
    }

    override fun stream(req: LlmRequest): Flow<String> = flow {
        lock.withLock {
            idleJob?.cancel()
            try {
                val e = engine()
                // Answers are read as they stream, so on the phone only a deep job waits for thinking.
                val think = req.think == Think.DEEP
                val conversation = e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(req.system),
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = req.temperature),
                        maxOutputToken = req.maxTokens + if (think) thinkBudget(req) else 0,
                        thinkingConfig = if (think) ThinkingConfig(true, thinkBudget(req)) else null,
                    ),
                )
                conversation.use { c ->
                    try {
                        c.sendMessageAsync(Contents.of(req.user)).collect { emit(it.toString()) }
                    } finally {
                        // Stops generation if the reader went away mid-answer. The native side
                        // keeps running for a moment after cancelling; closing the conversation
                        // under it crashed the app (null pointer in liblitertlm), so wait first —
                        // even when this coroutine is itself being cancelled.
                        withContext(NonCancellable) {
                            runCatching { c.cancelProcess() }
                            delay(1_500)
                        }
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
        const val TIMEOUT_MS = 12 * 60_000L

        /** Rough token count: about 3.5 Latin characters or 1.6 Devanagari characters per token. */
        fun estimateTokens(s: String): Int {
            var dev = 0
            for (ch in s) if (ch in '\u0900'..'\u097F') dev++
            return ((s.length - dev) / 3.5 + dev / 1.6).toInt() + 1
        }

        /** A phone generates ~10–25 tokens a second, so thinking is kept to what pays off. */
        // Measured on a Dimensity 7200 (CPU): ~7 tokens/s, so 1,024 thinking tokens cost over 5 minutes
        // and rarely changed the answer. Deep jobs get a little more room than the rest, not much.
        fun thinkBudget(req: LlmRequest) = when (req.think) { Think.OFF -> 0; Think.SOME -> 320; Think.DEEP -> 512 }
    }
}
