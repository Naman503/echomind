package app.echomind.brain

import android.util.Log
import app.echomind.ai.Ai
import app.echomind.ai.Deep
import app.echomind.ai.LlmRequest
import app.echomind.ai.Prompts
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.Analysis
import app.echomind.data.Conversation
import app.echomind.data.Db
import app.echomind.util.Js
import app.echomind.util.int
import app.echomind.util.objects
import app.echomind.util.str
import app.echomind.util.strings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate

@Serializable data class Lesson(val title: String, val detail: String = "")
@Serializable data class PersonNote(val name: String, val note: String)
@Serializable data class DayTopic(val name: String, val detail: String = "", val conversations: List<Long> = emptyList())

/** Who the owner talked with on a day, measured from the transcript. */
@Serializable
data class PersonDay(val name: String, val seconds: Int, val words: Int, val conversations: List<Long>, val named: Boolean)

/** The careful review of one day: measures, people, topics and what to learn from it. */
@Serializable
data class DayReviewData(
    val version: Int = DayReview.VERSION,
    val conversations: Int = 0,
    val people: List<PersonDay> = emptyList(),
    /** Voices that were never named, across the day's conversations. */
    val unnamedVoices: Int = 0,
    val topics: List<DayTopic> = emptyList(),
    val decisions: List<String> = emptyList(),
    val speech: SpeechStats.Result = SpeechStats.Result(),
    val confidence: Int? = null,
    val clarity: Int? = null,
    val communication: Int? = null,
    val lessons: List<Lesson> = emptyList(),
    val patterns: List<String> = emptyList(),
    val peopleNotes: List<PersonNote> = emptyList(),
    val followups: List<String> = emptyList(),
    val bestMoment: String? = null,
    val hardMoment: String? = null,
    val coachPoints: Int = 0,
    val reviewedAt: Long = 0,
)

/**
 * The night review. Done one small step at a time (so heat and battery rules
 * apply between steps): read again any conversation an older pipeline read,
 * coach each conversation, rewrite the journal, then review the day.
 */
