package app.echomind.brain

import app.echomind.ai.Prompts
import app.echomind.core.SettingsStore
import app.echomind.data.Conversation
import app.echomind.data.Db
import app.echomind.data.Note
import app.echomind.data.TaskRow
import app.echomind.tts.Speakable
import app.echomind.util.Js
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * What the assistant says out loud. Each script is a list of short parts —
 * the player highlights the part being read and you can skip between them.
 * Written to be heard, not read: counts first, then the items, no symbols.
 */
class Briefing(private val db: Db, private val settings: SettingsStore, private val agenda: () -> Agenda, private val plans: () -> Plans) {
    data class Script(val title: String, val parts: List<String>)

    private val zone get() = ZoneId.systemDefault()
    private fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun name() = settings.value.userName.substringBefore(' ').ifBlank { null }

    private fun spokenTask(t: TaskRow, withTime: Boolean = true): String {
        val time = (t.remindAt ?: t.dueAt)?.takeIf { withTime }?.let { at ->
            val lt = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
            if (lt.hour == 9 && lt.minute == 0 && t.remindAt == null) "" else " at ${Prompts.time(at)}"
        }.orEmpty()
        return t.title.trimEnd('.') + time + "."
    }

    private fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    /** The morning brief: today's plan, what slipped, what's waiting. */
    suspend fun morning(): Script {
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val end = start(today.plusDays(1))
        val hour = LocalTime.now().hour
        val greet = when { hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }
        val open = db.tasks().observeOpen().first()
        val overdue = open.filter { it.dueAt != null && it.dueAt < start(today) }
        val due = open.filter { it.dueAt != null && it.dueAt in start(today) until end }.sortedBy { it.remindAt ?: it.dueAt }
        val reminders = db.tasks().upcomingReminders(now).filter { (it.remindAt ?: 0) < end && it !in due }
        val review = db.tasks().suggestedCount().first()
        val yesterday = db.days().get(today.minusDays(1).toString())
        val carry = yesterday?.tomorrow?.let { runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()

        val events = runCatching { agenda().range(start(today), end) }.getOrDefault(emptyList()).filter { it.status == "confirmed" && (it.allDay || it.startAt >= now - 30 * 60_000L) }
        val parts = ArrayList<String>()
        parts.add("$greet${name()?.let { ", $it" } ?: ""}. It's ${Prompts.day(now)}.")
        if (events.isNotEmpty()) {
            parts.add("On your calendar: ${count(events.size, "event")}.")
            events.take(6).forEach { parts.add(if (it.allDay) "${it.title}, all day." else "${it.title} at ${Prompts.time(it.startAt)}${it.location?.let { l -> ", at $l" } ?: ""}.") }
        }
        if (due.isEmpty() && overdue.isEmpty() && reminders.isEmpty()) { if (events.isEmpty()) parts.add("Nothing is scheduled for today.") }
        else {
            if (due.isNotEmpty()) {
                parts.add("You have ${count(due.size, "thing")} planned today.")
                due.take(8).forEach { parts.add(spokenTask(it)) }
            }
            reminders.take(5).forEach { parts.add("Reminder: " + spokenTask(it)) }
            if (overdue.isNotEmpty()) {
                parts.add("${count(overdue.size, "task")} ${if (overdue.size == 1) "is" else "are"} overdue: ${list(overdue.take(4).map { it.title.trimEnd('.') })}.")
            }
        }
        if (carry.isNotEmpty()) parts.add("From yesterday, you meant to: ${list(carry.take(3))}.")
        runCatching { plans().get("day") }.getOrNull()?.let { p ->
            if (p.focus.isNotBlank()) parts.add("Today's focus: ${p.focus.trimEnd('.')}.")
            if (p.missing.isNotEmpty()) parts.add("You might be missing: ${list(p.missing.take(3).map { it.title.trimEnd('.') })}.")
        }
        if (review > 0) parts.add("${count(review, "suggestion")} from your conversations ${if (review == 1) "is" else "are"} waiting for your OK.")
        return Script("Your brief", parts)
    }

    /** What's still on — for "what's pending?". */
    suspend fun pending(): Script {
        val today = LocalDate.now()
        val end = start(today.plusDays(1))
        val open = db.tasks().observeOpen().first()
        val overdue = open.filter { it.dueAt != null && it.dueAt < start(today) }
        val due = open.filter { it.dueAt != null && it.dueAt in start(today) until end }.sortedBy { it.remindAt ?: it.dueAt }
        val later = open.filter { it.dueAt == null }.sortedBy { it.priority }
        val parts = ArrayList<String>()
        if (open.isEmpty()) return Script("Pending", listOf("Nothing is pending. Your list is clear."))
        parts.add("You have ${count(open.size, "open task")}.")
        if (overdue.isNotEmpty()) { parts.add("Overdue:"); overdue.take(5).forEach { parts.add(spokenTask(it, withTime = false)) } }
        if (due.isNotEmpty()) { parts.add("Due today:"); due.take(8).forEach { parts.add(spokenTask(it)) } }
        if (later.isNotEmpty()) { parts.add("Without a date:"); later.take(5).forEach { parts.add(spokenTask(it, withTime = false)) } }
        return Script("Pending tasks", parts)
    }

    suspend fun reminders(): Script {
        val list = db.tasks().upcomingReminders(System.currentTimeMillis()).take(10)
        if (list.isEmpty()) return Script("Reminders", listOf("You have no upcoming reminders."))
        return Script("Reminders", listOf("You have ${count(list.size, "upcoming reminder")}.") + list.map { "${dayWord(it.remindAt!!)} at ${Prompts.time(it.remindAt)}: ${it.title.trimEnd('.')}." })
    }

    /** The day's journal, or its conversations if the journal isn't written yet. */
    suspend fun day(d: LocalDate = LocalDate.now()): Script {
        val e = db.days().get(d.toString())
        val label = if (d == LocalDate.now()) "today" else dayWord(start(d))
        if (e?.diary != null) {
            val ends = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), e.looseEnds) }.getOrDefault(emptyList())
            val tomorrow = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), e.tomorrow) }.getOrDefault(emptyList())
            return Script("Summary of $label", listOfNotNull(e.headline?.let { "$it." }) + Speakable.sentences(e.diary) +
                (if (ends.isNotEmpty()) listOf("Still open: ${list(ends.take(4))}.") else emptyList()) +
                (if (tomorrow.isNotEmpty()) listOf("For tomorrow: ${list(tomorrow.take(4))}.") else emptyList()))
        }
        val convs = db.conversations().inRangeOnce(start(d), start(d.plusDays(1))).filter { it.state == "done" && !it.ignored }
        if (convs.isEmpty()) return Script("Summary of $label", listOf("There are no conversations to summarise for $label yet."))
        return Script("Summary of $label", listOf("${count(convs.size, "conversation")} $label.") + convs.map { "At ${Prompts.time(it.startAt)}, ${it.title}. ${it.summary.orEmpty()}" })
    }

    fun note(n: Note): Script = Script(n.title, listOf(n.title + ".") + n.body.lines().map { it.trim() }.filter { it.isNotEmpty() && it != "---" })

    fun conversation(c: Conversation): Script {
        val points = c.keyPoints?.let { runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()
        return Script(c.title ?: "Conversation", listOfNotNull(c.title?.let { "$it, at ${Prompts.time(c.startAt)}." }, c.summary) + points)
    }

    fun tasks(title: String, list: List<TaskRow>): Script =
        Script(title, if (list.isEmpty()) listOf("Nothing here.") else listOf("${count(list.size, "task")}.") + list.map { spokenTask(it) })

    private fun count(n: Int, word: String) = when (n) { 1 -> "one $word"; else -> "$n ${word}s" }
    private fun dayWord(at: Long): String {
        val d = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        return when (d) {
            LocalDate.now() -> "Today"
            LocalDate.now().plusDays(1) -> "Tomorrow"
            LocalDate.now().minusDays(1) -> "Yesterday"
            else -> Prompts.day(at).substringBefore(' ')
        }
    }
}
