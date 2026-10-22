package app.echomind.brain

import android.util.Log
import app.echomind.ai.Ai
import app.echomind.ai.LlmRequest
import app.echomind.ai.Prompts
import app.echomind.ai.Schemas
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.Db
import app.echomind.data.EventRow
import app.echomind.util.Js
import app.echomind.util.objects
import app.echomind.util.str
import app.echomind.util.strings
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The AI's hands for anything the quick commands don't cover: "put my
 * insurance notes in Finance", "tag the trip note with goa", "Rahul is
 * Priya's manager", "Ankit works at Tata as a site engineer", "meeting with
 * the CA on Friday at 4". The model picks from a fixed set of actions; none
 * of them deletes anything.
 */
class Actions(
    private val db: Db,
    private val ai: Ai,
    private val settings: SettingsStore,
    private val tasks: Tasks,
    private val notes: Notes,
    private val memory: Memory,
    private val people: People,
    private val agenda: Agenda,
    private val organizer: () -> Organizer,
    private val plans: () -> Plans,
) {
    data class Done(val reply: String, val changes: List<String>)

    suspend fun run(instruction: String): Done? {
        if (!ai.ready(Feature.ASK)) return null
        val now = System.currentTimeMillis()
        val noteTitles = db.notes().all().filter { !it.archived }.sortedByDescending { it.updatedAt }.take(40).map { it.title + (it.folder?.let { f -> " [$f]" } ?: "") }
        val folders = db.notes().all().mapNotNull { it.folder }.distinct()
        val names = db.memory().allEntities().filter { it.kind == "person" }.sortedByDescending { it.lastSeenAt }.take(40).map { it.name }
        val user = buildString {
            append("Now: ${Prompts.day(now)}, ${Prompts.time(now)}\n")
            if (noteTitles.isNotEmpty()) append("Notes: ").append(noteTitles.joinToString(" | ")).append('\n')
            if (folders.isNotEmpty()) append("Folders: ").append(folders.joinToString(" | ")).append('\n')
            if (names.isNotEmpty()) append("People: ").append(names.joinToString(", ")).append('\n')
            append("\nInstruction: ").append(instruction)
        }
        val r = runCatching {
            ai.json(LlmRequest(Feature.ASK, "Doing what you asked", Prompts.actionsSystem(settings.value.notesLanguage), user, Schemas.actions, maxTokens = 600, deep = true))
        }.onFailure { Log.w("EchoActions", "plan failed", it) }.getOrNull() ?: return null
        val actions = r.objects("actions")
        if (actions.isEmpty()) return null
        val changes = ArrayList<String>()
        for (a in actions.take(8)) runCatching { perform(a, now)?.let(changes::add) }.onFailure { Log.w("EchoActions", "action failed", it) }
        if (changes.isEmpty()) return Done(r.str("reply")?.takeIf { it.isNotBlank() } ?: "I couldn't find what to change.", emptyList())
        return Done(r.str("reply")?.takeIf { it.isNotBlank() } ?: "Done.", changes)
    }

    private fun enc(l: List<String>) = Js.json.encodeToString(ListSerializer(String.serializer()), l)
    private fun dec(s: String) = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), s) }.getOrDefault(emptyList())

    private suspend fun perform(a: kotlinx.serialization.json.JsonObject, now: Long): String? {
        val target = a.str("target")?.trim().orEmpty()
        val title = a.str("title")?.trim().orEmpty()
        val text = a.str("text")?.trim().orEmpty()
        val folder = Organizer.cleanFolder(a.str("folder"))
        val tags = a.strings("tags").map { it.trim().lowercase().removePrefix("#").replace(' ', '-') }.filter { it.length in 2..24 }
        val at = When.resolve(a.str("when_iso"), null, now)
        return when (a.str("op")) {
            "move_note" -> {
                val n = notes.find(target) ?: return null
                if (folder == null) return null
                db.notes().update(n.copy(folder = folder)); "Moved “${n.title}” to $folder"
            }
            "tag_note" -> {
                val n = notes.find(target) ?: return null
                val all = (dec(n.tags) + tags).distinct()
                db.notes().update(n.copy(tags = enc(all))); "Tagged “${n.title}” ${tags.joinToString(" ") { "#$it" }}"
            }
            "create_note" -> {
                val id = notes.createByUser(title.ifBlank { "Note" }, text)
                db.notes().get(id)?.let { db.notes().update(it.copy(folder = folder, tags = enc(tags))) }
                "Created note “${title.ifBlank { "Note" }}”${folder?.let { " in $it" } ?: ""}"
            }
            "update_person" -> {
                val e = memory.link("person", target, null, now) ?: return null
                val updated = e.copy(
                    role = a.str("role")?.trim()?.takeIf { it.isNotEmpty() } ?: e.role,
                    org = a.str("org")?.trim()?.takeIf { it.isNotEmpty() } ?: e.org,
                    circle = a.str("circle")?.trim()?.takeIf { it in People.CIRCLES } ?: e.circle,
                    relationToMe = a.str("relation")?.trim()?.takeIf { it.isNotEmpty() } ?: e.relationToMe,
                    pinned = true,
                )
                db.memory().updateEntity(updated)
                if (text.isNotBlank()) memory.addByUser(e.name, text, "relationship")
                "Updated ${e.name}: ${listOfNotNull(updated.relationToMe, updated.role, updated.org?.let { "at $it" }).joinToString(", ")}"
            }
            "relate" -> {
                val other = a.str("other")?.trim().orEmpty()
                val rel = a.str("relation")?.trim().orEmpty()
                if (people.relate(target, rel, other, null, now)) "Linked $target — $rel — $other" else null
            }
            "remember" -> {
                if (text.isBlank()) return null
                memory.addByUser(target.ifBlank { "Me" }, text); "Remembered: $text"
            }
            "add_event" -> {
                val start = at?.at ?: return null
                val end = When.resolve(a.str("end_iso"), null, now)?.at
                val allDay = !at.hasTime
                agenda.save(EventRow(title = title.ifBlank { target.ifBlank { "Event" } }, startAt = start, endAt = end, allDay = allDay, source = "user", status = "confirmed",
                    notes = text.ifBlank { null }, createdAt = now, updatedAt = now))
                "Added to your calendar: ${title.ifBlank { "Event" }}, ${Prompts.day(start).substringBefore(',')}${if (allDay) "" else " at ${Prompts.time(start)}"}"
            }
            "add_task" -> {
                val name = title.ifBlank { text }.ifBlank { return null }
                val id = tasks.addByUser(name, at?.takeIf { it.hasTime }?.at)
                if (at != null && !at.hasTime) db.tasks().get(id)?.let { db.tasks().update(it.copy(dueAt = at.at)) }
                "Added task “$name”${at?.let { " for ${Prompts.day(it.at).substringBefore(',')}" } ?: ""}"
            }
            "complete_task" -> {
                val terms = TextNorm.keyTerms(target).toSet()
                val t = db.tasks().observeOpen().first().maxByOrNull { TextNorm.keyTerms(it.title).count { w -> w in terms } }
                    ?.takeIf { TextNorm.keyTerms(it.title).any { w -> w in terms } } ?: return null
                tasks.complete(t); "Marked “${t.title}” done"
            }
            "set_setting" -> setSetting(a.str("setting")?.trim().orEmpty(), a.str("value")?.trim().orEmpty())
            "plan" -> {
                val kind = a.str("value")?.lowercase()?.takeIf { it in setOf("day", "week", "month") } ?: "day"
                plans().make(kind)?.let { "Made ${if (kind == "day") "today's" else "this $kind's"} plan: ${it.focus}" }
            }
            "organize" -> {
                val plan = organizer().propose(everything = false)
                organizer().apply(plan)
                "Filed ${plan.notes.size} notes and ${plan.people.size} people"
            }
            else -> null
        }
    }

    /** The settings the assistant may change, with checked values. */
    private suspend fun setSetting(key: String, raw: String): String? {
        val v = raw.lowercase()
        fun bool() = v in setOf("on", "true", "yes", "enable", "enabled", "1")
        fun num() = Regex("-?\\d+(\\.\\d+)?").find(v)?.value?.toFloatOrNull()
        val change: ((app.echomind.core.Settings) -> app.echomind.core.Settings)? = when (key) {
            "read_speed" -> num()?.coerceIn(0.5f, 6f)?.let { x -> { s: app.echomind.core.Settings -> s.copy(readSpeed = x) } }
            "speaker_policy" -> v.takeIf { it in setOf("ask", "allow", "never") }?.let { x -> { s -> s.copy(speakerPolicy = x) } }
            "keep_recordings_days" -> (if (v.contains("forever")) -1f else num())?.toInt()?.let { x -> { s -> s.copy(keepRecordingsDays = x) } }
            "ai_quality" -> v.takeIf { it in setOf("fast", "balanced", "best") }?.let { x -> { s -> s.copy(aiQuality = x) } }
            "web_search" -> v.takeIf { it in setOf("off", "ask", "auto") }?.let { x -> { s -> s.copy(webSearch = x) } }
            "songs" -> { s -> s.copy(songs = bool()) }
            "surroundings" -> { s -> s.copy(ambient = bool()) }
            "nudges" -> { s -> s.copy(nudges = bool()) }
            "nudges_per_day" -> num()?.toInt()?.coerceIn(1, 10)?.let { x -> { s -> s.copy(nudgesPerDay = x) } }
            "voice_sensitivity" -> v.takeIf { it in setOf("lenient", "normal", "strict") }?.let { x -> { s -> s.copy(voiceSensitivity = x) } }
            "recognise_people" -> { s -> s.copy(recognisePeople = bool()) }
            "newest_first" -> { s -> s.copy(transcriptNewestFirst = bool()) }
            "read_answers" -> { s -> s.copy(autoReadAnswers = bool()) }
            "notes_language" -> v.takeIf { it in setOf("english", "hindi", "hinglish") }?.let { x -> { s -> s.copy(notesLanguage = x.replaceFirstChar { it.uppercase() }) } }
            "phone_calendar" -> { s -> s.copy(showPhoneCalendar = bool()) }
            "event_headsup" -> { s -> s.copy(eventPrep = bool()) }
            "tasks_to_today" -> { s -> s.copy(undatedToToday = bool()) }
            "review_ai_tasks" -> { s -> s.copy(reviewAiTasks = bool()) }
            "smart_reminders" -> { s -> s.copy(smartReminders = bool()) }
            "morning_plans" -> { s -> s.copy(autoPlans = bool()) }
            "theme" -> v.takeIf { it in setOf("system", "light", "dark") }?.let { x -> { s -> s.copy(theme = x) } }
            else -> null
        }
        change ?: return null
        settings.update(change)
        return "Changed ${key.replace('_', ' ')} to $raw"
    }

    companion object {
        /** What the assistant may change, and how to say the value (shown to the model). */
        val SETTINGS = linkedMapOf(
            "read_speed" to "a number 0.5-6", "speaker_policy" to "ask | allow | never (reading aloud without earphones)",
            "keep_recordings_days" to "days, 0 = don't keep, forever", "ai_quality" to "fast | balanced | best", "web_search" to "off | ask | auto",
            "songs" to "on | off", "surroundings" to "on | off (activity timeline)", "nudges" to "on | off", "nudges_per_day" to "1-10",
            "voice_sensitivity" to "lenient | normal | strict", "recognise_people" to "on | off", "newest_first" to "on | off (transcript order)",
            "read_answers" to "on | off", "notes_language" to "english | hindi | hinglish", "phone_calendar" to "on | off", "event_headsup" to "on | off",
            "tasks_to_today" to "on | off", "review_ai_tasks" to "on | off", "smart_reminders" to "on | off", "morning_plans" to "on | off", "theme" to "system | light | dark",
        )

        /** Cheap check before asking the model: does this sound like an instruction to change something? */
        fun looksLikeInstruction(t: String): Boolean {
            val s = t.lowercase().trim()
            return Regex("^(please )?(move|put|file|tag|label|organi[sz]e|tidy|sort|schedule|book|add|create|make|start|set|link|connect|rename|group|save|remember that|note that|turn|switch|enable|disable|change|keep|read (faster|slower)|plan)\\b").containsMatchIn(s) ||
                Regex("\\b(is my|is our|works (at|for|as)|is the (manager|boss|owner|wife|husband|brother|sister|friend|son|daughter|father|mother) of|reports to|meeting with|appointment)\\b").containsMatchIn(s)
        }
    }
}
