package app.echomind.brain

import android.util.Log
import app.echomind.ai.Ai
import app.echomind.ai.Deep
import app.echomind.ai.Llm
import app.echomind.ai.LlmRequest
import app.echomind.ai.LocalLlm
import app.echomind.ai.Prompts
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.Analysis
import app.echomind.data.Conversation
import app.echomind.data.Db
import app.echomind.data.Utterance
import app.echomind.util.Js
import app.echomind.util.int
import app.echomind.util.objects
import app.echomind.util.str
import app.echomind.util.strings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable data class Topic(val name: String, val detail: String = "", val lines: List<Long> = emptyList())
@Serializable data class Fix(val line: Long, val at: Long, val said: String, val better: String, val why: String = "")
@Serializable data class NervousMoment(val line: Long, val at: Long, val quote: String, val sign: String, val tip: String = "")
@Serializable data class Slip(val line: Long, val at: Long, val said: String, val problem: String, val fix: String = "")

@Serializable
data class Coaching(
    val englishFixes: List<Fix> = emptyList(),
    val betterWays: List<Fix> = emptyList(),
    val nervous: List<NervousMoment> = emptyList(),
    val slips: List<Slip> = emptyList(),
    val confidence: Int? = null,
    val clarity: Int? = null,
    val strengths: List<String> = emptyList(),
) {
    val count get() = englishFixes.size + betterWays.size + nervous.size + slips.size
}

/** What the careful pipeline knows about one conversation. */
@Serializable
data class ConvAnalysis(
    val version: Int = Analyzer.VERSION,
    val topics: List<Topic> = emptyList(),
    val decisions: List<String> = emptyList(),
    val stats: SpeechStats.Result? = null,
    val coach: Coaching? = null,
    val coachedAt: Long? = null,
)

/**
 * Reads a conversation the way a small model can do well: in pieces that fit
 * its context, one job per request (understand, commitments, knowledge), and
 * later the speaking coach. The results use the same JSON shape as the
 * all-in-one extraction, so the rest of [Enricher] works on either.
 */
class Analyzer(private val db: Db, private val ai: Ai, private val settings: SettingsStore) {

    /** Lines numbered for the model: "L12 [4:05 PM] me: …" (global numbering across parts). */
    fun numbered(utts: List<Utterance>, hesitant: Set<Long> = emptySet()): List<String> {
        val rules = Corrections.parse(settings.value.corrections)
        return utts.mapIndexed { i, u -> "L${i + 1} [${Prompts.time(u.startAt)}] ${u.speaker ?: "?"}${if (u.id in hesitant) " [hesitant]" else ""}: ${Corrections.apply(u.text, rules)}" }
    }

    /** What the owner wrote about themselves, so names and projects are read right. */
    fun aboutOwner(): String = settings.value.aboutMe.trim().take(500).let { if (it.isEmpty()) "" else "About the owner (background, in their words; never a source of new facts):\n$it\n\n" }

