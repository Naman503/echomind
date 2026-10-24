package app.echomind.brain

import android.util.Log
import app.echomind.ai.Ai
import app.echomind.ai.LlmRequest
import app.echomind.ai.Prompts
import app.echomind.ai.S
import app.echomind.capture.CaptureController
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.Conversation
import app.echomind.data.Db
import app.echomind.data.Nudge
import app.echomind.notify.Notifier
import app.echomind.tts.Speaker
import app.echomind.util.int
import app.echomind.util.objects
import app.echomind.util.str
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The assistant's conscience: things worth saying without being asked.
 *
 * Sources — a question someone asked you that was left hanging, a call that
 * produced action items, something due within the hour with no reminder, a
 * midday and late-afternoon look at what's left, stretches where nothing
 * could be recorded, and (every few hours) an AI look at the day for anything
 * you might be missing.
 *
 * Interruptions cost attention, so delivery is strict (numbers from the
 * interruptibility research): a few a day, 90 minutes apart, never at night,
 * never during a call or while you're mid-conversation, and a kind you keep
 * dismissing goes quiet for a while. Everything also appears calmly on Today.
 */
class Nudges(
    private val db: Db,
    private val settings: SettingsStore,
    private val ai: Ai,
    private val notifier: Notifier,
    private val capture: () -> CaptureController,
    private val speaker: () -> Speaker,
) {
    private val zone get() = ZoneId.systemDefault()

    private suspend fun propose(n: Nudge) {
        if (!settings.value.nudges) return
        // The same point in other words, within two days, is not a new nudge.
        val recent = db.nudges().since(System.currentTimeMillis() - 48 * 3_600_000L)
        if (recent.any { Dedup.same(it.title + " " + it.body, n.title + " " + n.body, 0.75) }) return
        db.nudges().insert(n)
    }

    /** After a conversation is understood: hanging questions and action items. */
    suspend fun fromConversation(c: Conversation, questions: List<Pair<String, String?>>, tasksFound: Int) {
        val now = System.currentTimeMillis()
        for ((q, who) in questions.filter { (_, w) -> w?.trim()?.lowercase() !in setOf("me", "owner", "the owner", "i", "myself") }.take(2)) {
            val asker = who?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("someone", "unknown", "s1", "s2") } ?: "Someone"
            propose(Nudge(kind = "question", key = "q:${c.id}:${q.lowercase().hashCode()}", urgency = 2, createdAt = now, conversationId = c.id,
                title = "$asker asked you something", body = "“$q” — from ${c.title ?: "a conversation"} at ${Prompts.time(c.startAt)}. Did you answer?",
                taskText = "Reply to $asker: ${q.trimEnd('?')}"))
        }
        val long = c.endAt - c.startAt > 10 * 60_000L || c.source == "call"
        if (tasksFound > 0 && long) {
            propose(Nudge(kind = "recap", key = "recap:${c.id}", urgency = 1, createdAt = now, conversationId = c.id,
                title = "${c.title ?: "Conversation"}: $tasksFound action item${if (tasksFound == 1) "" else "s"}",
                body = (c.summary ?: "").take(220) + " Review them in Tasks."))
        }
        // The end of a conversation is a natural break — the best moment to speak up.
        deliver()
    }

    /** Periodic look (every ~15 minutes): time-based and health nudges, then delivery. */
    suspend fun check() {
        val s = settings.value
        if (!s.nudges || s.travel) return
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val minute = LocalTime.now().let { it.hour * 60 + it.minute }
        db.nudges().expire(now - 8 * 3_600_000L)

        // Due within the hour, with no reminder set.
        val open = db.tasks().observeOpen().first()
        for (t in open) {
            val due = t.dueAt ?: continue
            if (t.remindAt != null || due < now || due > now + 60 * 60_000L) continue
            val lt = java.time.Instant.ofEpochMilli(due).atZone(zone).toLocalTime()
            if (lt.hour == 9 && lt.minute == 0) continue // a date without a time
            propose(Nudge(kind = "due_soon", key = "due:${t.id}:$due", urgency = 3, createdAt = now, title = "Due at ${Prompts.time(due)}", body = t.title))
        }

        // Midday and late-afternoon check-ins about what's left today.
        val endToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val left = open.filter { it.dueAt != null && it.dueAt < endToday }
        for ((slot, from, to) in listOf(Triple("midday", 12 * 60 + 30, 14 * 60), Triple("late", 17 * 60, 18 * 60 + 30))) {
            if (minute !in from until to || left.isEmpty()) continue
            val names = left.sortedBy { it.priority }.take(3).joinToString(", ") { it.title.trimEnd('.') }
            propose(Nudge(kind = "check_in", key = "checkin:$today:$slot", urgency = 1, createdAt = now,
                title = "${left.size} thing${if (left.size == 1) "" else "s"} still on for today", body = names + if (left.size > 3) ", and more." else "."))
        }

        // Recording health: another app held the mic for a long stretch, or recordings failed.
        val gaps = db.gaps().inRangeOnce(now - 2 * 3_600_000L, now).filter { it.reason == "other_app" && it.endAt - it.startAt > 20 * 60_000L }
        gaps.lastOrNull()?.let { g ->
            propose(Nudge(kind = "audio", key = "gap:${g.id}", urgency = 1, createdAt = now, title = "Nothing was recorded for ${(g.endAt - g.startAt) / 60_000} min",
                body = "Another app had the microphone from ${Prompts.time(g.startAt)} to ${Prompts.time(g.endAt)}."))
        }
        val failed = db.audio().failed().first().filter { it.startedAt > now - 6 * 3_600_000L }
        if (failed.isNotEmpty()) propose(Nudge(kind = "audio", key = "failed:${failed.first().id}", urgency = 1, createdAt = now,
            title = "${failed.size} recording${if (failed.size == 1) "" else "s"} couldn't be transcribed", body = "Open Processing to retry. ${failed.first().error?.take(80) ?: ""}"))

        reflect(now, minute)
        deliver(now, minute)
    }

    private val reflectSchema = S.obj(
        "nudges" to S.arr(S.obj(
            "title" to S.str("max 8 words"),
            "body" to S.str("one or two short sentences"),
            "urgency" to S.int("1 nice to know, 2 useful today, 3 act now"),
            "task" to S.str("a task to add if the user agrees, or empty"),
        )),
    )

    /** Every ~3 hours in the daytime, the AI looks at the day for something genuinely worth saying. */
    private suspend fun reflect(now: Long, minute: Int) {
        if (minute !in 10 * 60 until 21 * 60 || !ai.ready(Feature.JOURNAL)) return
        val last = db.memory().kv("lastReflect")?.toLongOrNull() ?: 0
        if (now - last < 3 * 3_600_000L) return
        val dayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val convs = db.conversations().inRangeOnce(dayStart, now).filter { it.state == "done" && !it.ignored }
        val fresh = convs.count { (it.processedAt ?: 0) > last }
        if (fresh < 2) return
        db.memory().putKv(app.echomind.data.Kv("lastReflect", now.toString(), now))
        val open = db.tasks().observeOpen().first().take(15)
        val user = buildString {
            append("Now: ${Prompts.day(now)}, ${Prompts.time(now)}\n\nToday's conversations:\n")
            convs.takeLast(12).forEach { append("- ${Prompts.time(it.startAt)} ${it.title}: ${it.summary.orEmpty().take(200)}\n") }
            // A date without a time is stored at 9:00; saying "due 9:00 AM" made the model invent deadlines.
            append("\nOpen tasks:\n"); open.forEach { t ->
                val due = t.dueAt?.let { d ->
                    val lt = java.time.Instant.ofEpochMilli(d).atZone(zone).toLocalTime()
                    if (lt.hour == When.DEFAULT_HOUR && lt.minute == 0 && t.remindAt == null) " (due ${Prompts.day(d).substringBefore(',')})" else " (due ${Prompts.day(d).substringBefore(',')} ${Prompts.time(d)})"
                }.orEmpty()
                append("- ${t.title}$due\n")
            }
        }
        val system = """You are a thoughtful personal assistant who has been listening to the owner's day. Suggest at most two short nudges that would genuinely help right now: something they promised and may forget, a decision left open, a follow-up someone is waiting for, a conflict in their plans, or a pattern worth noticing. Only what the input supports. If nothing is genuinely useful, return an empty list — silence is better than noise. Write in ${settings.value.notesLanguage}."""
        val r = runCatching { ai.json(LlmRequest(Feature.JOURNAL, "Looking for anything you might be missing", system, user, reflectSchema, maxTokens = 400, deep = true)) }
            .onFailure { Log.w("EchoNudges", "reflect failed", it) }.getOrNull() ?: return
        for (o in r.objects("nudges").take(2)) {
            val title = o.str("title")?.trim().orEmpty()
            if (title.isEmpty()) continue
            // A time the input never mentioned is made up.
            val times = Regex("(?i)\\b\\d{1,2}(:\\d{2})?\\s?(am|pm)\\b").findAll(title + " " + o.str("body").orEmpty()).map { it.value.lowercase().replace(" ", "").replace(":00", "") }.toList()
            val known = Regex("(?i)\\b\\d{1,2}(:\\d{2})?\\s?(am|pm)\\b").findAll(user).map { it.value.lowercase().replace(" ", "").replace(":00", "") }.toSet()
            if (times.any { it !in known }) { Log.w("EchoNudges", "dropped a nudge with an invented time: $title"); continue }
            propose(Nudge(kind = "insight", key = "insight:${title.lowercase().hashCode()}:${LocalDate.now()}", urgency = (o.int("urgency") ?: 1).coerceIn(1, 3),
                createdAt = now, title = title, body = o.str("body")?.trim().orEmpty(), taskText = o.str("task")?.trim()?.takeIf { it.isNotEmpty() }))
        }
    }

    /** Shows at most one nudge now, if the moment is right. */
    suspend fun deliver(now: Long = System.currentTimeMillis(), minute: Int = LocalTime.now().let { it.hour * 60 + it.minute }) {
        val s = settings.value
        if (s.travel) return
        val night = minute < 8 * 60 || minute >= 22 * 60 || s.inQuietHours(minute)
        val mode = capture().state.value.mode
        if (mode == CaptureController.Mode.BLOCKED_CALL) return
        val talking = db.utterances().latest(1).first().firstOrNull()?.let { now - it.endAt < 90_000 } == true
        val dayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val shownToday = db.nudges().shownSince(dayStart)
        val lastShown = db.nudges().lastShownAt() ?: 0
        for (n in db.nudges().pending()) {
            val urgent = n.urgency >= 3
            if (night && !urgent) continue
            if (talking && !urgent) continue
            if (shownToday >= s.nudgesPerDay && !urgent) continue
            if (now - lastShown < (if (urgent) 20 else 90) * 60_000L) continue
            // A kind you dismissed in 3 of the last 5 times rests for a while.
            val recent = db.nudges().recentOutcomes(n.kind)
            if (recent.size >= 3 && recent.count { it.state == "dismissed" } >= 3 && now - (recent.first().shownAt ?: 0) < 3 * 86_400_000L && !urgent) {
                db.nudges().update(n.copy(state = "expired")); continue
            }
            db.nudges().update(n.copy(state = "shown", shownAt = now))
            notifier.nudge(n)
            if (s.speakNudges && speaker().headphonesConnected() && !speaker().state.value.active) speaker().speak(n.title, listOf(n.title + ".", n.body))
            return
        }
    }

    suspend fun act(id: Long, addTask: suspend (String) -> Unit) {
        val n = db.nudges().get(id) ?: return
        n.taskText?.let { addTask(it) }
        db.nudges().update(n.copy(state = "acted"))
    }

    suspend fun dismiss(id: Long) {
        val n = db.nudges().get(id) ?: return
        db.nudges().update(n.copy(state = "dismissed", shownAt = n.shownAt ?: System.currentTimeMillis()))
    }
}
