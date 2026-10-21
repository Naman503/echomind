package app.echomind.brain

import android.util.Log
import app.echomind.ai.Ai
import app.echomind.ai.LlmRequest
import app.echomind.ai.Prompts
import app.echomind.ai.S
import app.echomind.core.Cloud
import app.echomind.core.Feature
import app.echomind.core.Secrets
import app.echomind.core.SettingsStore
import app.echomind.data.Db
import app.echomind.util.Js
import app.echomind.util.arr
import app.echomind.util.str
import app.echomind.util.strings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Looking things up on the internet, privately. The AI writes short, general
 * queries (never your conversations), the phone strips anything personal that
 * still slipped in — names you know, numbers, emails — and, unless you chose
 * otherwise, shows you each query before it is sent. Only results come back;
 * nothing else is shared. Every search is in the data ledger.
 */
class Web(private val cloud: Cloud, private val settings: SettingsStore, private val secrets: Secrets, private val db: Db, private val ai: Ai) {
    data class Result(val title: String, val url: String, val snippet: String, val source: String)

    data class Approval(val purpose: String, val queries: List<String>, val reply: CompletableDeferred<List<String>?>)
    private val _approval = MutableStateFlow<Approval?>(null)
    /** A search waiting for your OK; the screen shows it and answers [reply]. */
    val approval: StateFlow<Approval?> = _approval.asStateFlow()

    fun enabled() = settings.value.webSearch != "off"

    fun providerLabel() = when (settings.value.webProvider) {
        "wikipedia" -> "Wikipedia"; "searxng" -> "your SearXNG"; "brave" -> "Brave Search"; "google" -> "Google"; else -> "DuckDuckGo"
    }

    /** Removes what could identify you or someone you know; null when nothing safe is left. */
    suspend fun scrub(query: String): String? {
        val names = db.memory().allEntities().filter { it.kind == "person" }.flatMap { listOf(it.name) + it.name.split(' ') }
            .map { it.trim().lowercase() }.filter { it.length >= 3 }.toSet() + listOfNotNull(settings.value.userName.lowercase().takeIf { it.length >= 3 }) +
            settings.value.userName.lowercase().split(' ').filter { it.length >= 3 }
        return clean(query, names)
    }

    /** Asks you (when set to ask) and returns the queries you allowed. */
    suspend fun approve(purpose: String, queries: List<String>): List<String> {
        if (queries.isEmpty() || !enabled()) return emptyList()
        if (settings.value.webSearch == "auto") return queries
        val d = CompletableDeferred<List<String>?>()
        _approval.value = Approval(purpose, queries, d)
        val ok = d.await()
        _approval.value = null
        return ok.orEmpty()
    }

    fun answer(allowed: List<String>?) { _approval.value?.reply?.complete(allowed) }

    /** [forSongs]: allowed by the songs' own "look up online" switch rather than the web-search mode. */
    suspend fun search(query: String, n: Int = 5, forSongs: Boolean = false): List<Result> = withContext(Dispatchers.IO) {
        val q = scrub(query) ?: return@withContext emptyList()
        val s = settings.value
        runCatching {
            cloud.sendPublic(providerLabel(), q.length.toLong(), "Search: $q", allowed = if (forSongs) s.songsOnline else s.webSearch != "off") {
                when (s.webProvider) {
                    "wikipedia" -> wikipedia(q, n)
                    "searxng" -> searxng(s.searxngUrl, q, n)
                    "brave" -> brave(q, n)
                    "google" -> google(q, n)
                    else -> duckduckgo(q, n)
                }
            }
        }.onFailure { Log.w(TAG, "search failed", it) }.getOrDefault(emptyList())
    }

