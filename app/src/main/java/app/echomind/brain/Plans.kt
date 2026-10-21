package app.echomind.brain

import android.util.Log
import app.echomind.ai.Ai
import app.echomind.ai.LlmRequest
import app.echomind.ai.Prompts
import app.echomind.ai.S
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.Db
import app.echomind.data.Kv
import app.echomind.util.Js
import app.echomind.util.int
import app.echomind.util.objects
import app.echomind.util.str
import app.echomind.util.strings
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/**
 * What's going on and what's next: a plan for today, this week and this month,
 * written from memory (goals, open loops, people), tasks, the calendar and
 * recent days — plus what you might be missing. Refreshed each morning (the
 * week on Mondays, the month on the 1st) and whenever you ask.
 */
class Plans(private val db: Db, private val ai: Ai, private val settings: SettingsStore, private val memory: Memory, private val agenda: () -> Agenda) {

    @Serializable data class Item(val title: String, val why: String = "", val taskId: Long? = null)
    @Serializable data class Slot(val time: String, val what: String)
    @Serializable data class Plan(
        val kind: String, val period: String, val focus: String = "", val summary: String = "",
        val priorities: List<Item> = emptyList(), val schedule: List<Slot> = emptyList(),
        val missing: List<Item> = emptyList(), val suggestions: List<String> = emptyList(), val madeAt: Long = 0,
    )

    private val zone get() = ZoneId.systemDefault()
    private val json = Json { ignoreUnknownKeys = true }

    fun period(kind: String, d: LocalDate = LocalDate.now()): String = when (kind) {
        "week" -> "${d.get(IsoFields.WEEK_BASED_YEAR)}-W${d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
        "month" -> d.toString().take(7)
        else -> d.toString()
    }

    suspend fun get(kind: String, d: LocalDate = LocalDate.now()): Plan? =
        db.memory().kv("plan:$kind:${period(kind, d)}")?.let { runCatching { json.decodeFromString(Plan.serializer(), it) }.getOrNull() }

    fun observe(kind: String, d: LocalDate = LocalDate.now()) = db.memory().observeKv("plan:$kind:${period(kind, d)}")

    fun decode(s: String?): Plan? = s?.let { runCatching { json.decodeFromString(Plan.serializer(), it) }.getOrNull() }

    private val schema = S.obj(
        "focus" to S.str("The one thing that matters most in this period, in a short sentence"),
        "summary" to S.str("2-3 sentences: what is going on and what this period is about"),
        "priorities" to S.arr(S.obj("title" to S.str(), "why" to S.str("one short line"), "task" to S.int("the T number if it is an existing task, else 0"))),
        "schedule" to S.arr(S.obj("time" to S.str("'9:00', 'Morning', 'Tuesday' or 'Week 2'"), "what" to S.str())),
        "missing" to S.arr(S.obj("title" to S.str("Starts with a verb"), "why" to S.str("why it may be slipping, from the input"))),
        "suggestions" to S.arr(S.str(), "Up to 3 practical suggestions"),
    )

