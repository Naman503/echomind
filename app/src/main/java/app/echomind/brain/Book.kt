package app.echomind.brain

import android.util.Log
import app.echomind.ai.Ai
import app.echomind.ai.LlmRequest
import app.echomind.ai.S
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.Analysis
import app.echomind.data.Db
import app.echomind.data.DayEntry
import app.echomind.data.Kv
import app.echomind.util.Js
import app.echomind.util.str
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

@Serializable
data class Chapter(
    val week: String,
    val from: String,
    val to: String,
    val title: String,
    val epigraph: String = "",
    val text: String,
    val summary: String = "",
    val days: List<String> = emptyList(),
    val writtenAt: Long = 0,
    val version: Int = Book.VERSION,
)

@Serializable
data class BookPart(val month: String, val title: String, val intro: String = "", val writtenAt: Long = 0)

/**
 * The book of your life, written from your diary: each finished week becomes a
 * chapter (memoir prose, a title and an epigraph), each month a part with a
 * short opening. Chapters are written at night once a week is over; the week
 * in progress is read straight from its days.
 */
class Book(private val db: Db, private val ai: Ai, private val settings: SettingsStore) {

    fun weekOf(d: LocalDate): String = "${d.get(IsoFields.WEEK_BASED_YEAR)}-W${"%02d".format(d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))}"
    fun monday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun daysOf(week: String): List<LocalDate> {
        val (y, w) = week.split("-W").let { it[0].toInt() to it[1].toInt() }
        val start = LocalDate.of(y, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, w.toLong()).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0..6).map { start.plusDays(it.toLong()) }
    }

    fun decodeChapter(json: String) = runCatching { Js.json.decodeFromString(Chapter.serializer(), json) }.getOrNull()
    fun decodePart(json: String) = runCatching { Js.json.decodeFromString(BookPart.serializer(), json) }.getOrNull()

    suspend fun chapter(week: String): Chapter? = db.analyses().get("chapter", week)?.let { decodeChapter(it.json) }

    /** Diary days of a week that have an entry. */
    suspend fun diaryDays(week: String): List<DayEntry> = daysOf(week).mapNotNull { db.days().get(it.toString()) }.filter { !it.diary.isNullOrBlank() }

    /** All weeks with at least one diary entry, oldest first. */
    suspend fun weeks(): List<String> = db.days().since("2000-01-01").filter { !it.diary.isNullOrBlank() }
        .map { weekOf(LocalDate.parse(it.day)) }.distinct().sorted()

    fun isRead(week: String, kv: Map<String, String>) = kv["book.read.$week"] == "1"
    suspend fun markRead(week: String) = db.memory().putKv(Kv("book.read.$week", "1", System.currentTimeMillis()))

    /**
     * Night step: the most recent finished week (or [force]d one) with two or more diary days
     * and no current chapter gets written; then a month's opening. True if it did something.
     */
    suspend fun step(force: String? = null): Boolean {
        if (!ai.ready(Feature.JOURNAL)) return false
        val thisWeek = weekOf(LocalDate.now())
        val candidates = if (force != null) listOf(force) else weeks().filter { it < thisWeek }.reversed()
        for (w in candidates) {
            val existing = chapter(w)
            val days = diaryDays(w)
            if (days.size < if (force != null) 1 else 2) continue
            // Rewrite when diaries were added or rewritten after the chapter.
            val newest = days.maxOf { it.generatedAt ?: 0 }
            if (force == null && existing != null && existing.version >= VERSION && existing.writtenAt >= newest) continue
            write(w, days)
            return true
        }
        // A part's opening once its month has a chapter.
        for (month in weeks().filter { it < thisWeek }.mapNotNull { chapter(it) }.groupBy { it.from.take(7) }) {
            if (db.analyses().get("part", month.key) != null && month.key != LocalDate.now().toString().take(7)) continue
            val old = db.analyses().get("part", month.key)?.let { decodePart(it.json) }
            if (old != null && month.value.all { it.writtenAt <= old.writtenAt }) continue
            writePart(month.key, month.value)
            return true
        }
        return false
    }

    private suspend fun write(week: String, days: List<DayEntry>) {
        val s = settings.value
        val local = ai.llm(Feature.JOURNAL).local
        val per = if (local) 900 else 3_000
        val input = buildString {
            s.aboutMe.trim().takeIf { it.isNotEmpty() }?.let { append("About me: ").append(it.take(400)).append("\n\n") }
            append("This week's diary entries:\n\n")
            for (d in days) {
                append("— ").append(LocalDate.parse(d.day).let { "${it.dayOfWeek.name.lowercase().replaceFirstChar { c -> c.uppercase() }}, ${it.dayOfMonth} ${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }}" })
                d.headline?.let { append(" · ").append(it) }
                append("\n").append(d.diary.orEmpty().take(per)).append("\n\n")
            }
        }
        val words = if (local) "400-650" else "600-1000"
        val voice = if (s.journalVoice == "second") "second person (\"You…\")" else "first person (\"I…\"), as ${s.userName.ifBlank { "the author" }}"
        val system = """You are writing one chapter of a memoir made from a real person's diary. Write in ${s.notesLanguage}, in $voice, past tense.
The chapter covers one week. Turn the days into a single story with an arc — what the week was about, what changed, who mattered, what was learned — like a chapter of a well-written memoir, intimate and cinematic but never purple.
Rules:
1. Only what the diary says. Never invent events, people, places or numbers.
2. $words words of flowing prose in paragraphs. No headings, no lists, no dates as headings, no emoji.
3. Fresh, concrete images; avoid stock phrases like tapestry, journey, testament, dance, woven.
4. End on a line that carries the reader into the next week."""
        val prose = runCatching {
            ai.stream(LlmRequest(Feature.JOURNAL, "Writing the chapter for $week", system, input, maxTokens = if (local) 950 else 1_600, temperature = 0.85)).toList().joinToString("")
        }.onFailure { Log.w(TAG, "chapter failed", it) }.getOrNull()?.trim()?.trim('"')?.let { first ->
            val made = Grounding.unknownNames(first, input)
            if (made.size < 2) first else runCatching {
                ai.stream(LlmRequest(Feature.JOURNAL, "Rewriting the chapter for $week", system + "\n5. Use only names that appear in the diary. Do not mention: ${made.take(8).joinToString(", ")}.",
                    input, maxTokens = if (local) 950 else 1_600, temperature = 0.55)).toList().joinToString("").trim().trim('"')
            }.getOrNull()?.takeIf { it.length >= 200 } ?: first
        }
        if (prose.isNullOrBlank() || prose.length < 200) return
        val meta = runCatching {
            ai.json(LlmRequest(Feature.JOURNAL, "Naming the chapter", "You name a memoir chapter. Write in ${s.notesLanguage}. title: 2-6 evocative words, true to the chapter. epigraph: one short line (under 20 words) taken from or faithful to the chapter, without quotation marks. summary: one sentence for the table of contents.",
                prose.take(4_000), S.obj("title" to S.str(), "epigraph" to S.str(), "summary" to S.str()), maxTokens = 200, noThink = true))
        }.getOrNull()
        val dates = daysOf(week)
        val ch = Chapter(
            week = week, from = dates.first().toString(), to = dates.last().toString(),
            title = meta?.str("title")?.trim()?.trim('"', '“', '”')?.takeIf { it.isNotBlank() } ?: "The week of ${dates.first().dayOfMonth} ${dates.first().month.name.lowercase().replaceFirstChar { it.uppercase() }}",
            epigraph = meta?.str("epigraph")?.trim()?.trim('"', '“', '”').orEmpty(),
            text = prose.lines().dropWhile { it.isBlank() || it.trimStart().startsWith("#") }.joinToString("\n").trim(),
            summary = meta?.str("summary")?.trim().orEmpty(),
            days = days.map { it.day }, writtenAt = System.currentTimeMillis(),
        )
        db.analyses().upsert(Analysis("chapter", week, Js.json.encodeToString(Chapter.serializer(), ch), VERSION, ch.writtenAt, ai.llm(Feature.JOURNAL).id))
    }

    private suspend fun writePart(month: String, chapters: List<Chapter>) {
        val s = settings.value
        val input = chapters.sortedBy { it.week }.joinToString("\n") { "- ${it.title}: ${it.summary.ifBlank { it.text.take(300) }}" }
        val r = runCatching {
            ai.json(LlmRequest(Feature.JOURNAL, "Opening ${month}", "You write the opening page of one part (a month) of a memoir made from a real diary, in ${s.notesLanguage}, ${if (s.journalVoice == "second") "second person" else "first person"}. title: 2-6 evocative words for the month. intro: 2-4 sentences that set up the month's story. Only what the chapters say.",
                input, S.obj("title" to S.str(), "intro" to S.str()), maxTokens = 300, noThink = true))
        }.getOrNull() ?: return
        val p = BookPart(month, r.str("title")?.trim()?.trim('"').orEmpty().ifBlank { month }, r.str("intro")?.trim().orEmpty(), System.currentTimeMillis())
        db.analyses().upsert(Analysis("part", month, Js.json.encodeToString(BookPart.serializer(), p), VERSION, p.writtenAt, ai.llm(Feature.JOURNAL).id))
    }

    companion object {
        const val VERSION = 1
        private const val TAG = "EchoBook"
    }
}
