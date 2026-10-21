package app.echomind.brain

import android.util.Log
import app.echomind.ai.Ai
import app.echomind.ai.LlmRequest
import app.echomind.ai.Prompts
import app.echomind.ai.Schemas
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.Db
import app.echomind.data.Note
import app.echomind.data.NoteRevision
import app.echomind.util.Js
import app.echomind.util.objects
import app.echomind.util.str
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Living notes: one note per ongoing topic (a project, a trip, a health
 * issue), kept current as conversations touch it. The model proposes small
 * edits — add / update / remove a bullet — which are applied here, recorded
 * as a revision with the reason and the source lines, and never touch text
 * the user wrote or a note they locked.
 */
class Notes(private val db: Db, private val ai: Ai, private val settings: SettingsStore, private val embedder: Embedder) {

    data class Update(val topic: String, val points: List<String>, val sourceIds: List<Long>, val folder: String? = null, val tags: List<String> = emptyList())

    suspend fun titles(): List<String> = db.notes().all().sortedByDescending { it.updatedAt }.take(30).map { it.title }

    private fun key(s: String) = TextNorm.keyTerms(s).toSet()

    suspend fun find(topic: String): Note? {
        val k = key(topic)
        if (k.isEmpty()) return null
        return db.notes().all().maxByOrNull { n ->
            val nk = key(n.title)
            if (nk.isEmpty()) 0.0 else nk.intersect(k).size.toDouble() / minOf(nk.size, k.size)
        }?.takeIf { n -> val nk = key(n.title); nk.isNotEmpty() && nk.intersect(k).size.toDouble() / minOf(nk.size, k.size) >= 0.6 }
    }

    /** Applies a conversation's note updates; returns the notes touched. */
    suspend fun apply(updates: List<Update>, conversationId: Long, conversationTitle: String, at: Long): List<Long> {
        val touched = ArrayList<Long>()
        for (u in updates) {
            val points = u.points.map { it.trim() }.filter { it.length > 3 }
            if (points.isEmpty()) continue
            val existing = find(u.topic)
            if (existing == null) {
                // A new topic needs at least two things worth noting, or it is just a stray fact.
                if (points.size < 2) continue
                val body = points.joinToString("\n") { "- $it" }
                val note = Note(title = u.topic.trim().take(80), body = body, createdAt = at, updatedAt = at, folder = Organizer.cleanFolder(u.folder),
                    tags = Js.json.encodeToString(ListSerializer(String.serializer()), u.tags.map { it.trim().lowercase().removePrefix("#").replace(' ', '-') }.filter { it.length in 2..24 }.distinct().take(4)))
                val id = db.notes().insert(note)
                db.notes().insertRevision(NoteRevision(noteId = id, version = 1, createdAt = at, reason = "Started from “$conversationTitle”",
                    conversationId = conversationId, sources = ids(u.sourceIds), before = "", after = body))
                embedder.index(listOf(Embedder.Item("n", id, note.title, body)))
                touched.add(id)
            } else if (!existing.locked) {
                if (patch(existing, points, u.sourceIds, conversationId, conversationTitle, at)) touched.add(existing.id)
            }
        }
        return touched
    }

    private suspend fun patch(note: Note, points: List<String>, sourceIds: List<Long>, conversationId: Long, conversationTitle: String, at: Long): Boolean {
        val before = note.body
        var reason = "Updated from “$conversationTitle”"
        val after: String
        if (ai.ready(Feature.NOTES)) {
            val user = "Note: ${note.title}\n\nCurrent note:\n${before.take(if (ai.llm(Feature.NOTES).local) 2_500 else 12_000)}\n\nNew information (${Prompts.day(at)}, from “$conversationTitle”):\n" +
                points.joinToString("\n") { "- $it" }
            val r = runCatching {
                ai.json(LlmRequest(Feature.NOTES, "Updating note “${note.title}”", Prompts.notePatchSystem(settings.value.notesLanguage), user, Schemas.notePatch, maxTokens = 500))
            }.onFailure { Log.w("EchoNotes", "patch failed", it) }.getOrNull()
            if (r != null) {
                r.str("reason")?.takeIf { it.isNotBlank() }?.let { reason = it.trim() }
                after = NotePatch.apply(before, r.objects("edits").map { NotePatch.Edit(it.str("op").orEmpty(), it.str("section").orEmpty(), it.str("find").orEmpty(), it.str("text").orEmpty()) }, userLines(note))
            } else {
                after = appendPoints(before, points, at)
            }
        } else {
            after = appendPoints(before, points, at)
        }
        if (after.trim() == before.trim()) return false
        val version = note.version + 1
        db.notes().update(note.copy(body = after, version = version, updatedAt = at))
        db.notes().insertRevision(NoteRevision(noteId = note.id, version = version, createdAt = at, reason = reason,
            conversationId = conversationId, sources = ids(sourceIds), before = before, after = after))
        embedder.index(listOf(Embedder.Item("n", note.id, note.title, after)))
        return true
    }