    /** Splits lines so each part, with its prompt and answer, fits the model. */
    fun parts(lines: List<String>, llm: Llm, overheadTokens: Int, answerTokens: Int): List<List<String>> {
        val local = llm as? LocalLlm
        val budget = if (local == null) llm.promptBudgetChars * 2 / 3 else (local.contextTokens.coerceAtLeast(4096) - overheadTokens - answerTokens - 250).coerceAtLeast(500)
        val cost = { s: String -> if (local == null) s.length else LocalLlm.estimateTokens(s) }
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>(); var size = 0
        for (l0 in lines) {
            val l = if (cost(l0) > budget / 2) l0.take(l0.length / 2) else l0
            val c = cost(l) + 1
            if (size + c > budget && cur.isNotEmpty()) { out.add(cur); cur = ArrayList(); size = 0 }
            cur.add(l); size += c
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun header(c: Conversation, part: Int, of: Int) = buildString {
        append(aboutOwner())
        append("Date: ").append(Prompts.day(c.startAt)).append('\n')
        append("Time: ").append(Prompts.time(c.startAt)).append(" to ").append(Prompts.time(c.endAt)).append('\n')
        if (c.source == "call") append("This is a phone call").append(c.contact?.let { " with $it" } ?: "").append(". \"me\" is the owner.\n")
        if (of > 1) append("This is part $part of $of of one long conversation.\n")
    }

    private fun tok(s: String) = LocalLlm.estimateTokens(s)

    /**
     * Understand → commitments → knowledge, per part. Returns one object per part with
     * the extraction's keys plus "topics" and "decisions".
     */
    suspend fun extract(c: Conversation, utts: List<Utterance>, liveTasks: List<String>, noteTitles: List<String>, people: String): List<JsonObject> {
        val llm = ai.llm(Feature.NOTES)
        val lang = settings.value.notesLanguage
        val lines = numbered(utts)
        val overhead = tok(Deep.commitmentsSystem(lang)) + 450
        val parts = parts(lines, llm, overhead, 750)
        val out = ArrayList<JsonObject>()
        for ((i, part) in parts.withIndex()) {
            val text = part.joinToString("\n")
            val head = header(c, i + 1, parts.size)
            val label = Prompts.time(c.startAt) + if (parts.size > 1) " (${i + 1}/${parts.size})" else ""

            val req = LlmRequest(Feature.NOTES, "Understanding · $label", Deep.understandSystem(lang), head + "\nTranscript:\n" + text, Deep.understand, maxTokens = 650, noThink = true)
            var a = ai.json(req)
            // A blank answer is worth one more try; small models sometimes stop early.
            if (a.str("title").isNullOrBlank() && a.str("summary").isNullOrBlank()) a = runCatching { ai.json(req.copy(temperature = 0.5)) }.getOrDefault(a)
            val media = a["is_media"].let { (it as? JsonPrimitive)?.content == "true" }
            val merged = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(a)
            if (!media) {
                // Only tasks that share words with this part, numbered as on the full list, so the model
                // can report on them without copying unrelated ones into new titles.
                val terms = TextNorm.keyTerms(text).toSet()
                val relevant = liveTasks.withIndex().filter { (_, t) -> TextNorm.keyTerms(t).any { it in terms } }.take(6)
                val tasksText = if (relevant.isEmpty()) "" else "Existing tasks (reference only; report changes in task_updates, never copy them as new tasks):\n" +
                    relevant.joinToString("\n") { (n, t) -> "T${n + 1}. $t" } + "\n\n"
                runCatching {
                    ai.json(LlmRequest(Feature.NOTES, "Finding tasks · $label", Deep.commitmentsSystem(lang), tasksText + head + "\nTranscript:\n" + text, Deep.commitments, maxTokens = 750, noThink = true))
                }.onSuccess { merged.putAll(it) }.onFailure { Log.w(TAG, "commitments failed", it) }
                val known = buildString {
                    if (noteTitles.isNotEmpty()) append("Existing notes: ").append(noteTitles.take(15).joinToString(" | ")).append("\n")
                    if (people.isNotBlank()) append("People they know:\n").append(people.take(500)).append("\n")
                    if (isNotEmpty()) append('\n')
                }
                runCatching {
                    ai.json(LlmRequest(Feature.NOTES, "Remembering · $label", Deep.knowledgeSystem(lang), known + head + "\nTranscript:\n" + text, Deep.knowledge, maxTokens = 750, noThink = true))
                }.onSuccess { merged.putAll(it) }.onFailure { Log.w(TAG, "knowledge failed", it) }
                if (settings.value.extractThings) merged.putAll(things(head, text, label))
            }
            // Nothing is kept that this part of the transcript doesn't back up.
            out.add(Grounding.check(clean(JsonObject(merged)) as JsonObject, Grounding.Part(part)))
        }
        return out
    }

    /** The named-things pass on one part (also used to scan older conversations). */
    suspend fun things(head: String, text: String, label: String): JsonObject = runCatching {
        ai.json(LlmRequest(Feature.NOTES, "Noting names · $label", Deep.thingsSystem(settings.value.notesLanguage), head + "\nTranscript:\n" + text, Deep.things, maxTokens = 500, noThink = true))
    }.onFailure { Log.w(TAG, "things failed", it) }.getOrDefault(JsonObject(emptyMap()))

    /** Things only, for a conversation analysed before this pass existed. Grounded like the rest. */
    suspend fun thingsOnly(c: Conversation, utts: List<Utterance>): List<JsonObject> {
        val llm = ai.llm(Feature.NOTES)
        val lines = numbered(utts)
        val parts = parts(lines, llm, tok(Deep.thingsSystem(settings.value.notesLanguage)) + 450, 500)
        return parts.mapIndexed { i, part ->
            val label = Prompts.time(c.startAt) + if (parts.size > 1) " (${i + 1}/${parts.size})" else ""
            Grounding.check(things(header(c, i + 1, parts.size), part.joinToString("\n"), label), Grounding.Part(part))
        }
    }

    /** "Not specified" and friends become empty strings, so they never show up as dates or places. */
    private fun clean(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { clean(it.value) })
        is JsonArray -> JsonArray(e.map { clean(it) })
        is JsonPrimitive -> if (e.isString && e.content.trim().lowercase().trimEnd('.') in Tasks.PLACEHOLDERS) JsonPrimitive("") else e
        else -> e
    }

    /** Saves topics, decisions and speech measures for a conversation, keeping any coaching already there. */
    suspend fun saveConversation(c: Conversation, utts: List<Utterance>, outputs: List<JsonObject>) {
        val idOf = { n: Int -> utts.getOrNull(n - 1)?.id }
        val topics = outputs.flatMap { it.objects("topics") }.mapNotNull { t ->
            val name = t.str("name")?.trim()?.takeIf { it.length >= 2 } ?: return@mapNotNull null
            Topic(name.take(60), t.str("detail")?.trim().orEmpty().take(240), ((t["lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.let(idOf) }).orEmpty())
        }.distinctBy { it.name.lowercase() }.take(8)
        val decisions = outputs.flatMap { it.strings("decisions") }.distinct().take(8)
        val old = load(c.id)
        save(c.id, (old ?: ConvAnalysis()).copy(version = VERSION, topics = topics, decisions = decisions, stats = stats(utts)))
    }

    fun stats(utts: List<Utterance>) = SpeechStats.analyze(utts.map { SpeechStats.Line(it.id, it.speaker, it.text, it.startAt, it.endAt) })

    suspend fun load(conversationId: Long): ConvAnalysis? = db.analyses().get("conv", conversationId.toString())?.let { decode(it.json) }

    fun decode(json: String): ConvAnalysis? = runCatching { Js.json.decodeFromString(ConvAnalysis.serializer(), json) }.getOrNull()

    suspend fun save(conversationId: Long, a: ConvAnalysis) {
        db.analyses().upsert(Analysis("conv", conversationId.toString(), Js.json.encodeToString(ConvAnalysis.serializer(), a), a.version, System.currentTimeMillis(), ai.llm(Feature.NOTES).id))
    }

    /** The speaking coach for one conversation. Null when the owner barely spoke. */
    suspend fun coach(c: Conversation, utts: List<Utterance>): Coaching? {
        val s = settings.value
        val wants = buildSet { if (s.coachEnglish) add("english"); if (s.coachBetter) add("better"); if (s.coachNervous) add("nervous"); if (s.coachSlips) add("slips") }
        val stats = stats(utts)
        if (wants.isEmpty() || stats.words < 12) return Coaching()
        val llm = ai.llm(Feature.JOURNAL)
        val system = Deep.coachSystem(s.coachStrictness, s.coachMax, wants)
        val lines = numbered(utts, stats.hesitant.toSet())
        val parts = parts(lines, llm, tok(system) + 250, 900)
        // Parts where the owner said little are skipped.
        val useful = parts.withIndex().filter { (_, p) -> p.sumOf { l -> if (" me: " in l || " me [hesitant]: " in l) SpeechStats.words(l).size else 0 } >= 8 }
        val results = ArrayList<JsonObject>()
        for ((i, part) in useful) {
            val r = runCatching {
                ai.json(LlmRequest(Feature.JOURNAL, "Speaking coach · ${Prompts.time(c.startAt)}" + if (parts.size > 1) " (${i + 1}/${parts.size})" else "",
                    system, header(c, i + 1, parts.size) + "\nTranscript:\n" + part.joinToString("\n"), Deep.coach, maxTokens = 900, temperature = 0.3))
            }.onFailure { Log.w(TAG, "coach failed", it) }.getOrNull() ?: continue
            results.add(r)
        }
        if (results.isEmpty() && useful.isNotEmpty()) throw IllegalStateException("The coach could not read this conversation")
        val at = { n: Int -> utts.getOrNull(n - 1) }
        fun fixes(key: String) = results.flatMap { it.objects(key) }.mapNotNull { o ->
            val u = at(o.int("line") ?: 0)?.takeIf { it.speaker == "me" } ?: return@mapNotNull null
            val said = o.str("said")?.trim().orEmpty(); val better = o.str("better")?.trim().orEmpty()
            if (!Grounding.ownerSaid(said, u.text)) return@mapNotNull null
            // "why" is a short reason; a copy of the sentence is no reason.
            val why = o.str("why")?.trim().orEmpty().takeIf { it.length <= 140 && !it.equals(said, true) && !said.startsWith(it.trimEnd(',', '.'), true) }.orEmpty()
            if (said.isEmpty() || better.isEmpty() || said.equals(better, ignoreCase = true)) null else Fix(u.id, u.startAt, said, better, why)
        }
        val nervous = results.flatMap { it.objects("nervous") }.mapNotNull { o ->
            val u = at(o.int("line") ?: 0)?.takeIf { it.speaker == "me" && Grounding.ownerSaid(o.str("quote").orEmpty(), it.text) } ?: return@mapNotNull null
            NervousMoment(u.id, u.startAt, o.str("quote")?.trim().orEmpty().ifEmpty { u.text.take(160) }, o.str("sign")?.trim().orEmpty(), o.str("tip")?.trim().orEmpty())
        }
        val slips = results.flatMap { it.objects("slips") }.mapNotNull { o ->
            val u = at(o.int("line") ?: 0)?.takeIf { it.speaker == "me" && Grounding.ownerSaid(o.str("said").orEmpty(), it.text) } ?: return@mapNotNull null
            val problem = o.str("problem")?.trim().orEmpty()
            if (problem.isEmpty()) null else Slip(u.id, u.startAt, o.str("said")?.trim().orEmpty().ifEmpty { u.text.take(160) }, problem, o.str("fix")?.trim().orEmpty())
        }
        val conf = results.mapNotNull { it.int("confidence")?.coerceIn(0, 100) }
        val clar = results.mapNotNull { it.int("clarity")?.coerceIn(0, 100) }
        return Coaching(
            englishFixes = fixes("english_fixes").distinctBy { it.said.lowercase() }.take(s.coachMax),
            betterWays = fixes("better_ways").distinctBy { it.said.lowercase() }.take(s.coachMax),
            nervous = nervous.distinctBy { it.line }.take(s.coachMax),
            slips = slips.distinctBy { it.line }.take(s.coachMax),
            confidence = conf.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            clarity = clar.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            strengths = results.flatMap { it.strings("strengths") }.distinct().take(3),
        )
    }

    companion object {
        /** Bump when the pipeline improves; older conversations are read again at night. */
        const val VERSION = 3
        private const val TAG = "EchoAnalyzer"
    }
}
