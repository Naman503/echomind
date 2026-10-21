package app.echomind.brain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.echomind.ai.Ai
import app.echomind.ai.LlmRequest
import app.echomind.ai.Prompts
import app.echomind.ai.S
import app.echomind.core.CheckInSlot
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.CheckIn
import app.echomind.data.Conversation
import app.echomind.data.Db
import app.echomind.data.Kv
import app.echomind.data.Utterance
import app.echomind.notify.CheckInReceiver
import app.echomind.notify.Notifier
import app.echomind.util.Js
import app.echomind.util.int
import app.echomind.util.objects
import app.echomind.util.str
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Check-ins: the assistant asks about your day, you answer by voice (or
 * typing), and it learns what recordings can't tell it — whether you did what
 * you said you would, who that unnamed voice was, what happened while nothing
 * was recorded, how the day felt. Answers you skip are simply skipped.
 *
 * Questions come from your actual day first (tasks, unnamed speakers, gaps,
 * your own questions); the AI phrases them and adds a few about anything
 * unclear. Answers act directly (a "yes" completes the task, a name names the
 * speaker and learns their voice) and are saved as a "Check-in" conversation,
 * so the journal, memory and notes use them like anything else you said.
 */
class CheckIns(
    private val context: Context,
    private val db: Db,
    private val ai: Ai,
    private val settings: SettingsStore,
    private val tasks: Tasks,
    private val memory: Memory,
    private val voicePrints: VoicePrints,
    private val journal: Journal,
    private val notifier: Notifier,
) {
    @Serializable
    data class Q(val question: String, val kind: String, val why: String = "", val taskId: Long? = null, val ref: String? = null,
                 val answer: String? = null, val skipped: Boolean = false)

    private val zone get() = ZoneId.systemDefault()
    private val qs = ListSerializer(Q.serializer())
    private fun dayBounds(d: LocalDate = LocalDate.now()) = d.atStartOfDay(zone).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    // ── questions ──

    /** Questions taken straight from the day's facts — no AI needed. */
    suspend fun candidates(kind: String): List<Q> {
        val s = settings.value
        val topics = s.checkInTopics
        val (from, to) = dayBounds()
        val now = System.currentTimeMillis()
        val out = ArrayList<Q>()
        if ("tasks" in topics) {
            val open = db.tasks().observeOpen().first()
            // What was due today, or promised today in a conversation: did it happen?
            open.filter { (it.dueAt != null && it.dueAt < minOf(to, now + 3_600_000L)) || (it.origin != "user" && it.createdAt >= from) }
                .sortedWith(compareBy({ it.dueAt ?: Long.MAX_VALUE }, { it.priority })).take(if (kind == "morning") 2 else 5)
                .forEach { t -> out.add(Q(if (kind == "morning") "Is “${t.title.trimEnd('.')}” still on for today?" else "Did you ${t.title.replaceFirstChar { it.lowercase() }.trimEnd('.')}?", "task", "It was ${if (t.dueAt != null && t.dueAt < to) "due today" else "said in a conversation today"}", taskId = t.id)) }
        }
        if ("people" in topics && kind != "morning") {
            // Voices with no name in today's conversations (enough lines to be worth naming).
            for (c in db.conversations().inRangeOnce(from, now).filter { it.state == "done" && !it.ignored }) {
                val unnamed = db.utterances().speakers(c.id).filter { it.speaker != null && it.speaker.startsWith("S") && it.speaker.length <= 3 && it.n >= 3 }
                for (sp in unnamed.take(1)) out.add(Q("Who were you talking with at ${Prompts.time(c.startAt)} — “${c.title ?: "a conversation"}”?", "who",
                    "One voice in it has no name yet; naming it teaches EchoMind their voice", ref = "${c.id}:${sp.speaker}"))
            }
        }
        if ("gaps" in topics && kind != "morning") {
            db.gaps().inRangeOnce(from, now).filter { it.endAt - it.startAt > 45 * 60_000L && it.reason != "quiet" }.take(2).forEach { gp ->
                out.add(Q("What were you doing between ${Prompts.time(gp.startAt)} and ${Prompts.time(gp.endAt)}?", "gap", "Nothing was recorded then (${gp.reason.replace('_', ' ')})"))
            }
        }
        if ("custom" in topics && kind == "evening") s.checkInCustom.filter { it.isNotBlank() }.forEach { out.add(Q(it.trim(), "custom", "Your own question")) }
        return out
    }

    private val schema = S.obj(
        "questions" to S.arr(S.obj(
            "question" to S.str("One short, natural question, as a friend would ask it"),
            "kind" to S.enum("task", "who", "gap", "day", "feelings", "plans", "learning", "clarify", "custom"),
            "why" to S.str("One line: why you're asking (shown to the owner)"),
            "from" to S.int("the number of the listed question it rephrases, or 0 for a new one"),
        )),
    )

    /** The questions for a check-in: the day's facts first, the AI phrases them and adds what's unclear. */
    suspend fun questions(kind: String, max: Int): List<Q> {
        val base = candidates(kind)
        val s = settings.value
        if (!ai.ready(Feature.JOURNAL)) return fallback(kind, base).take(max)
        val (from, to) = dayBounds()
        val convs = db.conversations().inRangeOnce(from, to).filter { it.state == "done" && !it.ignored && it.source != "checkin" }
        val earlier = db.checkins().range(from, to).flatMap { runCatching { Js.json.decodeFromString(qs, it.qa) }.getOrDefault(emptyList()) }.filter { !it.skipped && it.answer != null }
        val user = buildString {
            append("Check-in: $kind, ${Prompts.day(System.currentTimeMillis())} ${Prompts.time(System.currentTimeMillis())}\n")
            append("Topics the owner wants: ${s.checkInTopics.joinToString(", ")}\nAt most $max questions.\n\n")
            if (base.isNotEmpty()) { append("Questions from the day's facts (keep the ones that matter, rephrase naturally):\n"); base.forEachIndexed { i, q -> append("${i + 1}. [${q.kind}] ${q.question} — ${q.why}\n") } }
            if (convs.isNotEmpty()) { append("\nToday's conversations:\n"); convs.takeLast(15).forEach { append("- ${Prompts.time(it.startAt)} ${it.title}: ${it.summary.orEmpty().take(200)}\n") } }
            if (earlier.isNotEmpty()) { append("\nAlready answered today (don't ask again):\n"); earlier.forEach { append("- ${it.question} → ${it.answer}\n") } }
        }
        val system = """You are a warm, curious personal assistant doing a short ${kind} check-in with the owner, to keep an honest journal of their day and to keep their tasks and people straight.
Choose the most useful questions: confirm tasks that were due or promised, name unknown voices, fill gaps, and ask about anything in the conversations that is unclear or unfinished. ${if (kind == "morning") "In the morning ask about plans and intentions, not about what happened." else ""}
Add ${if ("feelings" in s.checkInTopics) "one question about how the day felt" else "no questions about feelings"}${if ("learning" in s.checkInTopics) ", one about what they learned" else ""}${if ("plans" in s.checkInTopics) ", and one about tomorrow's priority" else ""} when there is room.
Short, specific, one thing per question, no yes/no chains. Never ask what was already answered. Write in ${s.notesLanguage}."""
        val r = runCatching { ai.json(LlmRequest(Feature.JOURNAL, "Preparing your check-in", system, user, schema, maxTokens = 700, deep = true)) }
            .onFailure { Log.w("EchoCheckIn", "questions failed", it) }.getOrNull() ?: return fallback(kind, base).take(max)
        val made = r.objects("questions").mapNotNull { o ->
            val text = o.str("question")?.trim()?.takeIf { it.length > 4 } ?: return@mapNotNull null
            val src = base.getOrNull((o.int("from") ?: 0) - 1)
            // A rephrased fact question keeps what it acts on (the task, the speaker).
            src?.copy(question = text, why = o.str("why")?.takeIf { it.isNotBlank() } ?: src.why) ?: Q(text, o.str("kind") ?: "day", o.str("why").orEmpty())
        }
        // Every task and naming question survives, even if the AI left it out.
        val kept = made + base.filter { b -> (b.kind == "task" || b.kind == "who") && made.none { it.taskId == b.taskId && it.ref == b.ref } }
        return kept.distinctBy { it.question.lowercase() }.take(max)
    }

    private fun fallback(kind: String, base: List<Q>): List<Q> = base + when (kind) {
        "morning" -> listOf(Q("What's the one thing you most want to get done today?", "plans"))
        else -> listOfNotNull(
            Q("How did today go, in a sentence or two?", "day"),
            if ("feelings" in settings.value.checkInTopics) Q("How are you feeling right now?", "feelings") else null,
            if ("plans" in settings.value.checkInTopics) Q("What's the most important thing for tomorrow?", "plans") else null,
        )
    }

    fun isSkip(answer: String): Boolean {
        val a = answer.lowercase().trim().trimEnd('.', '!')
        return settings.value.checkInSkipWords.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { a == it || a.startsWith("$it ") }
    }

    private fun yes(a: String) = Regex("\\b(yes|yeah|yep|done|did|finished|completed|haan|ha|ho gaya|kar liya|kar diya|ho gya|sent|paid)\\b", RegexOption.IGNORE_CASE).containsMatchIn(a) &&
        !Regex("\\b(not|no|nahi|nahin|didn't|didnt|haven't|not yet)\\b", RegexOption.IGNORE_CASE).containsMatchIn(a)
    private fun tomorrow(a: String) = Regex("\\b(tomorrow|kal)\\b", RegexOption.IGNORE_CASE).containsMatchIn(a)

    // ── finishing ──

    /** Saves the check-in, acts on the answers, and returns what changed. */
    suspend fun finish(kind: String, startedAt: Long, answers: List<Q>): List<String> {
        val now = System.currentTimeMillis()
        val changes = ArrayList<String>()
        for (q in answers) {
            val a = q.answer?.trim()?.takeIf { it.isNotEmpty() && !q.skipped } ?: continue
            when (q.kind) {
                "task" -> q.taskId?.let { db.tasks().get(it) }?.takeIf { it.status == "open" }?.let { t ->
                    when {
                        kind != "morning" && yes(a) -> { tasks.complete(t); changes.add("Marked “${t.title}” done") }
                        tomorrow(a) -> { tasks.reschedule(t, java.time.LocalDate.now().plusDays(1).atTime(When.DEFAULT_HOUR, 0).atZone(zone).toInstant().toEpochMilli()); changes.add("Moved “${t.title}” to tomorrow") }
                        else -> db.tasks().update(t.copy(detail = listOfNotNull(t.detail, "• Check-in ${Prompts.time(now)}: $a").joinToString("\n"), updatedAt = now))
                    }
                }
                "who" -> q.ref?.split(':')?.let { (cid, label) ->
                    val name = a.replace(Regex("^(it was|that was|with|woh|vo|wo)\\s+", RegexOption.IGNORE_CASE), "").split(Regex("[,.]| and | who | from ")).first().trim()
                        .split(' ').take(3).joinToString(" ").replaceFirstChar { it.uppercase() }
                    if (name.length in 2..40 && !Regex("\\b(don't|dont|no idea|pata nahi|someone)\\b", RegexOption.IGNORE_CASE).containsMatchIn(a)) {
                        db.utterances().renameSpeaker(cid.toLong(), label, name)
                        memory.link("person", name, a.takeIf { it.length > name.length + 3 }, now)
                        if (settings.value.recognisePeople) voicePrints.learnFromConversation(cid.toLong(), null)
                        changes.add("Named a voice at ${db.conversations().get(cid.toLong())?.startAt?.let { Prompts.time(it) } ?: ""} as $name")
                    }
                }
            }
        }
        // The questions and answers become a conversation, so memory, notes and the journal use them.
        val said = answers.filter { !it.skipped && !it.answer.isNullOrBlank() }
        var convId: Long? = null
        if (said.isNotEmpty()) {
            val conv = Conversation(startAt = startedAt, endAt = now, source = "checkin", title = "${kind.replaceFirstChar { it.uppercase() }} check-in",
                words = said.sumOf { TextNorm.words(it.answer!!) }, state = "ready")
            convId = db.conversations().insert(conv)
            var at = startedAt
            val lines = ArrayList<Utterance>()
            for (q in said) {
                lines.add(Utterance(conversationId = convId, startAt = at, endAt = at + 2_000, text = q.question, speaker = "EchoMind", source = "checkin", searchText = TextNorm.searchable(q.question)))
                lines.add(Utterance(conversationId = convId, startAt = at + 2_000, endAt = at + 8_000, text = q.answer!!, speaker = "me", source = "checkin", searchText = TextNorm.searchable(q.answer)))
                at += 10_000
            }
            db.utterances().insertAll(lines)
        }
        db.checkins().insert(CheckIn(kind = kind, startedAt = startedAt, endedAt = now, qa = Js.json.encodeToString(qs, answers),
            changes = Js.json.encodeToString(ListSerializer(String.serializer()), changes), conversationId = convId))
        if (kind == "evening" && settings.value.checkInJournal && said.isNotEmpty()) runCatching { journal.write(LocalDate.now(), force = true) }?.let { changes.add("Updated today's journal") }
        return changes
    }

    /** Today's check-in answers, for the journal. */
    suspend fun answersToday(): List<Q> {
        val (from, to) = dayBounds()
        return db.checkins().range(from, to).flatMap { runCatching { Js.json.decodeFromString(qs, it.qa) }.getOrDefault(emptyList()) }.filter { !it.skipped && !it.answer.isNullOrBlank() }
    }

    // ── schedule ──

    private val am get() = context.getSystemService(AlarmManager::class.java)

    private fun pi(slot: CheckInSlot) = PendingIntent.getBroadcast(context, 960_000 + slot.id.hashCode() % 10_000,
        Intent(context, CheckInReceiver::class.java).putExtra(CheckInReceiver.EXTRA_SLOT, slot.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Sets an alarm for the next time of every enabled check-in. */
    fun schedule() {
        for (slot in settings.value.checkIns) {
            am.cancel(pi(slot))
            if (!slot.enabled || slot.days.isEmpty()) continue
            var d = LocalDate.now()
            repeat(8) {
                val at = d.atTime(LocalTime.of(slot.minute / 60, slot.minute % 60)).atZone(zone).toInstant().toEpochMilli()
                if (at > System.currentTimeMillis() && d.dayOfWeek.value in slot.days) {
                    am.setWindow(AlarmManager.RTC_WAKEUP, at, 5 * 60_000L, pi(slot)); return@repeat
                }
                d = d.plusDays(1)
            }
        }
    }

    /** The alarm went off: invite to the check-in (a tap opens it), then set the next one. */
    suspend fun due(slotId: String) {
        val slot = settings.value.checkIns.firstOrNull { it.id == slotId } ?: return
        schedule()
        if (!slot.enabled) return
        val n = candidates(slot.kind).size
        notifier.checkInPrompt(slot, n)
    }

    // ── quick questions through the day ──

    /** One question as a notification you can answer inline — at natural breaks, a few a day. */
    suspend fun quick() {
        val s = settings.value
        if (!s.quickQuestions) return
        val now = System.currentTimeMillis()
        val minute = LocalTime.now().let { it.hour * 60 + it.minute }
        if (minute < 10 * 60 || minute > 21 * 60 || s.inQuietHours(minute)) return
        val key = "quickq:${LocalDate.now()}"
        val asked = db.memory().kv(key)?.split('|')?.filter { it.isNotEmpty() }.orEmpty()
        if (asked.size >= s.quickQuestionsPerDay) return
        val last = db.memory().kv("quickq:last")?.toLongOrNull() ?: 0
        if (now - last < 2 * 3_600_000L) return
        // Only questions whose answer changes something: a name, or a task.
        val q = candidates("quick").firstOrNull { it.kind in setOf("who", "task") && (it.ref ?: "t${it.taskId}") !in asked } ?: return
        db.memory().putKv(Kv(key, (asked + (q.ref ?: "t${q.taskId}")).joinToString("|"), now))
        db.memory().putKv(Kv("quickq:last", now.toString(), now))
        db.memory().putKv(Kv("quickq:pending", Js.json.encodeToString(Q.serializer(), q), now))
        notifier.quickQuestion(q.question, q.why)
    }

    /** An inline reply to the quick question. */
    suspend fun answerQuick(text: String): List<String> {
        val q = db.memory().kv("quickq:pending")?.let { runCatching { Js.json.decodeFromString(Q.serializer(), it) }.getOrNull() } ?: return emptyList()
        db.memory().putKv(Kv("quickq:pending", "", System.currentTimeMillis()))
        val answered = q.copy(answer = text, skipped = isSkip(text))
        return finish("quick", System.currentTimeMillis(), listOf(answered))
    }
}
