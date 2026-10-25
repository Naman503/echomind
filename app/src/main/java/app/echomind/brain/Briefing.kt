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
    data class Script(val title: String, val parts: List<String>) {
        /** Drops any part that repeats one already in the script (headings like "Overdue:" stay). */
        fun tidy(): Script {
            val out = ArrayList<String>()
            for (p in parts) {
                val t = p.trim()
                if (t.isEmpty()) continue
                if (t.endsWith(":") || Dedup.keys(t).size < 2 || out.none { Dedup.same(it, t, 0.75) }) out.add(t)
            }
            return copy(parts = out)
        }
    }

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
        val open = Dedup.distinct(db.tasks().observeOpen().first()) { it.title }
        val overdue = open.filter { it.dueAt != null && it.dueAt < start(today) }
        val due = open.filter { it.dueAt != null && it.dueAt in start(today) until end }.sortedBy { it.remindAt ?: it.dueAt }
        val reminders = Dedup.without(db.tasks().upcomingReminders(now).filter { (it.remindAt ?: 0) < end && it.id !in due.map { d -> d.id } }, due.map { it.title }) { it.title }
        val review = db.tasks().suggestedCount().first()
        val yesterday = db.days().get(today.minusDays(1).toString())
        val carry = yesterday?.tomorrow?.let { runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()

        val events = runCatching { agenda().range(start(today), end) }.getOrDefault(emptyList()).filter { it.status == "confirmed" && (it.allDay || it.startAt >= now - 30 * 60_000L) }
        val parts = ArrayList<String>()
        parts.add("$greet${name()?.let { ", $it" } ?: ""}. It's ${Prompts.day(now)}.")
        runCatching { app.echomind.App.graph.places.morningWeather() }.getOrNull()?.let { parts.add("Weather: ${it.replace("°C", " degrees")}.") }
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
        // Each list leaves out what an earlier part already said.
        val said = (due + reminders + overdue).map { it.title }
        val carryNew = Dedup.without(Dedup.strings(carry), said) { it }
        if (carryNew.isNotEmpty()) parts.add("From yesterday, you meant to: ${list(carryNew.take(3))}.")
        runCatching { plans().get("day") }.getOrNull()?.let { p ->
            if (p.focus.isNotBlank() && (said + carryNew).none { Dedup.same(it, p.focus) }) parts.add("Today's focus: ${p.focus.trimEnd('.')}.")
            val missing = Dedup.without(p.missing.map { it.title.trimEnd('.') }, said + carryNew + p.focus) { it }
            if (missing.isNotEmpty()) parts.add("You might be missing: ${list(missing.take(3))}.")
        }
        if (review > 0) parts.add("${count(review, "suggestion")} from your conversations ${if (review == 1) "is" else "are"} waiting for your OK.")
        return Script("Your brief", parts).tidy()
    }

    /** What's still on — for "what's pending?". */
    suspend fun pending(): Script {
        val today = LocalDate.now()
        val end = start(today.plusDays(1))
        val open = Dedup.distinct(db.tasks().observeOpen().first()) { it.title }
        val overdue = open.filter { it.dueAt != null && it.dueAt < start(today) }
        val due = open.filter { it.dueAt != null && it.dueAt in start(today) until end }.sortedBy { it.remindAt ?: it.dueAt }
        val later = open.filter { it.dueAt == null }.sortedBy { it.priority }
        val parts = ArrayList<String>()
        if (open.isEmpty()) return Script("Pending", listOf("Nothing is pending. Your list is clear."))
        parts.add("You have ${count(open.size, "open task")}.")
        if (overdue.isNotEmpty()) { parts.add("Overdue:"); overdue.take(5).forEach { parts.add(spokenTask(it, withTime = false)) } }
        if (due.isNotEmpty()) { parts.add("Due today:"); due.take(8).forEach { parts.add(spokenTask(it)) } }
        if (later.isNotEmpty()) { parts.add("Without a date:"); later.take(5).forEach { parts.add(spokenTask(it, withTime = false)) } }
        return Script("Pending tasks", parts).tidy()
    }

    suspend fun reminders(): Script {
        val list = Dedup.distinct(db.tasks().upcomingReminders(System.currentTimeMillis())) { it.title }.take(10)
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
            val endsNew = Dedup.strings(ends)
            val tomorrowNew = Dedup.without(Dedup.strings(tomorrow), endsNew) { it }
            return Script("Summary of $label", listOfNotNull(e.headline?.let { "$it." }) + Speakable.sentences(e.diary) +
                (if (endsNew.isNotEmpty()) listOf("Still open: ${list(endsNew.take(4))}.") else emptyList()) +
                (if (tomorrowNew.isNotEmpty()) listOf("For tomorrow: ${list(tomorrowNew.take(4))}.") else emptyList())).tidy()
        }
        val convs = db.conversations().inRangeOnce(start(d), start(d.plusDays(1))).filter { it.state == "done" && !it.ignored }
        if (convs.isEmpty()) return Script("Summary of $label", listOf("There are no conversations to summarise for $label yet."))
        return Script("Summary of $label", listOf("${count(convs.size, "conversation")} $label.") + convs.map { "At ${Prompts.time(it.startAt)}, ${it.title}. ${it.summary.orEmpty()}" }).tidy()
    }

    fun note(n: Note): Script = Script(n.title, listOf(n.title + ".") + n.body.lines().map { it.trim().removePrefix("- ").removePrefix("## ") }.filter { it.isNotEmpty() && it != "---" }).tidy()

    fun conversation(c: Conversation): Script {
        val points = c.keyPoints?.let { runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()
        // Key points mostly restate the summary; only the new ones are read.
        val summary = c.summary.orEmpty()
        val fresh = Dedup.strings(points).filter { p -> Speakable.sentences(summary).none { Dedup.same(it, p, 0.6) } }
        return Script(c.title ?: "Conversation", listOfNotNull(c.title?.let { "$it, at ${Prompts.time(c.startAt)}." }, c.summary) + fresh).tidy()
    }

    fun tasks(title: String, list: List<TaskRow>): Script =
        Dedup.distinct(list) { it.title }.let { l -> Script(title, if (l.isEmpty()) listOf("Nothing here.") else listOf("${count(l.size, "task")}.") + l.map { spokenTask(it) }).tidy() }

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
