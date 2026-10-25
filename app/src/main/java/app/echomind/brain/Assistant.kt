package app.echomind.brain

import app.echomind.ai.Prompts
import app.echomind.data.Db
import app.echomind.data.Note
import app.echomind.data.TaskRow
import app.echomind.tts.Speaker
import kotlinx.coroutines.flow.first

/**
 * The assistant's hands: spoken or typed commands that *do* things — read my
 * day, what's pending, add a task, remind me, mark done, note this down. A
 * fast rule-based path handles the common ones in English and Hinglish with
 * no AI at all; anything else falls through to Ask, which answers from memory.
 */
class Assistant(
    private val db: Db,
    private val tasks: Tasks,
    private val notes: Notes,
    private val briefing: Briefing,
    private val speaker: Speaker,
    private val actions: () -> Actions,
    private val agenda: () -> Agenda,
) {
    /**
     * [kind]: which command it was, from a fixed list (for anonymous usage; never the words):
     * stop_reading, brief, summary, week, calendar, reminders, pending, read_note, complete_task,
     * append_note, note_down, add_task, add_reminder, action. [ok]: false when nothing matched (no such note/task).
     */
    data class Result(val reply: String, val script: Briefing.Script? = null, val taskId: Long? = null, val changes: List<String> = emptyList(), val kind: String = "other", val ok: Boolean = true)

    private fun has(t: String, vararg words: String) = words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }

    /** Handles [input] if it is a command; null means "this is a question — ask memory". */
    suspend fun handle(input: String, speak: Boolean): Result? {
        val raw = input.trim()
        val t = raw.lowercase().replace(Regex("[?!.]+$"), "").trim()
        val r = route(raw, t) ?: return null
        // Anonymous usage: the command's fixed category and whether it found its target.
        app.echomind.telemetry.Usage.count("command_used", mapOf("cmd" to r.kind, "ok" to r.ok, "read_aloud" to speak))
        if (speak) {
            val s = r.script ?: Briefing.Script("EchoMind", listOf(r.reply))
            speaker.speak(s.title, s.parts)
        }
        return r
    }

    private suspend fun route(raw: String, t: String): Result? {
        // Reading control
        if (t in setOf("stop", "stop reading", "chup", "bas", "pause", "quiet")) { speaker.stop(); return Result("Stopped.", kind = "stop_reading") }

        // Briefs and summaries
        if (has(t, "brief me", "morning brief", "my day", "what's my day", "whats my day", "plan for today", "today's plan", "aaj kya hai", "aaj ka plan", "daily brief"))
            return briefing.morning().let { Result(it.parts.joinToString(" "), it, kind = "brief") }
        if (has(t, "summary", "summarise", "summarize", "recap", "journal", "diary")) {
            val day = when { has(t, "yesterday", "kal") -> java.time.LocalDate.now().minusDays(1); else -> java.time.LocalDate.now() }
            return briefing.day(day).let { Result(it.parts.joinToString(" "), it, kind = "summary") }
        }
        if (has(t, "week ahead", "my week", "this week", "next week", "is hafte", "iss hafte"))
            return agenda().weekAhead().let { Result(it.parts.joinToString(" "), it, kind = "week") }
        if (has(t, "my calendar", "calendar", "meetings today", "any meetings", "my meetings", "schedule today")) {
            val z = java.time.ZoneId.systemDefault()
            val d = if (has(t, "tomorrow", "kal")) java.time.LocalDate.now().plusDays(1) else java.time.LocalDate.now()
            val ev = agenda().range(d.atStartOfDay(z).toInstant().toEpochMilli(), d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()).filter { it.status == "confirmed" }
            val day = if (d == java.time.LocalDate.now()) "today" else "tomorrow"
            val parts = if (ev.isEmpty()) listOf("Nothing on your calendar $day.") else
                listOf("${ev.size} on your calendar $day.") + ev.map { if (it.allDay) "${it.title}, all day." else "${it.title} at ${Prompts.time(it.startAt)}." }
            return Result(parts.joinToString(" "), Briefing.Script("Calendar", parts), kind = "calendar")
        }
        if (has(t, "reminders", "my reminders") && !t.startsWith("remind me"))
            return briefing.reminders().let { Result(it.parts.joinToString(" "), it, kind = "reminders") }
        if (has(t, "pending", "what's left", "whats left", "what do i have", "to do list", "todo list", "my tasks", "open tasks", "kya baaki", "baaki kya", "what should i do"))
            return briefing.pending().let { Result(it.parts.joinToString(" "), it, kind = "pending") }

        // Read a note
        Regex("^(read|open|play|sunao|padho)( me)?( my| the)?( note| notes)?( about| on| called| for)? (.+?)( note)?$").find(t)?.let { m ->
            val q = m.groupValues[6].trim()
            if (q.isNotBlank() && q !in setOf("tasks", "pending", "reminders", "summary", "brief")) {
                val n = findNote(q) ?: return Result("I couldn't find a note about \"$q\".", kind = "read_note", ok = false)
                return briefing.note(n).let { Result("Reading “${n.title}”.", it, kind = "read_note") }
            }
        }

        // Complete a task
        Regex("^(mark|complete|tick off|finish|cross off)( the)?( task)? (.+?)( as)?( done| complete| completed)?$").find(t)?.let { m ->
            return completeTask(m.groupValues[4])
        }
        Regex("^(i )?(finished|completed|did) (.+)$").find(t)?.let { m -> return completeTask(m.groupValues[3]) }
        Regex("^(.+?) (done ho gaya|ho gaya|is done)$").find(t)?.let { m -> return completeTask(m.groupValues[1]) }

        // Add to a note
        Regex("^(add|append) (.+?) to (my )?(note )?(.+)$").find(raw.lowercase())?.let { m ->
            val n = findNote(m.groupValues[5]) ?: return null
            val text = raw.substring(raw.lowercase().indexOf(m.groupValues[2]), raw.lowercase().indexOf(m.groupValues[2]) + m.groupValues[2].length)
            appendToNote(n, text)
            return Result("Added to “${n.title}”.", kind = "append_note")
        }
        Regex("^(note|note down|note that|make a note|likh lo|note kar(o|lo)?)[:,]? (.+)$").find(t)?.let { m ->
            val text = raw.takeLast(m.groupValues[3].length)
            val n = notes.find("Voice notes") ?: db.notes().get(notes.createByUser("Voice notes", ""))!!
            appendToNote(n, text)
            return Result("Noted in “Voice notes”: $text", kind = "note_down")
        }

        // Add a task / reminder
        // "add … to my calendar / folder / tags" is not a task; the action planner below handles it.
        val elsewhere = has(t, "calendar", "meeting", "appointment", "event", "folder", "tag", "tags", "note")
        if (Regex("^(remind me|reminder|yaad dila|add( a)? (task|todo|reminder)|todo|task:|add)\\b").containsMatchIn(t) && !(elsewhere && !t.startsWith("remind"))) {
            val body = raw.replace(Regex("^(add( a)? (task|todo|reminder)( to)?|todo:?|task:)\\s*", RegexOption.IGNORE_CASE), "")
            val id = tasks.addQuick(body, via = "command") ?: return null
            val task = db.tasks().get(id) ?: return null
            val whenText = (task.remindAt ?: task.dueAt)?.let { " for ${Prompts.day(it).substringBefore(',')} at ${Prompts.time(it)}" }.orEmpty()
            return Result(if (task.remindAt != null) "I'll remind you to ${task.title.replaceFirstChar { it.lowercase() }}$whenText." else "Added “${task.title}”$whenText.", taskId = id,
                kind = if (task.remindAt != null) "add_reminder" else "add_task")
        }

        // Anything else that sounds like "change something": the AI picks from a fixed set of safe actions.
        if (Actions.looksLikeInstruction(raw)) {
            val done = actions().run(raw) ?: return null
            val reply = if (done.changes.isEmpty()) done.reply else done.reply + "\n" + done.changes.joinToString("\n") { "• $it" }
            return Result(reply, Briefing.Script("EchoMind", listOf(done.reply)), changes = done.changes, kind = "action", ok = done.changes.isNotEmpty())
        }
        return null
    }

    private suspend fun completeTask(q: String): Result {
        val target = findTask(q) ?: return Result("I couldn't find an open task like \"$q\".", kind = "complete_task", ok = false)
        tasks.complete(target)
        return Result("Marked “${target.title}” done.", taskId = target.id, kind = "complete_task")
    }

    private suspend fun findTask(q: String): TaskRow? {
        val terms = TextNorm.keyTerms(q).toSet()
        if (terms.isEmpty()) return null
        return db.tasks().observeOpen().first().map { it to TextNorm.keyTerms(it.title).toSet() }
            .map { (task, k) -> task to if (k.isEmpty()) 0.0 else k.intersect(terms).size.toDouble() / minOf(k.size, terms.size) }
            .filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first
    }

    private suspend fun findNote(q: String): Note? = notes.find(q) ?: run {
        val terms = TextNorm.keyTerms(q).toSet()
        db.notes().all().filter { !it.archived }.maxByOrNull { n -> TextNorm.keyTerms(n.title + " " + n.body.take(400)).count { it in terms } }
            ?.takeIf { n -> TextNorm.keyTerms(n.title + " " + n.body.take(400)).count { it in terms } > 0 }
    }

    private suspend fun appendToNote(n: Note, text: String) {
        val body = (n.body.trimEnd() + "\n- " + text.trim()).trim()
        notes.saveByUser(n, n.title, body)
    }
}
