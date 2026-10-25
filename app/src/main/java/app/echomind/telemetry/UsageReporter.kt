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
 * Sends feature counts, setup steps, the AI provider *id* and crash types with a random
 * install id to POST /v1/app/echomind. Never anything you said, wrote or asked, nor
 * people, places or keys. There's no off switch: you can request to stop sharing, and
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

    fun event(name: String, props: Map<String, String> = emptyMap()) {
        if (stopped) return
        synchronized(lock) {
            val list = JSONArray(prefs.getString("pending", "[]"))
            if (list.length() >= 500) return
            list.put(JSONObject().put("n", name).put("t", System.currentTimeMillis()).apply { if (props.isNotEmpty()) put("p", JSONObject(props)) })
            prefs.edit().putString("pending", list.toString()).apply()
        }
    }

    fun count(name: String, n: Int = 1) {
        if (stopped) return
        synchronized(lock) {
            val c = JSONObject(prefs.getString("counters", "{}") ?: "{}")
            val key = "${today()}|$name"
            c.put(key, c.optInt(key) + n)
            prefs.edit().putString("counters", c.toString()).apply()
        }
    }

    fun once(name: String) {
        if (stopped) return
        synchronized(lock) {
            val done = prefs.getStringSet("once", emptySet()).orEmpty()
            if (name in done) return
            prefs.edit().putStringSet("once", done + name).apply()
        }
        event(name)
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                if (!stopped) synchronized(lock) {
                    val list = JSONArray(prefs.getString("crashes", "[]"))
                    if (list.length() < 20) {
                        val frames = t.stackTrace.take(25).joinToString("\n") { "at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                        val fp = Integer.toHexString("${t.javaClass.name}|${t.stackTrace.take(3).joinToString(";") { it.className + "." + it.methodName }}".hashCode())
                        // Type and stack only: messages can contain transcript text.
                        list.put(JSONObject().put("fp", fp).put("m", t.javaClass.name.take(200)).put("st", frames.take(4000)).put("t", System.currentTimeMillis()).put("fatal", true))
                        prefs.edit().putString("crashes", list.toString()).commit()
                    }
                }
            }
            previous?.uncaughtException(thread, t)
        }
    }

    /** Send what's queued (finished days' counters, events, crashes); learn whether sharing stopped. */
    suspend fun flush(daily: () -> Map<String, Map<String, String>>) {
        if (stopped) return
        if (prefs.getString("dailyChecked", null) != today()) {
            prefs.edit().putString("dailyChecked", today()).apply()
            for ((n, p) in daily()) event(n, p)
        }
        val snap = synchronized(lock) {
            val events = JSONArray(prefs.getString("pending", "[]"))
            val sentEvents = events.length()
            val c = JSONObject(prefs.getString("counters", "{}") ?: "{}")
            val sentKeys = c.keys().asSequence().filter { !it.startsWith(today()) }.toList()
            for (k in sentKeys) {
                val (day, name) = k.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                events.put(JSONObject().put("n", name).put("t", noon(day)).put("c", c.getInt(k)))
            }
            val crashes = JSONArray(prefs.getString("crashes", "[]"))
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
        private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fun today(): String = fmt().format(Date())
        fun noon(day: String): Long = (fmt().parse(day)?.time ?: System.currentTimeMillis()) + 12 * 3_600_000L
    }
}
