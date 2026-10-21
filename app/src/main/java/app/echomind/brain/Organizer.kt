package app.echomind.brain

import android.util.Log
import app.echomind.ai.Ai
import app.echomind.ai.LlmRequest
import app.echomind.ai.Prompts
import app.echomind.ai.Schemas
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.Db
import app.echomind.util.Js
import app.echomind.util.int
import app.echomind.util.objects
import app.echomind.util.str
import app.echomind.util.strings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Keeps things tidy: a folder tree and tags for notes, and a circle and
 * organisation for people. New, unfiled things are filed every night; a full
 * re-organise is proposed first and only applied when the owner says so.
 */
class Organizer(private val db: Db, private val ai: Ai, private val settings: SettingsStore) {

    data class NoteMove(val noteId: Long, val title: String, val fromFolder: String?, val folder: String, val fromTags: List<String>, val tags: List<String>)
    data class PersonMove(val id: Long, val name: String, val fromCircle: String?, val circle: String, val fromOrg: String?, val org: String?)
    data class Plan(val notes: List<NoteMove>, val people: List<PersonMove>)

    private fun list(json: String) = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), json) }.getOrDefault(emptyList())
    private fun enc(l: List<String>) = Js.json.encodeToString(ListSerializer(String.serializer()), l)

    /** Proposes folders/tags and circles; [everything] = also re-file what's already filed. */
    suspend fun propose(everything: Boolean): Plan {
        if (!ai.ready(Feature.MEMORY)) return Plan(emptyList(), emptyList())
        val local = ai.llm(Feature.MEMORY).local
        val notes = db.notes().all().filter { !it.archived && (everything || it.folder.isNullOrBlank()) }
        val people = db.memory().allEntities().filter { it.kind == "person" && (everything && !it.pinned || it.circle.isNullOrBlank()) }
        val folders = db.notes().all().mapNotNull { it.folder }.distinct().sorted()
        val noteMoves = ArrayList<NoteMove>()
        val personMoves = ArrayList<PersonMove>()
        val batch = if (local) 12 else 50
        val chunks = maxOf(notes.chunked(batch).size, people.chunked(batch).size)
        for (i in 0 until chunks) {
            val ns = notes.drop(i * batch).take(batch)
            val ps = people.drop(i * batch).take(batch)
            if (ns.isEmpty() && ps.isEmpty()) break
            val user = buildString {
                if (folders.isNotEmpty()) append("Existing folders: ").append(folders.joinToString(" | ")).append("\n\n")
                if (ns.isNotEmpty()) {
                    append("Notes:\n")
                    ns.forEachIndexed { k, n -> append("${k + 1}. ${n.title} — ${n.body.replace('\n', ' ').take(if (local) 120 else 300)}\n") }
                }
                if (ps.isNotEmpty()) {
                    append("\nPeople:\n")
                    ps.forEachIndexed { k, p -> append("${k + 1}. ${p.name}: ${listOfNotNull(p.relationToMe, p.role, p.org, p.summary.takeIf { it.isNotBlank() }).joinToString("; ").take(200)}\n") }
                }
            }
            val r = runCatching {
                ai.json(LlmRequest(Feature.MEMORY, "Organising notes and people", Prompts.organizeSystem(settings.value.notesLanguage), user, Schemas.organize,
                    maxTokens = if (local) 700 else 2_500, deep = true))
            }.onFailure { Log.w("EchoOrganizer", "organise failed", it) }.getOrNull() ?: continue
            for (o in r.objects("notes")) {
                val n = ns.getOrNull((o.int("n") ?: 0) - 1) ?: continue
                val folder = cleanFolder(o.str("folder")) ?: continue
                val tags = (list(n.tags) + o.strings("tags").map { it.trim().lowercase().removePrefix("#").replace(' ', '-') }).filter { it.length in 2..24 }.distinct().take(5)
                if (folder != n.folder || tags != list(n.tags)) noteMoves.add(NoteMove(n.id, n.title, n.folder, folder, list(n.tags), tags))
            }
            for (o in r.objects("people")) {
                val p = ps.getOrNull((o.int("n") ?: 0) - 1) ?: continue
                val circle = o.str("circle")?.takeIf { it in People.CIRCLES } ?: continue
                val org = o.str("org")?.trim()?.takeIf { it.isNotEmpty() } ?: p.org
                if (circle != p.circle || org != p.org) personMoves.add(PersonMove(p.id, p.name, p.circle, circle, p.org, org))
            }
        }
        return Plan(noteMoves, personMoves)
    }

    suspend fun apply(plan: Plan) {
        for (m in plan.notes) db.notes().get(m.noteId)?.let { db.notes().update(it.copy(folder = m.folder, tags = enc(m.tags))) }
        for (m in plan.people) db.memory().entity(m.id)?.let { db.memory().updateEntity(it.copy(circle = m.circle, org = m.org)) }
    }

    /** Nightly: file what isn't filed yet. */
    suspend fun tidyNew() = apply(propose(everything = false))

    companion object {
        /** "work / eazeebox//clients/" → "Work/Eazeebox/Clients", at most three levels. */
        fun cleanFolder(raw: String?): String? = raw?.split('/', '\\', '>')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(3)
            ?.joinToString("/") { s -> s.replaceFirstChar { it.uppercase() }.take(30) }?.takeIf { it.isNotEmpty() }
    }
}
