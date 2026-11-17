package app.echomind.ai

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import app.echomind.core.Feature
import app.echomind.util.Js
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The on-device model runs in its own process (":ai"). Loading it takes 1–2 GB, and its native
 * code can crash; in the app's own process either took the microphone down with it, and Android
 * doesn't let an app reopen the microphone from the background. Here a crash or a low-memory kill
 * ends only the AI job, which is tried again later, while listening carries on. The AI process is
 * bound below the listening service's priority, so Android reclaims it first.
 */
class AiProcessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var cfg = AiProcess.Cfg("", "", "cpu", 4, 4096)
    private val jobs = ConcurrentHashMap<Long, Job>()

    private val llm by lazy {
        LocalLlm(this, scope, { cfg.takeIf { it.path.isNotEmpty() && File(it.path).exists() }?.let { it.model to File(it.path) } }, { cfg.backend }, { cfg.threads }, { cfg.context })
    }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(m: Message) {
            val b = m.data
            val id = b.getLong(AiProcess.ID)
            val reply = m.replyTo
            b.getString(AiProcess.CFG)?.let { AiProcess.Cfg.decode(it)?.let { c -> cfg = c } }
            when (m.what) {
                AiProcess.JSON -> jobs[id] = scope.launch {
                    val req = AiProcess.decode(b.getString(AiProcess.REQ)!!)
                    val out = runCatching { llm.json(req) }
                    out.onSuccess { send(reply, AiProcess.RESULT, id, it.toString()) }
                        .onFailure { e -> send(reply, AiProcess.ERROR, id, e.message ?: e.javaClass.simpleName, retryable = (e as? LlmException)?.retryable ?: true) }
                    jobs.remove(id)
                }
                AiProcess.STREAM -> jobs[id] = scope.launch {
                    val req = AiProcess.decode(b.getString(AiProcess.REQ)!!)
                    runCatching { llm.stream(req).collect { send(reply, AiProcess.CHUNK, id, it) } }
                        .onSuccess { send(reply, AiProcess.DONE, id, "") }
                        .onFailure { e -> send(reply, AiProcess.ERROR, id, e.message ?: e.javaClass.simpleName, retryable = (e as? LlmException)?.retryable ?: true) }
                    jobs.remove(id)
                }
                AiProcess.CANCEL -> jobs.remove(id)?.cancel()
                AiProcess.RELEASE -> scope.launch { llm.release() }
            }
        }
    }

    private fun send(to: Messenger?, what: Int, id: Long, text: String, retryable: Boolean = true) {
        val m = Message.obtain(null, what).apply {
            data = Bundle().apply {
                putLong(AiProcess.ID, id); putString(AiProcess.TEXT, text); putBoolean(AiProcess.RETRY, retryable); putInt(AiProcess.CTX, llm.contextTokens)
            }
        }
        runCatching { to?.send(m) }.onFailure { Log.w(TAG, "client gone: ${it.message}") }
    }

    override fun onBind(intent: Intent): IBinder = Messenger(handler).binder

    override fun onDestroy() {
        scope.launch { runCatching { llm.release() } }
        super.onDestroy()
    }

    companion object { private const val TAG = "EchoAiProcess" }
}