    private fun appendPoints(body: String, points: List<String>, at: Long): String =
        body.trimEnd() + "\n\n## ${Prompts.day(at)}\n" + points.joinToString("\n") { "- $it" }

    private fun userLines(n: Note): Set<String> = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), n.userLines) }.getOrDefault(emptyList()).map { it.trim() }.toSet()
    private fun ids(list: List<Long>) = Js.json.encodeToString(ListSerializer(Long.serializer()), list)

    /** The user edited a note: their new lines become protected, and a revision is recorded. */
    suspend fun saveByUser(note: Note, newTitle: String, newBody: String) {
        val now = System.currentTimeMillis()
        val old = note.body.lines().map { it.trim() }.toSet()
        val added = newBody.lines().map { it.trim() }.filter { it.isNotEmpty() && it !in old }
        val protected = (userLines(note) + added).toList()
        val version = note.version + 1
        db.notes().update(note.copy(title = newTitle.trim().ifEmpty { note.title }, body = newBody, version = version, updatedAt = now,
            userLines = Js.json.encodeToString(ListSerializer(String.serializer()), protected)))
        db.notes().insertRevision(NoteRevision(noteId = note.id, version = version, createdAt = now, reason = "Edited by you", before = note.body, after = newBody, by = "user"))
    }

    suspend fun createByUser(title: String, body: String): Long {
        val now = System.currentTimeMillis()
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val id = db.notes().insert(Note(title = title.ifBlank { "Note" }, body = body, origin = "user", createdAt = now, updatedAt = now,
            userLines = Js.json.encodeToString(ListSerializer(String.serializer()), lines)))
        db.notes().insertRevision(NoteRevision(noteId = id, version = 1, createdAt = now, reason = "Written by you", before = "", after = body, by = "user"))
        embedder.index(listOf(Embedder.Item("n", id, title, body)))
        return id
    }
}

/** Applies bullet-level edits to a markdown note; lines the user wrote are never changed or removed. Pure, so it is unit-tested. */
object NotePatch {
    private fun key(s: String) = TextNorm.keyTerms(s).toSet()

    data class Edit(val op: String, val section: String, val find: String, val text: String)

    /** Applies bullet edits to a markdown note. Lines the user wrote are never changed or removed. */
    fun apply(body: String, edits: List<Edit>, protected: Set<String>): String {
        val lines = body.lines().toMutableList()
        fun locate(find: String): Int {
            val terms = key(find)
            if (terms.isEmpty()) return -1
            var best = -1
            var bestScore = 0.0
            lines.forEachIndexed { i, l ->
                if (!l.trimStart().startsWith("-")) return@forEachIndexed
                val lk = key(l)
                if (lk.isEmpty()) return@forEachIndexed
                val s = lk.intersect(terms).size.toDouble() / terms.size
                if (s > bestScore) { bestScore = s; best = i }
            }
            return if (bestScore >= 0.5) best else -1
        }
        for (e in edits) {
            val text = e.text.trim().removePrefix("-").trim()
            when (e.op) {
                "update" -> {
                    val i = locate(e.find)
                    if (i >= 0 && lines[i].trim() !in protected && text.isNotEmpty()) lines[i] = "- $text"
                    else if (text.isNotEmpty()) addUnder(lines, e.section, text)
                }
                "remove" -> {
                    val i = locate(e.find)
                    if (i >= 0 && lines[i].trim() !in protected) lines.removeAt(i)
                }
                else -> if (text.isNotEmpty() && locate(text) < 0) addUnder(lines, e.section, text)
            }
        }
        return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun addUnder(lines: MutableList<String>, section: String, text: String) {
        val heading = section.trim().removePrefix("#").trim()
        if (heading.isEmpty()) { lines.add("- $text"); return }
        val h = lines.indexOfFirst { it.startsWith("#") && it.trimStart('#', ' ').equals(heading, ignoreCase = true) }
        if (h < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("## $heading"); lines.add("- $text")
            return
        }
        var end = h + 1
        while (end < lines.size && !lines[end].startsWith("#")) end++
        while (end > h + 1 && lines[end - 1].isBlank()) end--
        lines.add(end, "- $text")
    }
}
