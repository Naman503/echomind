package app.echomind.brain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The check against hallucination: every item a model extracts must be backed by
 * the transcript it read. Quotes must really be there (spelling- and
 * script-tolerant), names must occur, and cited line numbers must exist.
 * Unsupported items are dropped before anything is saved.
 */
object Grounding {
    /** One part of a transcript: its numbered lines ("L12 [4:05 PM] me: …"). */
    class Part(lines: List<String>) {
        val byNumber: Map<Int, String> = lines.mapNotNull { l -> Regex("^L(\\d+)\\b").find(l)?.groupValues?.get(1)?.toIntOrNull()?.let { it to l.substringAfter(": ", l) } }.toMap()
        val keys: Set<String> = byNumber.values.flatMap { TextNorm.keyTerms(it) }.toSet()
        fun has(n: Int) = n in byNumber
    }

    /** Share of the quote's key words that are in the transcript. */
    fun support(quote: String, inKeys: Set<String>): Double {
        val q = TextNorm.keyTerms(quote).toSet()
        if (q.isEmpty()) return 0.0
        return q.count { it in inKeys }.toDouble() / q.size
    }

    private fun lines(o: JsonObject): List<Int> = (o["lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

    /** Backed by a real quote, or by real cited lines. */
    private fun backed(o: JsonObject, p: Part): Boolean {
        val q = str(o, "quote")
        if (q.length >= 4) return support(q, p.keys) >= 0.6
        val cited = lines(o)
        return cited.isNotEmpty() && cited.all(p::has)
    }

    /** A name occurs in the transcript (any of its words, transliterated). */
    fun nameIn(name: String, p: Part): Boolean {
        val k = TextNorm.keyTerms(name)
        return k.isNotEmpty() && k.any { it in p.keys }
    }

    /** Drops unsupported tasks, events, facts, people, relations, note points and topics from one pass's answer. */
    fun check(o: JsonObject, p: Part): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(o)
        fun filter(key: String, keep: (JsonObject) -> Boolean) {
            val arr = o[key] as? JsonArray ?: return
            out[key] = JsonArray(arr.filter { e -> (e as? JsonObject)?.let(keep) ?: false })
        }
        filter("tasks") { backed(it, p) }
        filter("events") { backed(it, p) }
        filter("task_updates") { str(it, "quote").let { q -> q.length < 4 || support(q, p.keys) >= 0.6 } }
        filter("facts") { f -> lines(f).let { it.isNotEmpty() && it.all(p::has) } }
        filter("people") { nameIn(str(it, "name"), p) }
        filter("relations") { nameIn(str(it, "from"), p) && (nameIn(str(it, "to"), p) || lines(it).all(p::has)) }
        filter("note_updates") { n -> lines(n).let { it.isEmpty() || it.all(p::has) } }
        filter("topics") { t -> lines(t).let { it.isEmpty() || it.all(p::has) } }
        filter("speakers") { s -> nameIn(str(s, "name"), p) }
        return JsonObject(out)
    }

    /** The coach may only quote what the owner really said on that line. */
    fun ownerSaid(said: String, lineText: String?): Boolean {
        if (lineText == null) return false
        if (said.isBlank()) return true
        return support(said, TextNorm.keyTerms(lineText).toSet()) >= 0.5
    }

    private val common = setOf("i", "i'm", "i've", "i'd", "i'll", "today", "tomorrow", "yesterday", "tonight", "morning", "evening", "afternoon", "night",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december", "god", "ai", "english", "hindi", "hinglish")

    /**
     * Capitalised names in [text] (not at the start of a sentence) that appear nowhere in [source]:
     * a sign the writer invented people or places.
     */
    fun unknownNames(text: String, source: String): List<String> {
        val src = TextNorm.keyTerms(source).toSet() + source.lowercase().split(Regex("[^\\p{L}]+")).toSet()
        val out = LinkedHashSet<String>()
        for (sentence in text.split(Regex("(?<=[.!?…])\\s+|\\n+"))) {
            val words = sentence.trim().split(Regex("\\s+"))
            for ((i, raw) in words.withIndex()) {
                if (i == 0) continue
                val w = raw.trim('"', '“', '”', '‘', '’', ',', '.', '!', '?', ';', ':', '(', ')', '—', '-')
                if (w.length < 3 || !w.first().isUpperCase() || w.lowercase() in common) continue
                val key = TextNorm.keyTerms(w)
                if (w.lowercase() !in src && key.none { it in src }) out.add(w)
            }
        }
        return out.toList()
    }
}
