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
import app.echomind.data.Relation
import app.echomind.util.Js
import app.echomind.util.str
import app.echomind.util.strings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Who's who, for someone who finds names hard: what each person does and
 * where, which part of life they belong to, how they connect to each other,
 * how you met, and a short story to read before you see them again.
 *
 * Conversations fill this in as they're understood; the owner can correct
 * anything, and a corrected (pinned) person only ever gets blanks filled.
 */
class People(private val db: Db, private val ai: Ai, private val settings: SettingsStore, private val memory: Memory) {

    data class Info(val name: String, val who: String?, val role: String?, val org: String?, val circle: String?)
    data class Link(val from: String, val type: String, val to: String)

    /** People and connections from one understood conversation. */
    suspend fun absorb(people: List<Info>, links: List<Link>, conversationId: Long, title: String, at: Long) {
        for (p in people) {
            val e = memory.link("person", p.name, p.who, at) ?: continue
            val first = e.mentions <= 1 && e.howMet == null
            db.memory().updateEntity(merge(e, p.role, p.org, p.circle, p.who).let {
                if (first) it.copy(howMet = "First came up in “$title”, ${Prompts.day(at)}") else it
            })
        }
        for (l in links) relate(l.from, l.type, l.to, conversationId, at)
    }

    /** Fills what's blank; updates what changed unless the owner set it themselves. */
    private fun merge(e: EntityRow, role: String?, org: String?, circle: String?, who: String?): EntityRow {
        fun pick(old: String?, new: String?) = new?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in setOf("unknown", "none", "n/a") }
            ?.let { if (old.isNullOrBlank() || !e.pinned) it.take(80) else old } ?: old
        return e.copy(
            role = pick(e.role, role),
            org = pick(e.org, org),
            // "other" never overrides a real circle.
            circle = pick(e.circle, circle?.takeIf { it in CIRCLES && (it != "other" || e.circle == null) }),
            relationToMe = pick(e.relationToMe, who),
        )
    }

    /** "Rahul — manager of — Priya". Organisations become entities of their own. */
    suspend fun relate(from: String, type: String, to: String, conversationId: Long?, at: Long): Boolean {
        val t = type.trim().lowercase().removeSuffix(".").take(40)
        if (t.isEmpty() || from.isBlank() || to.isBlank() || from.equals(to, true)) return false
        val a = memory.link("person", from, null, at) ?: return false
        val orgLike = t in setOf("works at", "works for", "studies at", "member of", "owns", "founded", "runs") || t.endsWith(" at")
        val b = memory.link(if (orgLike) "org" else "person", to, null, at) ?: return false
        if (orgLike && a.org.isNullOrBlank()) db.memory().updateEntity(a.copy(org = b.name))
        val existing = db.relations().all().firstOrNull { it.fromId == a.id && it.toId == b.id && it.type == t }
        if (existing != null) return false
        db.relations().insert(Relation(fromId = a.id, toId = b.id, type = t, sources = conversationId?.let { "[$it]" } ?: "[]", createdAt = at, updatedAt = at))
        return true
    }

    /** People first heard of in the last [days] days, newest first — the ones whose names slip. */
    suspend fun recentlyMet(days: Int = 30): List<EntityRow> {
        val since = System.currentTimeMillis() - days * 86_400_000L
        return db.memory().allEntities().filter { it.kind == "person" && it.firstSeenAt >= since }.sortedByDescending { it.firstSeenAt }
    }

    /** One line to recognise someone by: "Sales lead at Eazeebox · Rahul's wife · met at the site visit". */
    suspend fun clue(e: EntityRow, relations: List<Relation>? = null): String {
        val rels = relations ?: db.relations().all()
        val byId = db.memory().allEntities().associateBy { it.id }
        val parts = ArrayList<String>()
        listOfNotNull(e.role, e.org?.let { "at $it" }).joinToString(" ").takeIf { it.isNotBlank() }?.let(parts::add)
        e.relationToMe?.takeIf { it.isNotBlank() }?.let(parts::add)
        rels.filter { it.fromId == e.id && it.type !in setOf("works at", "works for") }.take(2).forEach { r -> byId[r.toId]?.let { parts.add("${r.type} ${it.name}") } }
        rels.filter { it.toId == e.id }.take(1).forEach { r -> byId[r.fromId]?.let { parts.add("${it.name}'s ${r.type.removeSuffix(" of")}") } }
        if (parts.isEmpty() && e.summary.isNotBlank()) parts.add(e.summary)
        return parts.joinToString(" · ")
    }

    fun details(e: EntityRow): List<String> = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), e.details) }.getOrDefault(emptyList())

    /** Writes (or rewrites) a person's page from everything memory has about them. */
    suspend fun writeStory(id: Long): Boolean {
        val e = db.memory().entity(id) ?: return false
        if (!ai.ready(Feature.MEMORY)) return false
        val facts = db.memory().activeFacts().filter { it.entityId == id || it.subject.equals(e.name, true) }.take(25)
        val name = e.name.lowercase()
        val convs = db.conversations().inRangeOnce(0, Long.MAX_VALUE)
            .filter { it.state == "done" && ((it.people ?: "").lowercase().contains(name) || it.contact?.lowercase()?.contains(name) == true) }
            .sortedByDescending { it.startAt }.take(if (ai.llm(Feature.MEMORY).local) 6 else 20)
        val all = db.memory().allEntities().associateBy { it.id }
        val rels = db.relations().all().filter { it.fromId == id || it.toId == id }
        val user = buildString {
            append("Person: ${e.name}\n")
            listOfNotNull(e.role?.let { "Role: $it" }, e.org?.let { "Organisation: $it" }, e.relationToMe?.let { "To the owner: $it" }, e.howMet, e.summary.takeIf { it.isNotBlank() }?.let { "Known so far: $it" })
                .forEach { append(it).append('\n') }
            if (rels.isNotEmpty()) {
                append("\nConnections:\n")
                rels.forEach { r -> append("- ${all[r.fromId]?.name} ${r.type} ${all[r.toId]?.name}\n") }
            }
            if (facts.isNotEmpty()) { append("\nWhat memory says:\n"); facts.forEach { append("- ${it.statement}\n") } }
            if (convs.isNotEmpty()) {
                append("\nConversations they came up in (newest first):\n")
                convs.forEach { append("- ${Prompts.day(it.startAt)}: ${it.title} — ${it.summary.orEmpty().take(260)}\n") }
            }
        }
        val r = runCatching {
            ai.json(LlmRequest(Feature.MEMORY, "Writing about ${e.name}", Prompts.personSystem(settings.value.notesLanguage), user, Schemas.person, maxTokens = 700, deep = true))
        }.onFailure { Log.w("EchoPeople", "story failed", it) }.getOrNull() ?: return false
        val updated = merge(e, r.str("role"), r.str("org"), r.str("circle"), r.str("relation_to_me")).copy(
            summary = if (e.pinned && e.summary.isNotBlank()) e.summary else r.str("summary")?.trim()?.takeIf { it.isNotEmpty() } ?: e.summary,
            story = r.str("story")?.trim()?.takeIf { it.isNotEmpty() },
            details = Js.json.encodeToString(ListSerializer(String.serializer()), r.strings("remember").take(4)),
            storyAt = System.currentTimeMillis(),
            storyMentions = e.mentions,
        )
        db.memory().updateEntity(updated)
        return true
    }

    /** Nightly: refreshes the pages of people who came up again since their story was written. */
    suspend fun refreshStories(limit: Int) {
        val due = db.memory().allEntities().filter { it.kind == "person" && it.mentions >= 2 && it.mentions > it.storyMentions }
            .sortedByDescending { it.lastSeenAt }.take(limit)
        for (e in due) writeStory(e.id)
    }

    /** Merges [dup] into [keep]: aliases, facts and connections move over. */
    suspend fun mergeInto(keep: EntityRow, dup: EntityRow) {
        val aliases = (runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), keep.aliases) }.getOrDefault(emptyList()) + dup.name).distinct()
        db.memory().updateEntity(keep.copy(aliases = Js.json.encodeToString(ListSerializer(String.serializer()), aliases), mentions = keep.mentions + dup.mentions,
            firstSeenAt = minOf(keep.firstSeenAt, dup.firstSeenAt), lastSeenAt = maxOf(keep.lastSeenAt, dup.lastSeenAt)))
        db.memory().activeFacts().filter { it.entityId == dup.id }.forEach { db.memory().updateFact(it.copy(entityId = keep.id)) }
        db.relations().all().filter { it.fromId == dup.id || it.toId == dup.id }.forEach { r ->
            db.relations().delete(r.id)
            val moved = r.copy(id = 0, fromId = if (r.fromId == dup.id) keep.id else r.fromId, toId = if (r.toId == dup.id) keep.id else r.toId)
            if (moved.fromId != moved.toId) db.relations().insert(moved)
        }
        db.memory().deleteEntity(dup.id)
    }

    suspend fun delete(e: EntityRow) {
        db.relations().deleteFor(e.id)
        db.memory().deleteEntity(e.id)
    }

    companion object {
        val CIRCLES = listOf("family", "work", "friends", "neighbourhood", "services", "other")
        fun circleLabel(c: String?) = when (c) {
            "family" -> "Family"; "work" -> "Work"; "friends" -> "Friends"; "neighbourhood" -> "Neighbourhood"; "services" -> "Services & shops"; else -> "Others"
        }
    }
}
