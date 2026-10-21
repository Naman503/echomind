package app.echomind.brain

import android.util.Log
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
import app.echomind.util.objects
import app.echomind.util.str
import app.echomind.util.strings
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
        val lines = db.utterances().range(from, to)
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
        val open = db.tasks().live().filter { it.status == "open" }.take(if (local) 8 else 25)
        val user = buildString {
            append("Day: ").append(Prompts.day(from)).append("\n\n")
            append("What happened:\n")
            convs.take(if (local) 14 else 60).forEach { append("- ${Prompts.time(it.startAt)} ${it.title}: ${it.summary.orEmpty().take(if (local) 200 else 600)}\n") }
            append("\nOpen tasks:\n")
            open.forEach { append("- ${it.title}${it.dueText?.let { d -> " ($d)" } ?: ""}\n") }
            append("\nMeasures: ${m.conversations} conversations, ${m.talkMinutes} minutes of talk, ${m.people} people, ${m.tasksMade} new tasks, ${m.tasksDone} done")
            if (m.lateNightMinutes > 10) append(", ${m.lateNightMinutes} min talking after 11 pm")
            append(".\n")
        }
        val r = runCatching {
            ai.json(LlmRequest(Feature.JOURNAL, "Writing today's journal", Prompts.journalSystem(settings.value.notesLanguage), user, Schemas.journal, maxTokens = 1_100, temperature = 0.4))
        }.onFailure { Log.w("EchoJournal", "journal failed", it) }.getOrNull() ?: return false
        fun list(k: String) = Js.json.encodeToString(ListSerializer(String.serializer()), r.strings(k))
        val improve = r.objects("improve").mapNotNull { o ->
            val obs = o.str("observation")?.trim().orEmpty()
            if (obs.isEmpty()) null else Improvement(obs, o.str("suggestion")?.trim().orEmpty())
        }.take(3)
        db.days().upsert(
            (existing ?: DayEntry(day = key)).copy(
                headline = r.str("headline"),
                diary = r.str("diary"),
                mood = r.dbl("mood")?.toFloat() ?: m.mood,
                highlights = list("highlights"),
                wins = list("wins"),
                looseEnds = list("loose_ends"),
                tomorrow = list("tomorrow"),
                improve = Js.json.encodeToString(ListSerializer(Improvement.serializer()), improve),
                metrics = metricsJson,
                generatedAt = System.currentTimeMillis(),
                provider = ai.llm(Feature.JOURNAL).id,
            ),
        )
        return true
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
