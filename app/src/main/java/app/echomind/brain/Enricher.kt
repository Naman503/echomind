package app.echomind.brain

import android.util.Log
import app.echomind.ai.Ai
import app.echomind.ai.LlmException
import app.echomind.ai.LlmRequest
import app.echomind.ai.Prompts
import app.echomind.ai.Schemas
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.Conversation
import app.echomind.data.Db
import app.echomind.data.Utterance
import app.echomind.stt.VoiceId
import app.echomind.util.Js
import app.echomind.util.bool
import app.echomind.util.dbl
import app.echomind.util.int
import app.echomind.util.objects
import app.echomind.util.str
import app.echomind.util.strings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.ZoneId

/**
 * Turns one finished conversation into everything else: title and summary,
 * who was there, memories, tasks and reminders, and updates to living notes.
 *
 * Lines are numbered L1…Ln in the prompt and the model cites numbers, which
 * this class maps back to real transcript lines — so every derived item links
 * to the exact moment it came from, and the prompt stays small. Long
 * conversations are split into parts that fit the model, then merged.
 */
class Enricher(
    private val db: Db,
    private val ai: Ai,
    private val settings: SettingsStore,
    private val memory: Memory,
    private val notes: Notes,
    private val tasks: Tasks,
    private val embedder: Embedder,
    private val nudges: () -> Nudges,
) {
    data class Result(val tasks: Int, val facts: Int, val notes: Int)

    suspend fun enrich(c: Conversation): Result {
        val lines = db.utterances().forConversation(c.id)
        if (lines.isEmpty()) {
            db.conversations().update(c.copy(state = "skipped"))
            return Result(0, 0, 0)
        }
        db.conversations().update(c.copy(state = "working", attempts = c.attempts + 1))
        groupSpeakers(c, lines)
        val utts = db.utterances().forConversation(c.id)
        val llm = ai.llm(Feature.NOTES)
        val local = llm.local
        val lang = settings.value.notesLanguage

        val ctx = Prompts.Context(
            profile = memory.profile().take(if (local) 700 else 4_000),
            people = memory.peopleContext(if (local) 600 else 3_000),
            openTasks = db.tasks().live().sortedByDescending { it.createdAt }.take(if (local) 8 else 30).map { it.title },
            noteTitles = notes.titles().take(if (local) 12 else 30),
            earlier = earlierToday(c, if (local) 400 else 2_500),
        )
        val fixed = Prompts.extractSystem(lang).length + Prompts.extractUser(ctx, c.startAt, c.endAt, c.source, c.contact, "").length
        val budget = (llm.promptBudgetChars - fixed).coerceAtLeast(1_800)
        val numbered = utts.mapIndexed { i, u -> "L${i + 1} [${Prompts.time(u.startAt)}] ${u.speaker ?: "?"}: ${u.text}" }
        val parts = split(numbered, budget)

        val outputs = ArrayList<JsonObject>()
        for ((i, part) in parts.withIndex()) {
            val purpose = (if (c.source == "call") "Call notes" else "Notes") + " · ${Prompts.time(c.startAt)}" + if (parts.size > 1) " (part ${i + 1}/${parts.size})" else ""
            val user = Prompts.extractUser(ctx, c.startAt, c.endAt, c.source, c.contact, part.joinToString("\n"), (i + 1) to parts.size)
            outputs.add(ai.json(LlmRequest(Feature.NOTES, purpose, Prompts.extractSystem(lang), user, Schemas.extract, maxTokens = if (local) 900 else 2_500)))
        }

        val idOf = { n: Int -> utts.getOrNull(n - 1)?.id }
        fun sources(o: JsonObject): List<Long> = ((o["lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.let(idOf) }).orEmpty()

        // Title / summary
        var title = outputs.first().str("title").orEmpty()
        var summary = outputs.joinToString(" ") { it.str("summary").orEmpty() }.trim()
        var keyPoints = outputs.flatMap { it.strings("key_points") }
        if (outputs.size > 1) {
            runCatching {
                val m = ai.json(LlmRequest(Feature.NOTES, "Combining parts · ${Prompts.time(c.startAt)}", Prompts.mergeSystem(lang),
                    outputs.mapIndexed { i, o -> "Part ${i + 1}: ${o.str("title")}\n${o.str("summary")}" }.joinToString("\n\n"), Schemas.merge, maxTokens = 500))
                m.str("title")?.takeIf { it.isNotBlank() }?.let { title = it }
                m.str("summary")?.takeIf { it.isNotBlank() }?.let { summary = it }
                m.strings("key_points").takeIf { it.isNotEmpty() }?.let { keyPoints = it }
            }.onFailure { Log.w(TAG, "merge failed", it) }
        }
        val media = outputs.count { it.bool("is_media") == true } * 2 > outputs.size
        val moods = outputs.mapNotNull { it.dbl("mood") }
        val category = if (media) "media" else outputs.groupingBy { it.str("category") ?: "other" }.eachCount().maxByOrNull { it.value }?.key

        // Speaker names the model recognised.
        for (o in outputs) for (s in o.objects("speakers")) {
            val label = s.str("label")?.trim() ?: continue
            val name = s.str("name")?.trim()?.takeIf { it.length in 2..40 } ?: continue
            if (label.startsWith("S")) db.utterances().renameSpeaker(c.id, label, name)
        }

        val at = c.endAt
        var factCount = 0
        var taskCount = 0
        var noteIds = emptyList<Long>()
        val people = outputs.flatMap { it.objects("people") }.mapNotNull { p -> p.str("name")?.let { it to p.str("who") } }.distinctBy { it.first.lowercase() }
        if (!media) {
            for ((name, who) in people) memory.link("person", name, who, at)
            val candidates = outputs.flatMap { it.objects("facts") }.mapNotNull { f ->
                val st = f.str("statement")?.trim().orEmpty()
                if (st.length < 6) null
                else Memory.Candidate(f.str("subject").orEmpty().ifBlank { "Me" }, st, f.str("kind") ?: "fact", f.int("importance") ?: 3, sources(f))
            }
            factCount = memory.reconcile(candidates, c.id, at)

            val found = outputs.flatMap { it.objects("tasks") }.map { t ->
                Tasks.Found(
                    title = t.str("title").orEmpty(),
                    owner = t.str("owner") ?: "unclear",
                    dueText = t.str("due_text"),
                    due = When.resolve(t.str("due_iso"), t.str("due_text"), c.startAt),
                    isReminder = t.bool("is_reminder") == true,
                    confidence = (t.dbl("confidence") ?: 0.6).toFloat(),
                    quote = t.str("quote"),
                    sourceIds = sources(t),
                )
            }.filter { it.owner != "other" || it.isReminder }
            taskCount = tasks.addFound(found, c.id, at)

            val updates = outputs.flatMap { it.objects("note_updates") }.groupBy { it.str("topic").orEmpty().trim() }.mapNotNull { (topic, list) ->
                if (topic.isBlank()) null else Notes.Update(topic, list.flatMap { it.strings("points") }.distinct(), list.flatMap { sources(it) })
            }
            noteIds = notes.apply(updates, c.id, title.ifBlank { "a conversation" }, at)
        }

        val done = c.copy(
            title = title.ifBlank { "Conversation" }.take(90),
            summary = summary.take(1_500),
            category = category,
            mood = moods.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            keyPoints = Js.json.encodeToString(ListSerializer(String.serializer()), keyPoints.take(8)),
            people = Js.json.encodeToString(ListSerializer(String.serializer()), people.map { it.first }.take(12)),
            state = "done",
            attempts = c.attempts + 1,
            provider = llm.id,
            error = null,
            processedAt = System.currentTimeMillis(),
            ignored = media,
        )
        db.conversations().update(done)
        if (!media) {
            index(done, utts)
            val questions = outputs.flatMap { it.objects("open_questions") }.mapNotNull { q -> q.str("question")?.trim()?.takeIf { it.length > 5 }?.let { it to q.str("asked_by") } }
            runCatching { nudges().fromConversation(done, questions, taskCount) }
        }
        return Result(taskCount, factCount, noteIds.size)
    }

    suspend fun markFailed(c: Conversation, e: Throwable) {
        val latest = db.conversations().get(c.id) ?: c
        val permanent = (e as? LlmException)?.retryable == false
        db.conversations().update(latest.copy(state = if (permanent || latest.attempts >= 3) "failed" else "ready", error = e.message?.take(300)))
    }

    /** Lines that are not the owner are grouped into Speaker 1, 2… by voice within this conversation. */
    private suspend fun groupSpeakers(c: Conversation, lines: List<Utterance>) {
        val candidates = lines.filter { it.speaker == null || (it.speaker.startsWith("S") && it.speaker.length <= 3) }
        if (candidates.size < 2) return
        val prints = candidates.map { it.id }.chunked(500).flatMap { db.vectors().forOwners("s", it) }.associateBy { it.ownerId }
        val withPrints = candidates.map { u -> prints[u.id]?.let { VoiceId.normalize(Vectors.dequantize(it.vector, it.scale)) } }
        if (withPrints.count { it != null } < 2) return
        val clusters = VoiceId.cluster(withPrints)
        candidates.zip(clusters).forEach { (u, k) -> if (k != null) db.utterances().update(u.copy(speaker = "S${k + 1}")) }
    }

    private suspend fun earlierToday(c: Conversation, maxChars: Int): String {
        val dayStart = Instant.ofEpochMilli(c.startAt).atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val sb = StringBuilder()
        for (e in db.conversations().doneBefore(dayStart, c.startAt, 12).reversed()) {
            val line = "- ${Prompts.time(e.startAt)} ${e.title.orEmpty()}${e.summary?.let { ": " + it.take(140) } ?: ""}\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        return sb.toString().trimEnd()
    }

    private fun split(lines: List<String>, budget: Int): List<List<String>> {
        val parts = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var size = 0
        for (l in lines) {
            val line = if (l.length > budget / 2) l.take(budget / 2) else l
            if (size + line.length + 1 > budget && cur.isNotEmpty()) {
                parts.add(cur); cur = ArrayList(); size = 0
            }
            cur.add(line); size += line.length + 1
        }
        if (cur.isNotEmpty()) parts.add(cur)
        return parts
    }

    /** Search vectors: the summary, plus chunks of a few lines each. */
    private suspend fun index(c: Conversation, utts: List<Utterance>) {
        if (!settings.value.semanticSearch || !embedder.ready()) return
        val items = ArrayList<Embedder.Item>()
        items.add(Embedder.Item("c", c.id, c.title.orEmpty(), "${c.summary.orEmpty()}\n${c.keyPoints.orEmpty()}"))
        var chunk = ArrayList<Utterance>()
        var size = 0
        for (u in utts) {
            chunk.add(u); size += u.text.length
            if (size > 450 || chunk.size >= 8) {
                items.add(Embedder.Item("u", chunk.first().id, c.title.orEmpty(), chunk.joinToString("\n") { "${it.speaker ?: ""}: ${it.text}" }))
                chunk = ArrayList(); size = 0
            }
        }
        if (chunk.isNotEmpty()) items.add(Embedder.Item("u", chunk.first().id, c.title.orEmpty(), chunk.joinToString("\n") { "${it.speaker ?: ""}: ${it.text}" }))
        embedder.index(items)
    }

    companion object {
        private const val TAG = "EchoEnricher"
    }
}