/** The app side: an [Llm] whose model lives in [AiProcessService]. Requests and answers go over a Messenger. */
class AiProcess(
    private val context: Context,
    /** The model's id and file, backend, threads and context to run with (read from settings in the app's process). */
    private val config: () -> Cfg,
) : Llm, OnDevice {
    override val id = "local"
    override val local = true
    @Volatile override var contextTokens: Int = 4096
        private set
    override val promptBudgetChars get() = ((config().context.coerceAtLeast(4096) - 2_600) * 1.2).toInt()

    data class Cfg(val model: String, val path: String, val backend: String, val threads: Int, val context: Int) {
        fun encode() = buildJsonObject { put("m", model); put("p", path); put("b", backend); put("t", threads); put("c", context) }.toString()
        companion object {
            fun decode(s: String): Cfg? = runCatching {
                val o = Js.json.parseToJsonElement(s) as JsonObject
                fun str(k: String) = (o[k] as JsonPrimitive).content
                Cfg(str("m"), str("p"), str("b"), str("t").toInt(), str("c").toInt())
            }.getOrNull()
        }
    }

    private val ids = AtomicLong(1)
    private val waiting = ConcurrentHashMap<Long, CompletableDeferred<Result<String>>>()
    private val streams = ConcurrentHashMap<Long, Channel<Result<String?>>>()
    private val bindLock = Mutex()
    @Volatile private var service: Messenger? = null
    @Volatile private var bound: CompletableDeferred<Messenger>? = null

    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(m: Message) {
            val b = m.data
            val id = b.getLong(ID)
            contextTokens = b.getInt(CTX, contextTokens)
            val text = b.getString(TEXT).orEmpty()
            when (m.what) {
                RESULT -> waiting.remove(id)?.complete(Result.success(text))
                CHUNK -> streams[id]?.trySend(Result.success(text))
                DONE -> streams.remove(id)?.let { it.trySend(Result.success(null)); it.close() }
                ERROR -> {
                    val e = LlmException(text, retryable = b.getBoolean(RETRY, true))
                    waiting.remove(id)?.complete(Result.failure(e))
                    streams.remove(id)?.let { it.trySend(Result.failure(e)); it.close() }
                }
            }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val m = Messenger(binder)
            service = m
            bound?.complete(m)
            // If the AI process dies (memory, a native crash), every request in flight fails and is retried later.
            runCatching { binder.linkToDeath({ died() }, 0) }
        }
        override fun onServiceDisconnected(name: ComponentName) = died()
    }

    private fun died() {
        service = null; bound = null
        val e = LlmException("The on-device AI stopped (the phone needed its memory). It will try again.", retryable = true)
        waiting.keys.toList().forEach { waiting.remove(it)?.complete(Result.failure(e)) }
        streams.keys.toList().forEach { k -> streams.remove(k)?.let { it.trySend(Result.failure(e)); it.close() } }
        Log.w(TAG, "AI process gone")
    }

    private suspend fun connect(): Messenger = bindLock.withLock {
        service?.let { return@withLock it }
        val d = CompletableDeferred<Messenger>().also { bound = it }
        // Below the listening service's priority: under memory pressure Android reclaims the AI first.
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_NOT_PERCEPTIBLE
        if (!context.bindService(Intent(context, AiProcessService::class.java), connection, flags)) throw LlmException("Couldn't start the on-device AI", retryable = true)
        withTimeout(15_000) { d.await() }
    }

    private suspend fun post(what: Int, id: Long, req: LlmRequest?): Messenger {
        val s = connect()
        val m = Message.obtain(null, what).apply {
            replyTo = replies
            data = Bundle().apply { putLong(ID, id); req?.let { putString(REQ, encode(it)) }; putString(CFG, config().encode()) }
        }
        try { s.send(m) } catch (e: RemoteException) { died(); throw LlmException("The on-device AI stopped. It will try again.", retryable = true, e) }
        return s
    }

    override suspend fun json(req: LlmRequest): JsonObject {
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<Result<String>>().also { waiting[id] = it }
        try {
            post(JSON, id, req)
            val text = d.await().getOrThrow()
            return Js.json.parseToJsonElement(text) as JsonObject
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { service?.send(Message.obtain(null, CANCEL).apply { data = Bundle().apply { putLong(ID, id) } }) }
            throw e
        } finally {
            waiting.remove(id)
        }
    }

    override fun stream(req: LlmRequest): Flow<String> = flow {
        val id = ids.getAndIncrement()
        val ch = Channel<Result<String?>>(Channel.UNLIMITED).also { streams[id] = it }
        try {
            post(STREAM, id, req)
            for (r in ch) { val t = r.getOrThrow() ?: break; emit(t) }
        } finally {
            if (streams.remove(id) != null) runCatching { service?.send(Message.obtain(null, CANCEL).apply { data = Bundle().apply { putLong(ID, id) } }) }
        }
    }

    /** Unloads the model now (travel mode, low memory). */
    fun release() { runCatching { service?.send(Message.obtain(null, RELEASE)) } }

    companion object {
        private const val TAG = "EchoAiClient"
        const val JSON = 1; const val STREAM = 2; const val CANCEL = 3; const val RELEASE = 4
        const val RESULT = 11; const val CHUNK = 12; const val DONE = 13; const val ERROR = 14
        const val ID = "id"; const val REQ = "req"; const val CFG = "cfg"; const val TEXT = "text"; const val RETRY = "retry"; const val CTX = "ctx"

        fun encode(r: LlmRequest): String = buildJsonObject {
            put("f", r.feature.name); put("p", r.purpose); put("s", r.system); put("u", r.user)
            r.schema?.let { put("schema", it) }
            put("mt", r.maxTokens); put("t", r.temperature); put("d", r.deep); put("n", r.noThink); put("k", r.think.name)
        }.toString()

        fun decode(s: String): LlmRequest {
            val o = Js.json.parseToJsonElement(s) as JsonObject
            fun str(k: String) = (o[k] as JsonPrimitive).content
            return LlmRequest(
                feature = Feature.valueOf(str("f")), purpose = str("p"), system = str("s"), user = str("u"),
                schema = o["schema"] as? JsonObject, maxTokens = str("mt").toInt(), temperature = str("t").toDouble(),
                deep = str("d").toBoolean(), noThink = str("n").toBoolean(), think = Think.valueOf(str("k")),
            )
        }
    }
}
