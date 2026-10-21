package app.echomind.brain

import android.util.Log
import app.echomind.ai.Ai
import app.echomind.ai.LlmRequest
import app.echomind.ai.Prompts
import app.echomind.ai.Schemas
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.Db
import app.echomind.data.EntityRow
import app.echomind.data.Fact
import app.echomind.data.Kv
import app.echomind.util.Js
import app.echomind.util.int
import app.echomind.util.objects
import app.echomind.util.str
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Long-term memory: people and things (entities), durable facts, and a short
 * profile that every prompt starts with.
 *
 * Writes follow Mem0's add / update / replace / skip decision, but nothing is
 * ever silently overwritten: a replaced fact is closed with an end date and a
 * pointer to what replaced it (Zep-style), so the history stays answerable.
 * Matching is deterministic first (names, aliases, shared words) and the model
 * is only asked when an existing memory might be about the same thing.
 */
class Memory(private val db: Db, private val ai: Ai, private val settings: SettingsStore, private val embedder: Embedder) {

    suspend fun profile(): String = db.memory().kv(PROFILE).orEmpty()

    suspend fun setProfile(text: String) = db.memory().putKv(Kv(PROFILE, text.trim(), System.currentTimeMillis()))

    /** "- Priya (person): colleague on the Q3 budget" lines, most relevant first. */
    suspend fun peopleContext(maxChars: Int): String {
        val now = System.currentTimeMillis()
        val rows = db.memory().allEntities().sortedByDescending { score(it, now) }
        val sb = StringBuilder()
        for (e in rows) {
            val line = "- ${e.name} (${e.kind})${if (e.summary.isNotBlank()) ": ${e.summary}" else ""}\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        return sb.toString().trimEnd()
    }

    private fun score(e: EntityRow, now: Long): Double {
        val days = (now - e.lastSeenAt) / 86_400_000.0
        return (if (e.pinned) 100.0 else 0.0) + e.importance * 10 + minOf(10.0, Math.log(1.0 + e.mentions) * 3) - minOf(20.0, days / 3)
    }

    private fun key(name: String) = TextNorm.searchable(name).split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    /** Finds the entity a name refers to (by name or any alias, spelling-tolerant), or creates it. */
    suspend fun link(kind: String, name: String, who: String?, at: Long): EntityRow? {
        val clean = name.trim().trim('.', ',')
        if (clean.length < 2 || clean.equals("me", true) || clean.equals("speaker", true)) return null
        val k = key(clean)
        val all = db.memory().allEntities()
        val found = all.firstOrNull { e ->
            (e.kind == kind || kind == "person" && e.kind == "person") &&
                (key(e.name) == k || aliases(e).any { key(it) == k } || (k.split(' ').size == 1 && key(e.name).split(' ').firstOrNull() == k && e.kind == "person"))
        }
        if (found != null) {
            val updated = found.copy(
                lastSeenAt = maxOf(found.lastSeenAt, at),
                mentions = found.mentions + 1,
                summary = found.summary.ifBlank { who.orEmpty() },
                aliases = if (!found.name.equals(clean, true) && aliases(found).none { it.equals(clean, true) })
                    Js.json.encodeToString(ListSerializer(String.serializer()), aliases(found) + clean) else found.aliases,
            )
            db.memory().updateEntity(updated)
            return updated
        }
        val row = EntityRow(kind = kind, name = clean, summary = who.orEmpty(), firstSeenAt = at, lastSeenAt = at)
        return row.copy(id = db.memory().insertEntity(row))
    }

    private fun aliases(e: EntityRow): List<String> = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), e.aliases) }.getOrDefault(emptyList())

    data class Candidate(val subject: String, val statement: String, val kind: String, val importance: Int, val sourceIds: List<Long>)

    /** Merges today's candidate facts into memory. Returns how many were stored or changed. */
    suspend fun reconcile(candidates: List<Candidate>, conversationId: Long, at: Long): Int {
        if (candidates.isEmpty()) return 0
        val active = db.memory().activeFacts()
        val withSimilar = ArrayList<Pair<Candidate, List<Fact>>>()
        var changed = 0
        for (c in candidates) {
            val similar = similar(c, active)
            if (similar.any { normalized(it.statement) == normalized(c.statement) }) {
                similar.first { normalized(it.statement) == normalized(c.statement) }.let { touch(it, at) }
                continue
            }
            if (similar.isEmpty()) {
                add(c, conversationId, at); changed++
            } else {
                withSimilar.add(c to similar)
            }
        }
        if (withSimilar.isEmpty()) return changed
        if (!ai.ready(Feature.MEMORY)) {
            for ((c, _) in withSimilar) { add(c, conversationId, at); changed++ }
            return changed
        }
        // One call decides all ambiguous candidates; batches keep local prompts small.
        val lang = settings.value.notesLanguage
        for (batch in withSimilar.chunked(if (ai.llm(Feature.MEMORY).local) 4 else 12)) {
            val existing = batch.flatMap { it.second }.distinctBy { it.id }
            val number = existing.withIndex().associate { (i, f) -> f.id to i + 1 }
            val user = buildString {
                append("Existing memories:\n")
                existing.forEachIndexed { i, f -> append("[${i + 1}] ${f.subject}: ${f.statement}\n") }
                append("\nCandidates from today:\n")
                batch.forEachIndexed { i, (c, sims) ->
                    append("(${i + 1}) ${c.subject}: ${c.statement}   — compare with ${sims.joinToString(", ") { "[${number[it.id]}]" }}\n")
                }
            }
            val result = runCatching {
                ai.json(LlmRequest(Feature.MEMORY, "Updating memory", Prompts.reconcileSystem(lang), user, Schemas.reconcile, maxTokens = 600, deep = true))
            }.onFailure { Log.w(TAG, "reconcile failed", it) }.getOrNull()
            val decided = HashSet<Int>()
            for (d in result?.objects("decisions").orEmpty()) {
                val idx = (d.int("candidate") ?: continue) - 1
                val (c, _) = batch.getOrNull(idx) ?: continue
                if (!decided.add(idx)) continue
                val target = d.int("target")?.let { existing.getOrNull(it - 1) }
                val statement = d.str("statement")?.trim().orEmpty().ifEmpty { c.statement }
                when (d.str("op")) {
                    "update" -> if (target != null) {
                        db.memory().updateFact(target.copy(statement = statement, updatedAt = at, mentions = target.mentions + 1,
                            importance = maxOf(target.importance, c.importance), sources = mergeSources(target.sources, c.sourceIds)))
                        changed++
                    } else { add(c.copy(statement = statement), conversationId, at); changed++ }
                    "replace" -> {
                        val newId = add(c.copy(statement = statement), conversationId, at)
                        if (target != null && !target.pinned) db.memory().updateFact(target.copy(status = "superseded", validTo = at, supersededBy = newId, updatedAt = at))
                        changed++
                    }
                    "add" -> { add(c.copy(statement = statement), conversationId, at); changed++ }
                    else -> {}
                }
            }
            // Anything the model skipped deciding is kept rather than lost.
            batch.forEachIndexed { i, (c, _) -> if (i !in decided) { add(c, conversationId, at); changed++ } }
        }
        return changed
    }

    private suspend fun similar(c: Candidate, active: List<Fact>): List<Fact> {
        val subjectKey = key(c.subject)
        val terms = TextNorm.keyTerms(c.statement).toSet()
        return active.filter { f ->
            val sameSubject = key(f.subject) == subjectKey
            val overlap = TextNorm.keyTerms(f.statement).count { it in terms }
            (sameSubject && overlap >= 1) || overlap >= 3
        }.sortedByDescending { f -> TextNorm.keyTerms(f.statement).count { it in terms } }.take(3)
    }

    private fun normalized(s: String) = TextNorm.searchable(s)

    private suspend fun touch(f: Fact, at: Long) = db.memory().updateFact(f.copy(mentions = f.mentions + 1, updatedAt = at))

    private fun mergeSources(json: String, ids: List<Long>): String {
        val old = runCatching { Js.json.decodeFromString(ListSerializer(Long.serializer()), json) }.getOrDefault(emptyList())
        return Js.json.encodeToString(ListSerializer(Long.serializer()), (old + ids).distinct().takeLast(30))
    }

    private suspend fun add(c: Candidate, conversationId: Long, at: Long): Long {
        val entity = if (c.subject.equals("me", true) || c.subject.isBlank()) null
        else link(if (c.kind == "relationship") "person" else guessKind(c.subject), c.subject, null, at)
        val f = Fact(
            entityId = entity?.id,
            subject = if (c.subject.equals("me", true)) "Me" else c.subject.trim(),
            statement = c.statement.trim(),
            kind = c.kind,
            importance = c.importance.coerceIn(1, 5),
            validFrom = at,
            conversationId = conversationId,
            sources = Js.json.encodeToString(ListSerializer(Long.serializer()), c.sourceIds),
            createdAt = at,
            updatedAt = at,
        )
        val id = db.memory().insertFact(f)
        embedder.index(listOf(Embedder.Item("f", id, f.subject, f.statement)))
        return id
    }

    private suspend fun guessKind(subject: String): String {
        val k = key(subject)
        return db.memory().allEntities().firstOrNull { key(it.name) == k }?.kind ?: if (subject.trim().split(' ').size <= 3 && subject.first().isUpperCase()) "person" else "topic"
    }

    /**
     * Nightly: refresh the profile and people summaries from the day, archive
     * what the model says is finished, and fade minor facts nobody mentions.
     */
    suspend fun consolidate(dayStart: Long, dayEnd: Long, daySummaries: List<String>): Boolean {
        val now = System.currentTimeMillis()
        val fresh = db.memory().activeFacts().filter { it.updatedAt in dayStart until dayEnd }
        val active = db.memory().activeFacts().sortedByDescending { it.importance * 10 + if (it.pinned) 100 else 0 }
        val people = db.memory().allEntities().filter { it.lastSeenAt in dayStart until dayEnd }.sortedByDescending { it.mentions }.take(8)
        fade(now)
        if (fresh.isEmpty() && people.isEmpty() && daySummaries.isEmpty()) return true
        if (!ai.ready(Feature.MEMORY)) return false
        val local = ai.llm(Feature.MEMORY).local
        val listed = active.take(if (local) 25 else 120)
        val user = buildString {
            append("Current profile:\n").append(profile().ifBlank { "(empty)" }).append("\n\n")
            append("Today (${Prompts.day(dayStart)}):\n")
            daySummaries.take(if (local) 10 else 40).forEach { append("- ").append(it.take(if (local) 220 else 600)).append('\n') }
            append("\nNew or changed memories today:\n")
            fresh.take(if (local) 15 else 60).forEach { append("- ${it.subject}: ${it.statement}\n") }
            append("\nPeople seen today: ").append(people.joinToString(", ") { it.name }.ifEmpty { "none" }).append("\n\n")
            append("All active memories:\n")
            listed.forEachIndexed { i, f -> append("[${i + 1}] ${f.subject}: ${f.statement}\n") }
        }
        val r = runCatching {
            ai.json(LlmRequest(Feature.MEMORY, "Reviewing memory", Prompts.profileSystem(settings.value.notesLanguage), user, Schemas.profile, maxTokens = 900, deep = true))
        }.onFailure { Log.w(TAG, "consolidate failed", it) }.getOrNull() ?: return false
        r.str("profile")?.takeIf { it.length > 20 }?.let { setProfile(it) }
        for (p in r.objects("people")) {
            val name = p.str("name") ?: continue
            val summary = p.str("summary")?.trim() ?: continue
            val e = people.firstOrNull { key(it.name) == key(name) } ?: continue
            db.memory().updateEntity(e.copy(summary = summary.take(240)))
        }
        r["archive"]?.let { arr ->
            (arr as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }?.forEach { n ->
                val f = listed.getOrNull(n - 1) ?: return@forEach
                if (!f.pinned) db.memory().updateFact(f.copy(status = "archived", archivedReason = "finished or no longer true", validTo = now, updatedAt = now))
            }
        }
        return true
    }

    /** Minor facts fade if nobody mentions them: importance 1 after 14 days, 2 after 30, 3 after 90; open loops twice as fast. */
    private suspend fun fade(now: Long) {
        for (f in db.memory().activeFacts()) {
            if (f.pinned || f.importance >= 4) continue
            var days = when (f.importance) { 1 -> 14; 2 -> 30; else -> 90 }
            if (f.kind == "open_loop") days /= 2
            if (now - f.updatedAt > days * 86_400_000L) {
                db.memory().updateFact(f.copy(status = "archived", archivedReason = "not mentioned for a while", validTo = now, updatedAt = now))
            }
        }
    }

    // User controls
    suspend fun pin(f: Fact, pinned: Boolean) = db.memory().updateFact(f.copy(pinned = pinned, updatedAt = System.currentTimeMillis()))
    suspend fun archive(f: Fact) = db.memory().updateFact(f.copy(status = "archived", archivedReason = "removed by you", validTo = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
    suspend fun restore(f: Fact) = db.memory().updateFact(f.copy(status = "active", archivedReason = null, validTo = null, updatedAt = System.currentTimeMillis()))
    suspend fun delete(f: Fact) { db.memory().deleteFact(f.id); db.vectors().delete("f", f.id) }
    suspend fun addByUser(subject: String, statement: String, kind: String = "fact"): Long {
        val now = System.currentTimeMillis()
        val id = db.memory().insertFact(Fact(subject = subject.ifBlank { "Me" }, statement = statement, kind = kind, importance = 4, pinned = true, origin = "user", validFrom = now, createdAt = now, updatedAt = now))
        embedder.index(listOf(Embedder.Item("f", id, subject, statement)))
        return id
    }

    companion object {
        private const val TAG = "EchoMemory"
        const val PROFILE = "profile"
    }
}
