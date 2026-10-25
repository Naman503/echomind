package app.echomind.brain

import android.util.Log
import app.echomind.core.Cloud
import app.echomind.core.SettingsStore
import app.echomind.data.Conversation
import app.echomind.data.Db
import app.echomind.data.EntityRow
import app.echomind.data.Kv
import app.echomind.data.Mention
import app.echomind.data.Utterance
import app.echomind.util.Js
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * Named things from your conversations — places, movies and shows, books, music,
 * technology, products, companies, food, events, games — so you can recall
 * "that series Rahul suggested" later. Every mention keeps the exact words and the
 * line they came from; a model's spelling never replaces what was said. Names are
 * matched spelling- and script-tolerantly, so "Panchayat", "panchayath" and
 * "पंचायत" are one thing. Online look-ups (Wikidata) are optional.
 */
class Things(private val db: Db, private val settings: SettingsStore, private val cloud: Cloud, private val web: () -> Web) {

    enum class Kind(val key: String, val label: String, val plural: String) {
        PLACE("place", "Place", "Places"), MOVIE("movie", "Movie", "Movies"), SHOW("show", "Show", "Shows"), BOOK("book", "Book", "Books"),
        MUSIC("music", "Music", "Music"), TECH("tech", "Tech", "Tech and tools"), PRODUCT("product", "Product", "Products"),
        COMPANY("org", "Company", "Companies"), FOOD("food", "Food", "Food"), EVENT("event", "Event", "Events"), GAME("game", "Game", "Games"),
        OTHER("other", "Other", "Other");
        companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
    }

    companion object {
        private const val TAG = "EchoThings"
        val KEYS = Kind.entries.map { it.key }.toSet()

        /** The model's kind word → the stored kind ("company" is stored as "org", like organisations from people). */
        fun kindOf(raw: String?): String = when (val k = raw?.trim()?.lowercase()) {
            "company", "org", "organisation", "organization", "brand" -> "org"
            null, "" -> "other"
            else -> if (k in KEYS) k else "other"
        }

        /** Kinds that are easily confused for the same thing (a "movie" that is really a series). */
        private fun sameGroup(a: String, b: String) = a == b || setOf(a, b) == setOf("movie", "show") || setOf(a, b) == setOf("tech", "product")

        /** Spelling- and script-tolerant key: transliterated, sound-alike, without "the". */
        fun key(name: String): String = TextNorm.searchable(name).split(' ').filter { it.isNotEmpty() && it != "the" && it != "da" }.joinToString(" ")

        /** The consonants of a key, in order ("koramangala" → "krmngl"). */
        fun skeleton(k: String) = k.filter { it !in "aeiou " }

        /** Jaro–Winkler similarity of two keys (1 = same). */
        fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val range = (maxOf(a.length, b.length) / 2 - 1).coerceAtLeast(0)
            val am = BooleanArray(a.length); val bm = BooleanArray(b.length)
            var m = 0
            for (i in a.indices) {
                for (j in maxOf(0, i - range)..minOf(b.length - 1, i + range)) if (!bm[j] && a[i] == b[j]) { am[i] = true; bm[j] = true; m++; break }
            }
            if (m == 0) return 0.0
            var t = 0; var k = 0
            for (i in a.indices) if (am[i]) { while (!bm[k]) k++; if (a[i] != b[k]) t++; k++ }
            val jaro = (m.toDouble() / a.length + m.toDouble() / b.length + (m - t / 2.0) / m) / 3
            val prefix = a.zip(b).takeWhile { it.first == it.second }.size.coerceAtMost(4)
            return jaro + prefix * 0.1 * (1 - jaro)
        }

