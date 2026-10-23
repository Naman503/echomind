package app.echomind.brain

import android.util.Log
import app.echomind.App
import app.echomind.ai.Ai
import app.echomind.ai.LlmRequest
import app.echomind.ai.Prompts
import app.echomind.ai.Schemas
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.DayEntry
import app.echomind.data.Db
import app.echomind.util.Js
import app.echomind.util.dbl
import app.echomind.util.int
import app.echomind.util.objects
import app.echomind.util.str
import app.echomind.util.strings
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate
import java.time.ZoneId

/** Day measures computed without AI — counts, never judgements. */
@Serializable
data class DayMetrics(
    val conversations: Int = 0,
    val words: Int = 0,
    val myWords: Int = 0,
    val talkMinutes: Int = 0,
    val people: Int = 0,
    val calls: Int = 0,
    val firstSpeech: Long? = null,
    val lastSpeech: Long? = null,
    val lateNightMinutes: Int = 0,
    val tasksMade: Int = 0,
    val tasksDone: Int = 0,
    val mood: Float? = null,
    val categories: Map<String, Int> = emptyMap(),
)

@Serializable
data class Improvement(val observation: String, val suggestion: String)

/**
 * The end-of-day pass: measures, a diary entry, loose ends, plans for tomorrow
 * and up to three gentle "things to improve" — then the memory review, so the
 * next day starts with an up-to-date profile.
 */
class Journal(private val db: Db, private val ai: Ai, private val settings: SettingsStore, private val memory: Memory) {
    private val zone get() = ZoneId.systemDefault()

