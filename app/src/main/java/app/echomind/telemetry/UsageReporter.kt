package app.echomind.telemetry

import android.content.Context
import android.os.Build
import app.echomind.BuildConfig
import app.echomind.core.Cloud
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Anonymous usage sharing, on as part of using EchoMind (see the usage terms screen).
 * Sends feature counts, outcomes, error kinds, timing/size buckets, screens opened, a daily
 * settings/permissions/storage snapshot (enums, booleans, numbers), setup steps, AI provider
 * *ids* and crash types with a random install id to POST /v1/app/echomind. Never anything
 * you said, wrote or asked, nor people, places, search words, keys or addresses.
 * Fixed names and values come from [UsageMath]; call sites use [Usage]. There's no off switch: you can request to stop sharing, and
 * once the owner approves it the server answers "off" and nothing more is sent.
 *
 * State lives in its own prefs (backups are off for the whole app anyway).
 */
class UsageReporter(private val context: Context, private val cloud: Cloud) {
    private val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
    private val lock = Any()

    val installId: String
        get() = prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }

    /** Server said sharing stopped (request approved): nothing is recorded or sent any more. */
    val stopped get() = prefs.getBoolean("stopped", false)
    /** null, "pending", "approved" or "declined". */
    val requestStatus get() = prefs.getString("request", null)
    val termsSeen get() = prefs.getBoolean("termsSeen", false)

    fun acceptTerms() = prefs.edit().putBoolean("termsSeen", true).apply()

    /** Ask the owner to stop sharing; sent with the next report. */
    fun requestStop() = prefs.edit().putBoolean("wantStop", true).putString("request", "pending").apply()

    /** One event row (low-frequency things). Props: fixed enums, buckets, numbers, booleans only. */
    fun event(name: String, props: Map<String, Any?> = emptyMap()) {
        if (stopped || !UsageMath.validName(name)) return
        val p = UsageMath.cleanProps(props)
        synchronized(lock) {
            val list = JSONArray(prefs.getString("pending", "[]"))
            if (list.length() >= 500) return
            list.put(JSONObject().put("n", name).put("t", System.currentTimeMillis()).apply { if (p.isNotEmpty()) put("p", JSONObject(p)) })
            prefs.edit().putString("pending", list.toString()).apply()
        }
    }

    /** A daily counter (high-frequency things); each distinct props combination is its own count. */
    fun count(name: String, props: Map<String, Any?> = emptyMap(), n: Int = 1) {
        if (stopped || !UsageMath.validName(name)) return
        val p = UsageMath.cleanProps(props)
        synchronized(lock) {
            val c = JSONObject(prefs.getString("counters", "{}") ?: "{}")
            val key = "${today()}|$name" + if (p.isEmpty()) "" else "|" + JSONObject(p.toSortedMap() as Map<*, *>).toString()
            if (!c.has(key) && c.length() >= 600) return
            c.put(key, c.optInt(key) + n)
            prefs.edit().putString("counters", c.toString()).apply()
        }
    }

    fun count(name: String, n: Int) = count(name, emptyMap(), n)

    fun once(name: String, props: Map<String, Any?> = emptyMap()) {
        if (stopped) return
        synchronized(lock) {
            val done = prefs.getStringSet("once", emptySet()).orEmpty()
            if (name in done) return
            prefs.edit().putStringSet("once", done + name).apply()
        }
        event(name, props)
    }

    /**
     * A handled failure (worker, pipeline): type, our own [where] label and the stack only —
     * messages can contain transcript text or server replies, so they're never sent.
     * The same fingerprint is queued once until the next report.
     */
    fun error(where: String, t: Throwable, fatal: Boolean = false) {
        if (stopped) return
        runCatching {
            synchronized(lock) {
                val list = JSONArray(prefs.getString("crashes", "[]"))
                val entry = crashEntry(t, fatal, where)
                for (i in 0 until list.length()) if (list.optJSONObject(i)?.optString("fp") == entry.optString("fp")) return
                if (list.length() >= 20) return
                list.put(entry)
                prefs.edit().putString("crashes", list.toString()).apply()
            }
        }
    }

    private fun crashEntry(t: Throwable, fatal: Boolean, where: String?): JSONObject {
        val root = generateSequence(t) { it.cause }.take(8).last()
        val frames = t.stackTrace.take(25).joinToString("\n") { "at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" } +
            if (root !== t) "\ncaused by ${root.javaClass.name}\n" + root.stackTrace.take(10).joinToString("\n") { "at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" } else ""
        val fp = Integer.toHexString("${where.orEmpty()}|${t.javaClass.name}|${t.stackTrace.take(3).joinToString(";") { it.className + "." + it.methodName }}".hashCode())
        val m = listOfNotNull(where, t.javaClass.name, root.takeIf { it !== t }?.javaClass?.name?.let { "caused by $it" }).joinToString(" · ")
        return JSONObject().put("fp", fp).put("m", m.take(500)).put("st", frames.take(2500)).put("t", System.currentTimeMillis()).put("fatal", fatal)
    }

    // --- Foreground sessions (length bucket only) and permission changes ---

    private var fgSince = 0L

    fun sessionStart() { if (fgSince == 0L) fgSince = System.currentTimeMillis() }

    fun sessionEnd() {
        val since = fgSince
        fgSince = 0L
        if (since > 0) count("session_end", mapOf("len" to UsageMath.sessionBucket(System.currentTimeMillis() - since)))
    }

    /** Compares permission states with the last check; each change is one event (no event the first time). */
    fun checkPermissions(now: Map<String, Boolean>) {
        if (stopped) return
        val before = prefs.getString("perms", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (before != null) for ((k, v) in now) if (before.has(k) && before.optBoolean(k) != v) event("permission_changed", mapOf("perm" to k, "granted" to v))
        prefs.edit().putString("perms", JSONObject(now as Map<*, *>).toString()).apply()
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                if (!stopped) synchronized(lock) {
                    val list = JSONArray(prefs.getString("crashes", "[]"))
                    if (list.length() < 20) {
                        list.put(crashEntry(t, true, null))
                        prefs.edit().putString("crashes", list.toString()).commit()
                    }
                }
            }
            previous?.uncaughtException(thread, t)
        }
    }

    /** Send what's queued (finished days' counters, events, crashes); learn whether sharing stopped. */
    suspend fun flush(daily: suspend () -> Map<String, Map<String, Any?>>) {
        if (stopped) return
        if (prefs.getString("dailyChecked", null) != today()) {
            prefs.edit().putString("dailyChecked", today()).apply()
            for ((n, p) in daily()) event(n, p)
        }
        // A report stays inside the API limits (200 events, 64 KB); whatever doesn't fit waits for the next one.
        val snap = synchronized(lock) {
            val events = JSONArray()
            val pending = JSONArray(prefs.getString("pending", "[]"))
            val sentEvents = minOf(pending.length(), MAX_EVENTS)
            for (i in 0 until sentEvents) events.put(pending.get(i))
            val c = JSONObject(prefs.getString("counters", "{}") ?: "{}")
            val sentKeys = c.keys().asSequence().filter { !it.startsWith(today()) }.take(MAX_EVENTS - sentEvents).toList()
            for (k in sentKeys) {
                val parts = k.split('|', limit = 3)
                val e = JSONObject().put("n", parts.getOrElse(1) { "" }).put("t", noon(parts[0])).put("c", c.getInt(k).coerceIn(1, 1_000_000))
                parts.getOrNull(2)?.let { p -> runCatching { e.put("p", JSONObject(p)) } }
                events.put(e)
            }
            val all = JSONArray(prefs.getString("crashes", "[]"))
            val crashes = JSONArray().apply { for (i in 0 until minOf(all.length(), MAX_CRASHES)) put(all.get(i)) }
            Triple(events, sentKeys, sentEvents to crashes)
        }
        val (events, sentKeys, rest) = snap
        val (sentEvents, crashes) = rest
        val wantStop = prefs.getBoolean("wantStop", false)
        if (events.length() == 0 && crashes.length() == 0 && !wantStop) return
        val body = base().put("e", events).put("cr", crashes).apply { if (wantStop) put("req", true) }.toString()
        val answer = runCatching {
            cloud.sendUsage(body.length.toLong()) {
                val req = Request.Builder().url(ENDPOINT).post(body.toRequestBody("application/json".toMediaType())).build()
                cloud.http.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body.string()) else null }
            }
        }.getOrNull() ?: return
        synchronized(lock) {
            val pending = JSONArray(prefs.getString("pending", "[]"))
            val left = JSONArray().apply { for (i in sentEvents until pending.length()) put(pending.get(i)) }
            val c = JSONObject(prefs.getString("counters", "{}") ?: "{}").apply { sentKeys.forEach { remove(it) } }
            val cr = JSONArray(prefs.getString("crashes", "[]"))
            val crLeft = JSONArray().apply { for (i in crashes.length() until cr.length()) put(cr.get(i)) }
            prefs.edit().putString("pending", left.toString()).putString("counters", c.toString()).putString("crashes", crLeft.toString())
                .putBoolean("wantStop", false).putString("request", answer.optString("request").takeIf { it.isNotEmpty() && it != "null" })
                .apply()
        }
        if (answer.optString("sharing") == "off") {
            prefs.edit().putBoolean("stopped", true).remove("pending").remove("counters").remove("crashes").apply()
        }
    }

    private fun base() = JSONObject()
        .put("i", installId).put("v", BuildConfig.VERSION_NAME).put("pf", "android").put("os", "Android")
        .put("osv", Build.VERSION.RELEASE.take(32)).put("dc", "phone").put("dm", "${Build.MANUFACTURER} ${Build.MODEL}".take(48))
        .put("loc", Locale.getDefault().toLanguageTag().take(16))

    companion object {
        const val ENDPOINT = "https://api.np-dev.duckdns.org/v1/app/echomind"
        /** ≤150 events (8 short props at most) and 4 crashes with ≤2.5 KB stacks: this stays well under the 64 KB body limit. */
        private const val MAX_EVENTS = 150
        private const val MAX_CRASHES = 4
        private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fun today(): String = fmt().format(Date())
        fun noon(day: String): Long = (fmt().parse(day)?.time ?: System.currentTimeMillis()) + 12 * 3_600_000L
    }
}

