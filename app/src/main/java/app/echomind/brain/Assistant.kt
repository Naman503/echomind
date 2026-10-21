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
) {
    data class Result(val reply: String, val script: Briefing.Script? = null, val taskId: Long? = null)

    private fun has(t: String, vararg words: String) = words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }

    /** Handles [input] if it is a command; null means "this is a question — ask memory". */
    suspend fun handle(input: String, speak: Boolean): Result? {
        val raw = input.trim()
        val t = raw.lowercase().replace(Regex("[?!.]+$"), "").trim()
        val r = route(raw, t) ?: return null
        if (speak) {
            val s = r.script ?: Briefing.Script("EchoMind", listOf(r.reply))
            speaker.speak(s.title, s.parts)
        }
        return r
    }

    private suspend fun route(raw: String, t: String): Result? {
        // Reading control
        if (t in setOf("stop", "stop reading", "chup", "bas", "pause", "quiet")) { speaker.stop(); return Result("Stopped.") }

        // Briefs and summaries
        if (has(t, "brief me", "morning brief", "my day", "what's my day", "whats my day", "plan for today", "today's plan", "aaj kya hai", "aaj ka plan", "daily brief"))
            return briefing.morning().let { Result(it.parts.joinToString(" "), it) }
        if (has(t, "summary", "summarise", "summarize", "recap", "journal", "diary")) {
            val day = when { has(t, "yesterday", "kal") -> java.time.LocalDate.now().minusDays(1); else -> java.time.LocalDate.now() }
            return briefing.day(day).let { Result(it.parts.joinToString(" "), it) }
        }
        if (has(t, "reminders", "my reminders") && !t.startsWith("remind me"))
            return briefing.reminders().let { Result(it.parts.joinToString(" "), it) }
        if (has(t, "pending", "what's left", "whats left", "what do i have", "to do list", "todo list", "my tasks", "open tasks", "kya baaki", "baaki kya", "what should i do"))
            return briefing.pending().let { Result(it.parts.joinToString(" "), it) }

        // Read a note
        Regex("^(read|open|play|sunao|padho)( me)?( my| the)?( note| notes)?( about| on| called| for)? (.+?)( note)?$").find(t)?.let { m ->
            val q = m.groupValues[6].trim()
            if (q.isNotBlank() && q !in setOf("tasks", "pending", "reminders", "summary", "brief")) {
                val n = findNote(q) ?: return Result("I couldn't find a note about \"$q\".")
                return briefing.note(n).let { Result("Reading “${n.title}”.", it) }
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
            return Result("Added to “${n.title}”.")
        }
        Regex("^(note|note down|note that|make a note|likh lo|note kar(o|lo)?)[:,]? (.+)$").find(t)?.let { m ->
            val text = raw.takeLast(m.groupValues[3].length)
            val n = notes.find("Voice notes") ?: db.notes().get(notes.createByUser("Voice notes", ""))!!
            appendToNote(n, text)
            return Result("Noted in “Voice notes”: $text")
        }

        // Add a task / reminder
        if (Regex("^(remind me|reminder|yaad dila|add( a)? (task|todo|reminder)|todo|task:|add)\\b").containsMatchIn(t)) {
            val body = raw.replace(Regex("^(add( a)? (task|todo|reminder)( to)?|todo:?|task:)\\s*", RegexOption.IGNORE_CASE), "")
            val id = tasks.addQuick(body) ?: return null
            val task = db.tasks().get(id) ?: return null
            val whenText = (task.remindAt ?: task.dueAt)?.let { " for ${Prompts.day(it).substringBefore(',')} at ${Prompts.time(it)}" }.orEmpty()
            return Result(if (task.remindAt != null) "I'll remind you to ${task.title.replaceFirstChar { it.lowercase() }}$whenText." else "Added “${task.title}”$whenText.", taskId = id)
        }
        return null
    }

    private suspend fun completeTask(q: String): Result {
        val target = findTask(q) ?: return Result("I couldn't find an open task like \"$q\".")
        tasks.complete(target)
        return Result("Marked “${target.title}” done.", taskId = target.id)
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