    /** The readable text of a result page, for a fuller answer. */
    suspend fun read(url: String, maxChars: Int = 3_000): String? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        runCatching {
            cloud.sendPublic(url.toHttpUrl().host, url.length.toLong(), "Reading ${url.toHttpUrl().host}") {
                get(url)?.let { pageText(it).take(maxChars) }
            }
        }.getOrNull()
    }

    private val planSchema = S.obj("queries" to S.arr(S.str(), "1-3 short general web searches, no names or private details"))

    /**
     * Thinks about what to look up for [need], writes general queries, gets your OK,
     * searches and reads. Returns the findings with sources, or empty when nothing was allowed.
     */
    suspend fun research(need: String, purpose: String, maxQueries: Int = 3): List<Result> {
        if (!enabled()) return emptyList()
        val planned = runCatching {
            ai.json(LlmRequest(Feature.ASK, "Planning a web search", Prompts.webPlanSystem(), "Need: $need", planSchema, maxTokens = 200, deep = true)).strings("queries")
        }.getOrDefault(emptyList()).ifEmpty { listOf(need) }
        val safe = planned.mapNotNull { scrub(it) }.distinct().take(maxQueries)
        val allowed = approve(purpose, safe)
        val results = allowed.flatMap { search(it, 4) }.distinctBy { it.url }
        if (!settings.value.webReadPages) return results.take(8)
        // The top two pages are read for detail; the rest stay as snippets.
        return results.take(8).mapIndexed { i, r -> if (i < 2) read(r.url)?.let { r.copy(snippet = it) } ?: r else r }
    }

    // ── providers ──

    /** A plain HTTPS GET for public data (song catalogue, artwork). */
    fun fetch(url: String, appAgent: Boolean = false): String? = get(url, if (appAgent) mapOf("User-Agent" to APP_UA) else emptyMap())

    fun fetchBytes(url: String): ByteArray? {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        return cloud.http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body.bytes() else null }
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
        val req = Request.Builder().url(url).header("User-Agent", headers["User-Agent"] ?: UA).header("Accept-Language", "en-IN,en;q=0.8").apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return cloud.http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body.string() else throw IllegalStateException("HTTP ${r.code}") }
    }

    private fun duckduckgo(q: String, n: Int): List<Result> {
        val html = get("https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(q, "UTF-8")) ?: return emptyList()
        // DuckDuckGo answers automated searches with a check page; EchoMind never tries to get past it.
        if ("anomaly" in html && "result__a" !in html) throw IllegalStateException("DuckDuckGo blocked this automated search — choose Wikipedia, your SearXNG or Brave in Settings → Web search")
        val blocks = html.split("class=\"result__body\"").drop(1)
        return blocks.mapNotNull { b ->
            val link = Regex("class=\"result__a\" href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b) ?: return@mapNotNull null
            var url = link.groupValues[1]
            // DuckDuckGo wraps links: //duckduckgo.com/l/?uddg=<real url>
            Regex("uddg=([^&]+)").find(url)?.let { url = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (url.startsWith("//")) url = "https:$url"
            val snippet = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1).orEmpty()
            Result(strip(link.groupValues[2]), url, strip(snippet), "DuckDuckGo")
        }.filter { "duckduckgo.com/y.js" !in it.url }.take(n)
    }

    private fun wikipedia(q: String, n: Int): List<Result> {
        val body = get("https://en.wikipedia.org/w/rest.php/v1/search/page?limit=$n&q=" + java.net.URLEncoder.encode(q, "UTF-8")) ?: return emptyList()
        return (Js.parseObject(body).arr("pages") ?: return emptyList()).mapNotNull { it as? JsonObject }.map { p ->
            Result(p.str("title").orEmpty(), "https://en.wikipedia.org/wiki/" + p.str("key").orEmpty(), strip(p.str("excerpt").orEmpty()) + " " + p.str("description").orEmpty(), "Wikipedia")
        }
    }

    private fun searxng(base: String, q: String, n: Int): List<Result> {
        if (!base.startsWith("http")) throw IllegalStateException("Set your SearXNG address in Settings → Web search")
        val body = get(base.trimEnd('/') + "/search?format=json&q=" + java.net.URLEncoder.encode(q, "UTF-8")) ?: return emptyList()
        return (Js.parseObject(body).arr("results") ?: return emptyList()).mapNotNull { it as? JsonObject }.take(n).map { r ->
            Result(r.str("title").orEmpty(), r.str("url").orEmpty(), strip(r.str("content").orEmpty()), "SearXNG")
        }
    }

    private fun brave(q: String, n: Int): List<Result> {
        val key = secrets.get("brave") ?: throw IllegalStateException("Add your Brave Search key in Settings → Web search")
        val body = get("https://api.search.brave.com/res/v1/web/search?count=$n&q=" + java.net.URLEncoder.encode(q, "UTF-8"), mapOf("X-Subscription-Token" to key, "Accept" to "application/json")) ?: return emptyList()
        val results = (Js.parseObject(body)["web"] as? JsonObject)?.arr("results") ?: return emptyList()
        return results.mapNotNull { it as? JsonObject }.map { r -> Result(r.str("title").orEmpty(), r.str("url").orEmpty(), strip(r.str("description").orEmpty()), "Brave") }
    }

    private fun google(q: String, n: Int): List<Result> {
        val key = secrets.get("google_search") ?: throw IllegalStateException("Add your Google search key in Settings → Web search")
        val cx = settings.value.googleCx.ifBlank { throw IllegalStateException("Add your search engine ID (cx) in Settings → Web search") }
        val body = get("https://www.googleapis.com/customsearch/v1?num=$n&key=$key&cx=$cx&q=" + java.net.URLEncoder.encode(q, "UTF-8")) ?: return emptyList()
        return (Js.parseObject(body).arr("items") ?: return emptyList()).mapNotNull { it as? JsonObject }.map { r ->
            Result(r.str("title").orEmpty(), r.str("link").orEmpty(), r.str("snippet").orEmpty(), "Google")
        }
    }

    companion object {
        private const val TAG = "EchoWeb"
        /** For open APIs that ask apps to say who they are (LRCLIB, Apple's catalogue). */
        const val APP_UA = "EchoMind/1.0 (personal Android app)"
        private const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Mobile Safari/537.36"

        /** Pure, so it is unit-tested: drops known names, emails, phone and account numbers, and long digit runs. */
        fun clean(query: String, names: Set<String>): String? {
            var q = query.replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), " ")
                .replace(Regex("\\+?\\d[\\d\\s-]{7,}\\d"), " ")
                .replace(Regex("\\b\\d{5,}\\b"), " ")
            q = q.split(Regex("\\s+")).filter { w -> w.isNotBlank() && w.lowercase().trim(',', '.', '\'', '"', '?') !in names }.joinToString(" ")
            q = q.trim().take(120)
            return q.takeIf { it.split(' ').count { w -> w.any(Char::isLetterOrDigit) } >= 2 }
        }

        fun strip(html: String): String = html.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

        /** Main text of an HTML page: scripts, styles and navigation removed. */
        fun pageText(html: String): String {
            val body = html.replace(Regex("(?is)<(script|style|nav|header|footer|aside|noscript|svg)[^>]*>.*?</\\1>"), " ")
            val paras = Regex("(?is)<(p|li|h[1-4])[^>]*>(.*?)</\\1>").findAll(body).map { strip(it.groupValues[2]) }.filter { it.length > 40 }.toList()
            return (if (paras.size >= 3) paras.joinToString("\n") else strip(body)).replace(Regex("\n{2,}"), "\n")
        }
    }
}