    /** Writes the plan for [kind] ("day", "week", "month"). */
    suspend fun make(kind: String, d: LocalDate = LocalDate.now()): Plan? {
        if (!ai.ready(Feature.JOURNAL)) return null
        val local = ai.llm(Feature.JOURNAL).local
        val (from, to) = when (kind) {
            "week" -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(7) }
            "month" -> d.withDayOfMonth(1).let { it to it.plusMonths(1) }
            else -> d to d.plusDays(1)
        }.let { (a, b) -> a.atStartOfDay(zone).toInstant().toEpochMilli() to b.atStartOfDay(zone).toInstant().toEpochMilli() }
        val now = System.currentTimeMillis()
        val open = db.tasks().observeOpen().first().sortedWith(compareBy({ it.dueAt == null }, { it.dueAt }, { it.priority })).take(if (local) 15 else 40)
        val events = runCatching { agenda().range(maxOf(from, now - 3_600_000L), to) }.getOrDefault(emptyList()).filter { it.status == "confirmed" }
        val goals = db.memory().activeFacts().filter { it.kind in setOf("goal", "open_loop", "event") }.sortedByDescending { it.importance * 10 + (it.updatedAt / 86_400_000L) }.take(if (local) 10 else 30)
        val recentDays = db.days().since(LocalDate.now().minusDays(if (kind == "day") 2 else 10).toString()).filter { it.headline != null }
        val recentConvs = db.conversations().doneBefore(now - (if (kind == "day") 2 else 7) * 86_400_000L, now, if (local) 8 else 25)
        val parent = when (kind) { "day" -> get("week", d); "week" -> get("month", d); else -> null }
        val user = buildString {
            append("Plan for: ").append(when (kind) { "week" -> "the week of ${Prompts.day(from)}"; "month" -> d.month.name.lowercase().replaceFirstChar { it.uppercase() } + " ${d.year}"; else -> Prompts.day(from) }).append('\n')
            append("Now: ${Prompts.day(now)}, ${Prompts.time(now)}\n\n")
            memory.profile().takeIf { it.isNotBlank() }?.let { append("About them:\n").append(it.take(if (local) 600 else 2_000)).append("\n\n") }
            parent?.let { append("The ${if (kind == "day") "week" else "month"}'s focus: ${it.focus}\n\n") }
            if (goals.isNotEmpty()) { append("Goals and open loops from memory:\n"); goals.forEach { append("- ${it.subject}: ${it.statement}\n") }; append('\n') }
            append("Open tasks:\n")
            open.forEachIndexed { i, t -> append("T${i + 1}. ${t.title}${t.dueAt?.let { " (due ${Prompts.day(it).substringBefore(',')} ${if (t.remindAt != null) Prompts.time(t.remindAt) else ""})" } ?: ""}${if (t.priority < 4) " P${t.priority}" else ""}\n") }
            if (events.isNotEmpty()) { append("\nCalendar:\n"); events.take(30).forEach { append("- ${Prompts.day(it.startAt).substringBefore(',')} ${if (it.allDay) "all day" else Prompts.time(it.startAt)}: ${it.title}\n") } }
            if (recentDays.isNotEmpty()) { append("\nRecent days:\n"); recentDays.forEach { append("- ${it.day}: ${it.headline}${it.tomorrow.takeIf { t -> t != "[]" }?.let { t -> " · meant to do next: $t" } ?: ""}\n") } }
            if (recentConvs.isNotEmpty()) { append("\nRecent conversations:\n"); recentConvs.forEach { append("- ${Prompts.day(it.startAt).substringBefore(',')}: ${it.title} — ${it.summary.orEmpty().take(if (local) 120 else 300)}\n") } }
        }
        val r = runCatching {
            ai.json(LlmRequest(Feature.JOURNAL, "Planning ${if (kind == "day") "today" else "this $kind"}", Prompts.planSystem(kind, settings.value.notesLanguage), user, schema, maxTokens = if (local) 700 else 1_400, deep = true))
        }.onFailure { Log.w("EchoPlans", "plan failed", it) }.getOrNull() ?: return null
        val taskOf = { n: Int -> open.getOrNull(n - 1)?.id }
        val plan = Plan(
            kind = kind, period = period(kind, d), focus = r.str("focus").orEmpty(), summary = r.str("summary").orEmpty(),
            priorities = r.objects("priorities").mapNotNull { o -> o.str("title")?.takeIf { it.isNotBlank() }?.let { Item(it, o.str("why").orEmpty(), (o.int("task") ?: 0).takeIf { n -> n > 0 }?.let(taskOf)) } }.take(7),
            schedule = r.objects("schedule").mapNotNull { o -> o.str("what")?.takeIf { it.isNotBlank() }?.let { Slot(o.str("time").orEmpty(), it) } }.take(12),
            missing = r.objects("missing").mapNotNull { o -> o.str("title")?.takeIf { it.isNotBlank() }?.let { Item(it, o.str("why").orEmpty()) } }.take(6),
            suggestions = r.strings("suggestions").take(3), madeAt = now,
        )
        db.memory().putKv(Kv("plan:$kind:${plan.period}", json.encodeToString(Plan.serializer(), plan), now))
        return plan
    }

    /** Morning: today's plan, and the week's / month's when they're new or missing. */
    suspend fun morning() {
        val d = LocalDate.now()
        if (get("month", d) == null || d.dayOfMonth == 1) make("month", d)
        if (get("week", d) == null || d.dayOfWeek == DayOfWeek.MONDAY) make("week", d)
        if (get("day", d) == null) make("day", d)
    }
}