        /** The existing thing a name refers to: same key, a known alias, or a very close spelling of a longer name. */
        fun match(name: String, kind: String, existing: List<EntityRow>): EntityRow? {
            val k = key(name)
            if (k.isEmpty()) return null
            val pool = existing.filter { sameGroup(it.kind, kind) }
            pool.firstOrNull { key(it.name) == k || aliases(it).any { a -> key(a) == k } }?.let { return it }
            if (k.length < 5) return null
            // Recognisers mostly get the vowels wrong in Hinglish names (Koramangala / Kormangala): same consonants count.
            val sk = skeleton(k)
            if (sk.length >= 4) pool.firstOrNull { e -> (listOf(e.name) + aliases(e)).any { skeleton(key(it)) == sk } }?.let { return it }
            return pool.map { it to maxOf(similarity(key(it.name), k), aliases(it).maxOfOrNull { a -> similarity(key(a), k) } ?: 0.0) }
                .filter { it.second >= 0.93 }.maxByOrNull { it.second }?.first
        }

        fun aliases(e: EntityRow): List<String> = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), e.aliases) }.getOrDefault(emptyList())

        /** The newest real intent wins; "none" never erases one. */
        fun status(current: String?, intent: String?): String? = intent?.takeIf { it in setOf("want", "recommended", "done", "liked", "disliked") } ?: current

        fun statusLabel(kind: String, status: String?): String? = when (status) {
            "want" -> when (kind) { "movie", "show" -> "Want to watch"; "book" -> "Want to read"; "place" -> "Want to visit"; "music" -> "Want to listen"; "food" -> "Want to try"; else -> "Want to try" }
            "recommended" -> "Recommended"
            "done" -> when (kind) { "movie", "show" -> "Watched"; "book" -> "Read"; "place" -> "Been there"; else -> "Done" }
            "liked" -> "Liked"
            "disliked" -> "Didn't like"
            else -> null
        }

        /** Words in a Wikidata description that confirm a kind; a result of another type is not accepted for these kinds. */
        private val typeWords = mapOf(
            "movie" to listOf("film"), "show" to listOf("series", "television", "show", "sitcom", "anime"), "book" to listOf("book", "novel", "literary", "written work"),
            "music" to listOf("song", "album", "single", "band", "singer", "musician", "rapper", "composer"),
            "tech" to listOf("software", "programming", "framework", "library", "application", "app", "operating system", "language", "smartphone", "device", "model", "platform", "website", "service"),
            "game" to listOf("video game", "game"), "place" to listOf("city", "town", "village", "district", "state", "country", "neighbourhood", "neighborhood", "area", "temple", "fort", "beach", "lake", "hill", "park", "mall", "restaurant", "museum", "station", "airport", "island", "region", "locality"),
            "org" to listOf("company", "business", "organization", "organisation", "brand", "startup", "bank", "university", "college", "school", "manufacturer", "corporation"),
            "food" to listOf("dish", "food", "cuisine", "drink", "beverage", "sweet", "snack", "bread"),
        )

        /** Picks the search result whose description fits the kind (or the first one for kinds without a word list). */
        fun pick(kind: String, results: List<Pair<String, String>>): Pair<String, String>? {
            val words = typeWords[kind] ?: return results.firstOrNull()
            return results.firstOrNull { (_, d) -> words.any { d.lowercase().contains(it) } }
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
    private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.content?.toIntOrNull()

    /** Saves the grounded "things" from a conversation's passes. Returns how many mentions were added. */
    suspend fun record(c: Conversation, utts: List<Utterance>, outputs: List<JsonObject>): Int {
        val items = outputs.flatMap { o -> (o["things"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject } }
        if (items.isEmpty()) { db.memory().putKv(Kv("things:${c.id}", "1", System.currentTimeMillis())); return 0 }
        var added = 0
        for (t in items) {
            val name = t.s("name").trim('.', ',', '"', '\'').takeIf { it.length in 2..60 && !Memory.isJunkName(it) } ?: continue
            val kind = kindOf(t.s("kind"))
            val u = t.i("line")?.let { utts.getOrNull(it - 1) } ?: continue
            val quote = t.s("quote").ifBlank { u.text }.take(240)
            val intent = t.s("intent").ifBlank { "none" }
            val all = db.memory().allEntities().filter { it.kind != "person" && it.kind != "project" && it.kind != "topic" }
            val found = match(name, kind, all)
            val e = if (found != null) {
                if (db.mentions().exists(found.id, c.id, quote)) continue
                val al = aliases(found)
                val updated = found.copy(
                    mentions = found.mentions + 1, lastSeenAt = maxOf(found.lastSeenAt, u.startAt), firstSeenAt = minOf(found.firstSeenAt, u.startAt),
                    status = status(found.status, intent),
                    summary = found.summary.ifBlank { t.s("about").take(200) },
                    aliases = if (!found.name.equals(name, true) && al.none { it.equals(name, true) }) Js.json.encodeToString(ListSerializer(String.serializer()), (al + name).takeLast(12)) else found.aliases,
                )
                db.memory().updateEntity(updated); updated
            } else {
                val row = EntityRow(kind = kind, name = name, summary = t.s("about").take(200), importance = 2, firstSeenAt = u.startAt, lastSeenAt = u.startAt, status = status(null, intent))
                row.copy(id = db.memory().insertEntity(row))
            }
            db.mentions().insert(Mention(entityId = e.id, conversationId = c.id, utteranceId = u.id, at = u.startAt, quote = quote, speaker = u.speaker, intent = intent))
            added++
        }
        db.memory().putKv(Kv("things:${c.id}", "1", System.currentTimeMillis()))
        return added
    }

    /** Conversations from [since] that the things pass hasn't read yet, newest first. */
    suspend fun unscanned(since: Long, limit: Int): List<Conversation> =
        db.conversations().inRangeOnce(since, System.currentTimeMillis()).filter { it.state == "done" && it.category != "media" && db.memory().kv("things:${it.id}") == null }
            .sortedByDescending { it.startAt }.take(limit)

    /** Merges [from] into [into]: names become aliases, mentions move, counts add up. */
    suspend fun merge(from: EntityRow, into: EntityRow) {
        if (from.id == into.id) return
        val al = (aliases(into) + from.name + aliases(from)).filter { !it.equals(into.name, true) }.distinctBy { it.lowercase() }
        db.mentions().move(from.id, into.id)
        db.memory().updateEntity(into.copy(aliases = Js.json.encodeToString(ListSerializer(String.serializer()), al.takeLast(16)), mentions = into.mentions + from.mentions,
            firstSeenAt = minOf(into.firstSeenAt, from.firstSeenAt), lastSeenAt = maxOf(into.lastSeenAt, from.lastSeenAt), status = into.status ?: from.status))
        db.memory().deleteEntity(from.id)
    }

    suspend fun delete(e: EntityRow) { db.mentions().deleteFor(e.id); db.memory().deleteEntity(e.id) }

    // ── Optional online look-up (Wikidata) ──

    /**
     * Looks up a few things not yet looked up, by name only (never what was said about them),
     * and keeps a one-line description. Cached: each thing is looked up once.
     */
    suspend fun lookUp(max: Int = 15): Int {
        if (!settings.value.thingsOnline) return 0
        val todo = db.memory().allEntities().filter { it.kind in KEYS && it.extId == null }.sortedByDescending { it.mentions * 10 + (it.lastSeenAt / 86_400_000L) }.take(max)
        var n = 0
        for (e in todo) {
            val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&type=item&limit=5&language=en&uselang=en&search=" + URLEncoder.encode(e.name, "UTF-8")
            val body = runCatching {
                cloud.sendPublic("Wikidata", url.length.toLong(), "Look up \"${e.name}\"", allowed = true, feature = "Things look-up") { withContext(Dispatchers.IO) { web().fetch(url, appAgent = true) } }
            }.onFailure { Log.w(TAG, "look-up failed: ${it.message}") }.getOrNull() ?: break
            val results = runCatching { (Js.json.parseToJsonElement(body) as JsonObject)["search"] as JsonArray }.getOrNull()?.mapNotNull { r ->
                val o = r as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                id to ((o["description"] as? JsonPrimitive)?.content.orEmpty())
            }.orEmpty()
            val hit = pick(e.kind, results)
            db.memory().updateEntity(e.copy(extId = hit?.first ?: "none", detail = hit?.second?.takeIf { it.isNotBlank() }?.take(160)))
            n++
        }
        return n
    }
}