/**
 * Safe handle for call sites anywhere in the app (does nothing before the app object
 * exists, e.g. in unit tests). Same rules as [UsageReporter]: fixed names and values only.
 */
object Usage {
    private val r: UsageReporter? get() = runCatching { app.echomind.App.graph.usage }.getOrNull()

    fun event(name: String, props: Map<String, Any?> = emptyMap()) { r?.event(name, props) }
    fun count(name: String, props: Map<String, Any?> = emptyMap(), n: Int = 1) { r?.count(name, props, n) }
    fun once(name: String, props: Map<String, Any?> = emptyMap()) { r?.once(name, props) }
    fun error(where: String, t: Throwable) { if (t !is kotlinx.coroutines.CancellationException) r?.error(where, t) }

    /**
     * A failure that isn't an expected outside problem (no key, offline, rate limit, server
     * error…) is a bug: record it like a crash (non-fatal, type and stack only).
     */
    fun unexpected(where: String, t: Throwable) {
        if (UsageMath.errorCategory(t) in setOf("other", "state", "io", "oom")) error(where, t)
    }

    /**
     * A background job's run: its outcome is counted, and a crash is recorded (non-fatal, type and
     * stack only) and rethrown, so WorkManager handles it exactly as before.
     */
    suspend fun worker(name: String, block: suspend () -> androidx.work.ListenableWorker.Result): androidx.work.ListenableWorker.Result {
        val began = System.currentTimeMillis()
        try {
            val res = block()
            val outcome = when (res) {
                is androidx.work.ListenableWorker.Result.Success -> "success"
                is androidx.work.ListenableWorker.Result.Retry -> "retry"
                else -> "failure"
            }
            count("worker_run", mapOf("worker" to name, "outcome" to outcome, "took" to UsageMath.latencyBucket(System.currentTimeMillis() - began)))
            return res
        } catch (e: kotlinx.coroutines.CancellationException) {
            count("worker_run", mapOf("worker" to name, "outcome" to "stopped"))
            throw e
        } catch (t: Throwable) {
            count("worker_run", mapOf("worker" to name, "outcome" to "crashed", "err" to UsageMath.errorCategory(t)))
            error("worker_$name", t)
            throw t
        }
    }
}