class DayReview(
    private val db: Db,
    private val ai: Ai,
    private val settings: SettingsStore,
    private val analyzer: Analyzer,
    private val enricher: Enricher,
    private val journal: Journal,
) {
    data class Progress(val day: String, val label: String, val done: Int, val total: Int)

    private val fails = HashMap<String, Int>()
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    suspend fun load(day: String): DayReviewData? = db.analyses().get("day", day)?.let { decode(it.json) }
    fun decode(json: String): DayReviewData? = runCatching { Js.json.decodeFromString(DayReviewData.serializer(), json) }.getOrNull()

    private suspend fun conversations(d: LocalDate): List<Conversation> {
        val (from, to) = journal.bounds(d)
        return db.conversations().inRangeOnce(from, to).filter { it.state == "done" && !it.ignored }
    }

    /** True when the day has a current review covering all its conversations. */
    suspend fun isComplete(d: LocalDate): Boolean {
        val r = load(d.toString()) ?: return false
        return r.version >= VERSION && r.conversations >= conversations(d).size
    }

    /** Whether there is anything to review on [d] at all. */
    suspend fun hasConversations(d: LocalDate) = conversations(d).isNotEmpty()

    /**
     * Does the next unit of work for [d]. Returns false when the day is fully reviewed
     * (or there is nothing to do).
     */
    suspend fun step(d: LocalDate): Boolean {
        val day = d.toString()
        val convs = conversations(d)
        if (convs.isEmpty()) { _progress.value = null; return false }
        val analyses = db.analyses().many("conv", convs.map { it.id.toString() }).associateBy { it.ref.toLong() }.mapValues { analyzer.decode(it.value.json) }
        val s = settings.value
        val total = convs.size * 2 + 2
        var doneUnits = 0

        // 1. Conversations an older (weaker) pipeline read.
        if (s.reReadOld) {
            val stale = convs.filter { it.words >= 20 && (analyses[it.id]?.version ?: 0) < Analyzer.VERSION }
            doneUnits += convs.size - stale.size
            stale.firstOrNull()?.let { c ->
                _progress.value = Progress(day, "Reading again: ${c.title ?: Prompts.time(c.startAt)}", doneUnits, total)
                runCatching { enricher.enrich(c) }.onFailure {
                    Log.w(TAG, "re-read ${c.id} failed", it)
                    enricher.markFailed(c, it)
                    // Never loop on one conversation: mark it read so the review moves on.
                    analyzer.save(c.id, (analyses[c.id] ?: ConvAnalysis()).copy(version = Analyzer.VERSION))
                }
                return true
            }
        } else doneUnits += convs.size

        // 2. The speaking coach, per conversation.
        val uncoached = convs.filter { it.source != "import" && analyses[it.id]?.coachedAt == null }
        doneUnits += convs.size - uncoached.size
        uncoached.firstOrNull()?.let { c ->
            _progress.value = Progress(day, "Coaching: ${c.title ?: Prompts.time(c.startAt)}", doneUnits, total)
            val utts = db.utterances().forConversation(c.id)
            val coach = runCatching { analyzer.coach(c, utts) }.onFailure { Log.w(TAG, "coach ${c.id} failed", it) }.getOrNull()
            val base = analyses[c.id] ?: ConvAnalysis(stats = analyzer.stats(utts))
            analyzer.save(c.id, base.copy(coach = coach ?: base.coach, coachedAt = System.currentTimeMillis(), stats = base.stats ?: analyzer.stats(utts)))
            return true
        }

        // 3 + 4. Journal, then the day review (once per new set of conversations).
        val existing = load(day)
        if (existing != null && existing.version >= VERSION && existing.conversations >= convs.size) { _progress.value = null; return false }
        _progress.value = Progress(day, "Writing the journal", total - 2, total)
        runCatching { journal.write(d) }.onFailure { Log.w(TAG, "journal failed", it) }
        _progress.value = Progress(day, "Reviewing the day", total - 1, total)
        val fresh = db.analyses().many("conv", convs.map { it.id.toString() }).associateBy { it.ref.toLong() }.mapValues { analyzer.decode(it.value.json) }
        val review = review(d, convs, fresh)
        // If the AI part failed, try again on a later step (twice at most), so a timeout never
        // leaves the day "done" without its lessons.
        val tries = (fails[day] ?: 0)
        if (review.lessons.isEmpty() && review.followups.isEmpty() && review.communication == null && tries < 2 && ai.ready(Feature.JOURNAL)) {
            fails[day] = tries + 1
            _progress.value = null
            return true
        }
        db.analyses().upsert(Analysis("day", day, Js.json.encodeToString(DayReviewData.serializer(), review), review.version, System.currentTimeMillis(), ai.llm(Feature.JOURNAL).id))
        _progress.value = null
        return true
    }

    /** Measures from the transcript, then the AI's lessons and patterns. */
    private suspend fun review(d: LocalDate, convs: List<Conversation>, analyses: Map<Long, ConvAnalysis?>): DayReviewData {
        val (from, to) = journal.bounds(d)
        val lines = db.utterances().range(from, to).filter { it.conversationId in analyses.keys || convs.any { c -> c.id == it.conversationId } }
        // People: named speakers by talk time; "S1"-style labels are counted per conversation as unnamed voices.
        val byConv = lines.groupBy { it.conversationId }
        val people = HashMap<String, PersonDay>()
        var unnamed = 0
        for ((cid, ls) in byConv) {
            for (t in SpeechStats.bySpeaker(ls.map { SpeechStats.Line(it.id, it.speaker, it.text, it.startAt, it.endAt) })) {
                // Unnamed voices ("S1", "Other") are counted, not listed; the owner, songs and the assistant are neither.
                if (t.speaker == "me" || t.speaker == "Song" || t.speaker == "EchoMind" || t.speaker == "?") continue
                if (Regex("S\\d+").matches(t.speaker) || t.speaker.equals("other", true)) { if (t.words >= 5) unnamed++; continue }
                if (Memory.isJunkName(t.speaker)) continue
                val p = people[t.speaker]
                people[t.speaker] = PersonDay(t.speaker, (p?.seconds ?: 0) + t.seconds, (p?.words ?: 0) + t.words, (p?.conversations.orEmpty() + listOfNotNull(cid)).distinct(), true)
            }
        }
        // People the AI recognised by name in conversations (mentioned or present).
        for (c in convs) for (n in runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), c.people ?: "[]") }.getOrDefault(emptyList())) {
            val name = n.trim()
            if (name.length < 2 || name.equals("other", true) || Memory.isJunkName(name)) continue
            if (people.keys.none { it.equals(name, true) }) people[name] = PersonDay(name, 0, 0, listOf(c.id), false)
            else people.entries.first { it.key.equals(name, true) }.let { e -> people[e.key] = e.value.copy(conversations = (e.value.conversations + c.id).distinct()) }
        }
        val topicMap = LinkedHashMap<String, DayTopic>()
        for (c in convs) for (t in analyses[c.id]?.topics.orEmpty()) {
            val k = t.name.lowercase()
            val old = topicMap[k]
            topicMap[k] = DayTopic(old?.name ?: t.name, old?.detail?.takeIf { it.isNotBlank() } ?: t.detail, (old?.conversations.orEmpty() + c.id).distinct())
        }
        val speech = SpeechStats.combine(analyses.values.mapNotNull { it?.stats })
        val coaches = convs.mapNotNull { c -> analyses[c.id]?.coach?.let { c to it } }
        val base = DayReviewData(
            conversations = convs.size,
            people = people.values.sortedWith(compareByDescending<PersonDay> { it.seconds }.thenByDescending { it.conversations.size }),
            unnamedVoices = unnamed,
            topics = topicMap.values.toList(),
            decisions = analyses.values.flatMap { it?.decisions.orEmpty() }.distinct(),
            speech = speech,
            confidence = coaches.mapNotNull { it.second.confidence }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            clarity = coaches.mapNotNull { it.second.clarity }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            coachPoints = coaches.sumOf { it.second.count },
            reviewedAt = System.currentTimeMillis(),
        )
        if (!ai.ready(Feature.JOURNAL)) return base

        val lang = settings.value.notesLanguage
        val input = buildString {
            append("Day: ").append(Prompts.day(from)).append("\n\nConversations:\n")
            for (c in convs) {
                append("- ${Prompts.time(c.startAt)} ${c.title.orEmpty()}: ${c.summary.orEmpty().take(260)}")
                analyses[c.id]?.topics?.takeIf { it.isNotEmpty() }?.let { ts -> append(" Topics: ").append(ts.joinToString(", ") { it.name }) }
                append('\n')
            }
            if (base.people.isNotEmpty()) append("\nPeople: ").append(base.people.take(12).joinToString(", ") { "${it.name}${if (it.seconds > 0) " (${it.seconds / 60} min)" else ""}" }).append('\n')
            append("\nSpeaking: ${speech.words} words, ${speech.wpm} words/min, ${"%.1f".format(speech.fillerPer100)} fillers per 100 words, ${speech.restarts} restarts.\n")
            val points = coaches.flatMap { (_, k) -> k.englishFixes.map { "English: \"${it.said}\" → \"${it.better}\"" } + k.betterWays.map { "Better: \"${it.said}\" → \"${it.better}\"" } + k.nervous.map { "Nervous: ${it.sign}" } + k.slips.map { "Slip: ${it.problem}" } }
            if (points.isNotEmpty()) { append("\nCoach notes:\n"); points.take(24).forEach { append("- ").append(it.take(200)).append('\n') } }
        }.let { if (ai.llm(Feature.JOURNAL).local) it.take(8_000) else it }
        val r = runCatching {
            ai.json(LlmRequest(Feature.JOURNAL, "Reviewing ${Prompts.day(from)}", Deep.daySystem(lang), input, Deep.dayInsights, maxTokens = 1_100, temperature = 0.3, noThink = true))
        }.onFailure { Log.w(TAG, "day insights failed", it) }.getOrNull() ?: return base
        return base.copy(
            lessons = r.objects("lessons").mapNotNull { o -> o.str("title")?.trim()?.takeIf { it.isNotEmpty() }?.let { Lesson(it, twoSentences(o.str("detail")?.trim().orEmpty())) } }.take(4),
            patterns = r.strings("patterns").take(5),
            peopleNotes = r.objects("people_notes").mapNotNull { o -> o.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { PersonNote(it, o.str("note")?.trim().orEmpty()) } }.take(12),
            topics = base.topics.ifEmpty { r.objects("topics").mapNotNull { o -> o.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { DayTopic(it, o.str("detail")?.trim().orEmpty()) } } },
            followups = r.strings("followups").take(8),
            bestMoment = r.str("best_moment")?.trim()?.takeIf { it.isNotEmpty() },
            hardMoment = r.str("hard_moment")?.trim()?.takeIf { it.isNotEmpty() },
            // 0 means the model skipped it, not a score.
            communication = r.int("communication")?.takeIf { it >= 5 }?.coerceAtMost(100),
        )
    }

    /** Lessons read better short: the first two sentences. */
    private fun twoSentences(t: String): String {
        val parts = Regex("(?<=[.!?])\\s+").split(t)
        return if (parts.size <= 2) t else parts.take(2).joinToString(" ")
    }

    companion object {
        private const val TAG = "EchoReview"
        /** Bump when the journal or day review improves: days are reviewed again (conversations are not reread). */
        const val VERSION = 4
    }
}