    fun dayKey(d: LocalDate) = d.toString()
    fun bounds(d: LocalDate): Pair<Long, Long> = d.atStartOfDay(zone).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    suspend fun metrics(d: LocalDate): DayMetrics {
        val (from, to) = bounds(d)
        // Lyrics and check-in questions aren't talk.
        val lines = db.utterances().range(from, to).filter { it.speaker != Sessionizer.SONG && it.speaker != "EchoMind" }
        val convs = db.conversations().inRangeOnce(from, to).filter { it.state != "skipped" }
        val lateFrom = d.atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        val people = convs.flatMap { c -> runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), c.people ?: "[]") }.getOrDefault(emptyList()) }
            .map { it.lowercase() }.toSet()
        val moods = convs.mapNotNull { it.mood }
        return DayMetrics(
            conversations = convs.size,
            words = lines.sumOf { TextNorm.words(it.text) },
            myWords = lines.filter { it.speaker == "me" }.sumOf { TextNorm.words(it.text) },
            talkMinutes = (lines.sumOf { (it.endAt - it.startAt).coerceIn(0, 60_000) } / 60_000).toInt(),
            people = people.size,
            calls = convs.count { it.source == "call" },
            firstSpeech = lines.firstOrNull()?.startAt,
            lastSpeech = lines.lastOrNull()?.endAt,
            lateNightMinutes = (lines.filter { it.startAt >= lateFrom }.sumOf { (it.endAt - it.startAt).coerceIn(0, 60_000) } / 60_000).toInt(),
            tasksMade = db.tasks().createdIn(from, to).size,
            tasksDone = db.tasks().completedIn(from, to).size,
            mood = moods.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            categories = convs.groupingBy { it.category ?: "other" }.eachCount(),
        )
    }

    /** Writes (or rewrites) the journal for [d]. False if the AI is unavailable. */
    suspend fun write(d: LocalDate, force: Boolean = false): Boolean {
        val key = dayKey(d)
        val existing = db.days().get(key)
        if (existing?.userEdited == true && !force) {
            if (existing.generatedAt == null) db.days().upsert(existing.copy(generatedAt = System.currentTimeMillis()))
            return true
        }
        val m = metrics(d)
        val (from, to) = bounds(d)
        val convs = db.conversations().inRangeOnce(from, to).filter { it.state == "done" && !it.ignored }
        val metricsJson = Js.json.encodeToString(DayMetrics.serializer(), m)
        if (convs.isEmpty()) {
            db.days().upsert((existing ?: DayEntry(day = key)).copy(metrics = metricsJson, headline = existing?.headline ?: "A quiet day", generatedAt = System.currentTimeMillis()))
            return true
        }
        if (!ai.ready(Feature.JOURNAL)) {
            db.days().upsert((existing ?: DayEntry(day = key)).copy(metrics = metricsJson))
            return false
        }
        val local = ai.llm(Feature.JOURNAL).local
        val st = settings.value
        val about = st.aboutMe.trim().take(500)
        val reviews = App.graph.analyzer.let { an -> db.analyses().many("conv", convs.map { it.id.toString() }).mapNotNull { an.decode(it.json) } }
        val topics = reviews.flatMap { it.topics }.distinctBy { it.name.lowercase() }.take(12)
        val decisions = reviews.flatMap { it.decisions }.distinct().take(8)
        val people = convs.flatMap { c -> runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), c.people ?: "[]") }.getOrDefault(emptyList()) }
            .map { it.trim() }.filter { it.length >= 2 && !Memory.isJunkName(it) && !it.equals("other", true) }.distinctBy { it.lowercase() }.take(12)
        val user = buildString {
            if (about.isNotEmpty()) append("About me (background only):\n").append(about).append("\n\n")
            append("Day: ").append(Prompts.day(from)).append("\n")
            m.firstSpeech?.let { append("First words of the day: ").append(Prompts.time(it)).append(". ") }
            m.lastSpeech?.let { append("Last: ").append(Prompts.time(it)).append(".") }
            append("\n\nWhat happened, in order:\n")
            append(happened(convs, local))
            if (people.isNotEmpty()) append("\nPeople in the day: ").append(people.joinToString(", ")).append('\n')
            if (topics.isNotEmpty()) append("Topics: ").append(topics.joinToString("; ") { it.name + if (it.detail.isNotBlank()) " — " + it.detail.take(120) else "" }).append('\n')
            if (decisions.isNotEmpty()) append("Decided: ").append(decisions.joinToString("; ")).append('\n')
            append("\nMeasures: ${m.conversations} conversations, ${m.talkMinutes} minutes of talk, ${m.tasksMade} new tasks, ${m.tasksDone} done")
            if (m.lateNightMinutes > 10) append(", ${m.lateNightMinutes} min talking after 11 pm")
            append(".\n")
        }
        val done = db.tasks().completedIn(from, to)
        val open = db.tasks().live().filter { it.status == "open" }.take(if (local) 8 else 25)
        val events = db.events().range(from, to)
        val user2 = buildString {
            append(user)
            if (done.isNotEmpty()) append("\nFinished today: ").append(done.take(20).joinToString(" | ") { it.title }).append('\n')
            val answers = if (d == LocalDate.now()) runCatching { App.graph.checkIns.answersToday() }.getOrDefault(emptyList()) else emptyList()
            if (answers.isNotEmpty()) { append("\nWhat I said in today's check-in (my own words, the best source):\n"); answers.forEach { append("- ${it.question} → ${it.answer}\n") } }
            if (events.isNotEmpty()) append("\nOn the calendar: ").append(events.joinToString(" | ") { "${Prompts.time(it.startAt)} ${it.title}" }).append('\n')
        }
        // 1. The structure: title, highlights, loose ends, plans (constrained JSON, quick).
        val r = runCatching {
            ai.json(LlmRequest(Feature.JOURNAL, "Shaping the day", Prompts.journalSystem(st.notesLanguage), user2, Schemas.journal, maxTokens = 800, temperature = 0.4, noThink = true))
        }.onFailure { Log.w("EchoJournal", "journal structure failed", it) }.getOrNull()
        // 2. The diary: free prose in my voice, which models write far better than prose inside JSON.
        val diary = runCatching {
            val words = when (st.journalLength) { "short" -> 300; "long" -> 900; else -> 600 }
            ai.stream(LlmRequest(Feature.JOURNAL, "Writing your diary", Prompts.diarySystem(st.notesLanguage, st.journalStyle, st.journalVoice, st.journalLength, st.userName), user2,
                maxTokens = words, temperature = 0.85)).toList().joinToString("")
        }.onFailure { Log.w("EchoJournal", "diary failed", it) }.getOrNull()?.let(::cleanProse)
        // Never save a blank journal as done; the night review tries again.
        if (diary.isNullOrBlank() || diary.length < 80) { Log.w("EchoJournal", "diary came back empty"); return false }
        fun strings(k: String) = r?.strings(k).orEmpty()
        val loose = strings("loose_ends")
        val tomorrow = strings("tomorrow").filter { t -> loose.none { it.equals(t, true) } }
        fun list(l: List<String>) = Js.json.encodeToString(ListSerializer(String.serializer()), l)
        val improve = r?.objects("improve").orEmpty().mapNotNull { o ->
            val obs = o.str("observation")?.trim().orEmpty()
            if (obs.isEmpty()) null else Improvement(obs, o.str("suggestion")?.trim().orEmpty())
        }.take(3)
        // Small models sometimes answer on a 0-10 scale.
        val productivity = r?.int("productivity")?.let { if (it in 1..10) it * 10 else it }?.coerceIn(0, 100)
        db.days().upsert(
            (existing ?: DayEntry(day = key)).copy(
                headline = r?.str("headline")?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: diary.substringBefore('.').take(60),
                diary = diary,
                mood = r?.dbl("mood")?.toFloat() ?: m.mood,
                highlights = list(strings("highlights")),
                wins = list(strings("wins")),
                looseEnds = list(loose),
                tomorrow = list(tomorrow),
                improve = Js.json.encodeToString(ListSerializer(Improvement.serializer()), improve),
                metrics = metricsJson,
                productivity = productivity,
                productiveWhy = r?.str("productive_why")?.trim()?.takeIf { it.isNotEmpty() },
                generatedAt = System.currentTimeMillis(),
                provider = ai.llm(Feature.JOURNAL).id,
            ),
        )
        return true
    }

    /** Strips what models add around prose: titles, markdown, quotes, a date line. */
    private fun cleanProse(t: String): String = t.trim()
        .lines().dropWhile { l -> l.isBlank() || l.trimStart().startsWith("#") || l.trim().startsWith("**") && l.trim().endsWith("**") || Regex("(?i)^(dear diary|diary|journal)\\b.{0,40}$").matches(l.trim()) && l.length < 50 }
        .joinToString("\n")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?m)^#+\\s*"), "")
        .trim().trim('"', '“', '”').trim()

    /**
     * The day's conversations as lines for the journal. On the phone, a busy day is first
     * condensed a few conversations at a time, so the journal prompt fits the model.
     */
    private suspend fun happened(convs: List<app.echomind.data.Conversation>, local: Boolean): String {
        fun line(c: app.echomind.data.Conversation, n: Int) = "- ${Prompts.time(c.startAt)} ${c.title}: ${c.summary.orEmpty().take(n)}\n"
        if (!local) return convs.take(60).joinToString("") { line(it, 600) }
        if (convs.size <= 6) return convs.joinToString("") { line(it, 320) }
        val out = StringBuilder()
        for (group in convs.chunked(5)) {
            val text = group.joinToString("") { line(it, 300) }
            val r = runCatching {
                ai.json(LlmRequest(Feature.JOURNAL, "Condensing ${Prompts.time(group.first().startAt)}–${Prompts.time(group.last().endAt)}", app.echomind.ai.Deep.dayPartSystem(settings.value.notesLanguage), text, app.echomind.ai.Deep.dayPart, maxTokens = 450, noThink = true))
            }.getOrNull()
            val summary = r?.str("summary")?.takeIf { it.isNotBlank() }
            out.append(if (summary != null) "- ${Prompts.time(group.first().startAt)}–${Prompts.time(group.last().endAt)}: $summary\n" else group.joinToString("") { line(it, 140) })
        }
        return out.toString()
    }

    /** The nightly pass for [d]: journal, then the memory review. */
    suspend fun nightly(d: LocalDate): Boolean {
        val wrote = write(d)
        val key = dayKey(d)
        val entry = db.days().get(key)
        if (entry?.memoryReviewedAt == null) {
            val (from, to) = bounds(d)
            val summaries = db.conversations().inRangeOnce(from, to).filter { it.state == "done" && !it.ignored }.map { "${it.title}: ${it.summary.orEmpty()}" }
            if (memory.consolidate(from, to, summaries)) {
                db.days().upsert((db.days().get(key) ?: DayEntry(day = key)).copy(memoryReviewedAt = System.currentTimeMillis()))
            }
        }
        return wrote
    }

    suspend fun saveByUser(day: String, diary: String) {
        val e = db.days().get(day) ?: DayEntry(day = day)
        db.days().upsert(e.copy(diary = diary, userEdited = true))
    }
}
