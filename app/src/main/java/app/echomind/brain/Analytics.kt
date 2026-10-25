package app.echomind.brain

import app.echomind.data.Db
import app.echomind.util.Js
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.builtins.serializer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The numbers behind the dashboard. Everything here is plain arithmetic over
 * one record per day, so it is exact, fast and testable; the loader at the
 * bottom is the only part that touches the database.
 */
object Analytics {
    data class DayPoint(
        val date: LocalDate,
        val talkMin: Int = 0,
        val myMin: Int = 0,
        val conversations: Int = 0,
        val people: Set<String> = emptySet(),
        val mood: Float? = null,
        val productivity: Int? = null,
        val tasksMade: Int = 0,
        val tasksDone: Int = 0,
        val lateMin: Int = 0,
        val wpm: Int? = null,
        val fillers: Float? = null,
        val confidence: Int? = null,
        val coachPoints: Int = 0,
        val journal: Boolean = false,
        val topics: List<String> = emptyList(),
        val categories: Map<String, Int> = emptyMap(),
    ) {
        val recorded get() = conversations > 0 || talkMin > 0
    }

    // ── Comparisons ──────────────────────────────────────────────

    /** Percent change from [before] to [now]; null when there is nothing to compare with. */
    fun deltaPct(now: Double, before: Double): Int? = if (before <= 0.0) null else (((now - before) / before) * 100).toInt()

    fun avg(points: List<DayPoint>, f: (DayPoint) -> Number?): Double? = points.mapNotNull { f(it)?.toDouble() }.takeIf { it.isNotEmpty() }?.average()

    fun sum(points: List<DayPoint>, f: (DayPoint) -> Int) = points.sumOf(f)

    // ── Influences ("happier on days you talk to X") ─────────────

    enum class Confidence { LOW, MEDIUM, HIGH }

    data class Influence(val label: String, val metric: String, val with: Double, val without: Double, val nWith: Int, val nWithout: Int, val confidence: Confidence, val lag: Int = 0) {
        val diff get() = with - without
    }

    /**
     * Compares [metric] on days where [has] is true against days where it isn't
     * (optionally on the following day, [lag] = 1). Needs at least 3 days of each.
     * Confidence grows with sample size and the size of the effect relative to the spread.
     */
    fun influence(points: List<DayPoint>, label: String, metricName: String, has: (DayPoint) -> Boolean, metric: (DayPoint) -> Double?, lag: Int = 0): Influence? {
        val byDate = points.associateBy { it.date }
        val withV = ArrayList<Double>(); val withoutV = ArrayList<Double>()
        for (p in points) {
            if (!p.recorded) continue
            val target = if (lag == 0) p else byDate[p.date.plusDays(lag.toLong())] ?: continue
            val v = metric(target) ?: continue
            if (has(p)) withV.add(v) else withoutV.add(v)
        }
        if (withV.size < 3 || withoutV.size < 3) return null
        val a = withV.average(); val b = withoutV.average()
        val all = withV + withoutV
        val mean = all.average()
        val sd = sqrt(all.sumOf { (it - mean) * (it - mean) } / all.size).takeIf { it > 1e-9 } ?: return null
        val effect = abs(a - b) / sd
        val conf = when {
            withV.size >= 10 && withoutV.size >= 10 && effect >= 0.5 -> Confidence.HIGH
            withV.size >= 5 && withoutV.size >= 5 && effect >= 0.35 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return Influence(label, metricName, a, b, withV.size, withoutV.size, conf, lag)
    }

    /** People, categories and late nights against mood and productivity, strongest first. */
    fun influences(points: List<DayPoint>, maxPeople: Int = 8): List<Influence> {
        val people = points.flatMap { it.people }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(maxPeople).map { it.key }
        val cats = points.flatMap { it.categories.keys }.distinct().filter { it != "other" && it != "media" }
        val metrics = listOf<Pair<String, (DayPoint) -> Double?>>("mood" to { p -> p.mood?.toDouble() }, "productivity" to { p -> p.productivity?.toDouble() })
        val out = ArrayList<Influence>()
        for ((m, f) in metrics) {
            for (who in people) influence(points, "talking with $who", m, { who in it.people }, f)?.let(out::add)
            for (c in cats) influence(points, "$c conversations", m, { (it.categories[c] ?: 0) > 0 }, f)?.let(out::add)
            influence(points, "talking late at night", m, { it.lateMin >= 15 }, f, lag = 1)?.let(out::add)
        }
        return out.sortedWith(compareByDescending<Influence> { it.confidence.ordinal }.thenByDescending { abs(it.diff) / (if (it.metric == "mood") 1.0 else 50.0) })
    }

    /** One plain sentence for an influence. */
    fun sentence(i: Influence): String {
        val up = i.diff > 0
        val amount = if (i.metric == "mood") "%+.1f mood".format(i.diff) else "%+d productivity".format(i.diff.toInt())
        val day = if (i.lag == 1) "the day after " else "on days of "
        val what = if (i.metric == "mood") (if (up) "happier" else "lower in mood") else (if (up) "more productive" else "less productive")
        return "You were $what $day${i.label} ($amount)"
    }

    // ── Patterns ─────────────────────────────────────────────────

    /** Minutes of talk per weekday (0 = Monday) and hour, from line spans. */
    fun heatmap(spans: List<Pair<Long, Long>>, zone: ZoneId = ZoneId.systemDefault()): Array<IntArray> {
        val grid = Array(7) { IntArray(24) }
        val seconds = Array(7) { LongArray(24) }
        for ((a, b) in spans) {
            val t = Instant.ofEpochMilli(a).atZone(zone)
            seconds[t.dayOfWeek.value - 1][t.hour] += ((b - a) / 1000).coerceIn(0, 120)
        }
        for (d in 0..6) for (h in 0..23) grid[d][h] = (seconds[d][h] / 60).toInt()
        return grid
    }

    /** Averages per weekday (0 = Monday), over days that were recorded. */
    fun byWeekday(points: List<DayPoint>, f: (DayPoint) -> Double?): List<Double?> =
        (1..7).map { dow -> points.filter { it.recorded && it.date.dayOfWeek.value == dow }.mapNotNull(f).takeIf { it.isNotEmpty() }?.average() }

    /** Consecutive days up to [today] (or yesterday, if today isn't done yet) where [test] holds. */
    fun streak(dates: Set<LocalDate>, today: LocalDate = LocalDate.now()): Int {
        var d = if (today in dates) today else today.minusDays(1)
        var n = 0
        while (d in dates) { n++; d = d.minusDays(1) }
        return n
    }

    fun longestStreak(dates: Set<LocalDate>): Int {
        var best = 0
        for (d in dates) if (d.minusDays(1) !in dates) {
            var n = 0; var x = d
            while (x in dates) { n++; x = x.plusDays(1) }
            best = maxOf(best, n)
        }
        return best
    }

    // ── Words ────────────────────────────────────────────────────

    /** The period in a few words: how much talk and how productive, against the period before. */
    fun headline(cur: List<DayPoint>, prev: List<DayPoint>): String {
        if (cur.none { it.recorded }) return "A quiet stretch"
        val talk = deltaPct(sum(cur) { it.talkMin }.toDouble(), sum(prev) { it.talkMin }.toDouble())
        val prod = avg(cur) { it.productivity }
        val mood = avg(cur) { it.mood }
        val social = when { talk == null -> null; talk >= 25 -> "talkative"; talk <= -25 -> "quieter"; else -> "steady" }
        val work = when { prod == null -> null; prod >= 65 -> "productive"; prod <= 35 -> "slow"; else -> null }
        val feel = when { mood == null -> null; mood >= 0.3 -> "bright"; mood <= -0.3 -> "heavy"; else -> null }
        val words = listOfNotNull(social, work, feel).distinct()
        return if (words.isEmpty()) "A balanced stretch" else "A " + when (words.size) { 1 -> words[0]; 2 -> "${words[0]}, ${words[1]}"; else -> "${words[0]}, ${words[1]} and ${words[2]}" } + " stretch"
    }

    data class Noticed(val text: String, val date: LocalDate?)

    /** The most unusual recent day, against the average of the others (the biggest z-score wins). */
    fun noticed(points: List<DayPoint>): Noticed? {
        val rec = points.filter { it.recorded }
        if (rec.size < 4) return null
        val checks = listOf<Triple<String, (DayPoint) -> Double?, (DayPoint, Double) -> String>>(
            Triple("talk", { it.talkMin.toDouble() }) { p, avg -> "${p.date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}: ${p.talkMin} min of talk, ${if (p.talkMin > avg) "%.1f× your usual".format(p.talkMin / avg.coerceAtLeast(1.0)) else "much less than usual"}" },
            Triple("people", { it.people.size.toDouble() }) { p, avg -> "${p.date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}: you talked with ${p.people.size} people (usually ${"%.0f".format(avg)})" },
            Triple("mood", { it.mood?.toDouble() }) { p, _ -> "${p.date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} was ${if ((p.mood ?: 0f) > 0) "your brightest" else "your heaviest"} day" },
        )
        var best: Pair<Double, Noticed>? = null
        for ((_, f, say) in checks) {
            val vals = rec.mapNotNull { p -> f(p)?.let { p to it } }
            if (vals.size < 4) continue
            val mean = vals.map { it.second }.average()
            val sd = sqrt(vals.sumOf { (it.second - mean) * (it.second - mean) } / vals.size).takeIf { it > 1e-9 } ?: continue
            for ((p, v) in vals.takeLast(7)) {
                val z = abs(v - mean) / sd
                if (z >= 1.5 && (best == null || z > best.first)) best = z to Noticed(say(p, mean), p.date)
            }
        }
        return best?.second
    }

    // ── Loading ──────────────────────────────────────────────────

    private val zone get() = ZoneId.systemDefault()

    /** One point per day in [from, to], from journals, day reviews and conversations. */
    suspend fun load(db: Db, from: LocalDate, to: LocalDate, decodeReview: (String) -> DayReviewData?): List<DayPoint> {
        val days = db.days().since(from.toString()).filter { it.day <= to.toString() }.associateBy { it.day }
        val reviews = db.analyses().many("day", generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { it.toString() }.toList())
            .associate { it.ref to decodeReview(it.json) }
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli(); val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val convs = db.conversations().inRangeOnce(start, end).filter { it.state == "done" }
        val convByDay = convs.groupBy { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() }
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
            val e = days[d.toString()]
            val m = e?.metrics?.let { runCatching { Js.json.decodeFromString(DayMetrics.serializer(), it) }.getOrNull() }
            val r = reviews[d.toString()]
            val cs = convByDay[d].orEmpty()
            val named = r?.people?.filter { it.named }?.map { it.name }?.toSet()
                ?: cs.flatMap { c -> runCatching { Js.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(String.serializer()), c.people ?: "[]") }.getOrDefault(emptyList()) }
                    .filter { it.length >= 2 && !Memory.isJunkName(it) && !it.equals("other", true) }.toSet()
            DayPoint(
                date = d,
                talkMin = m?.talkMinutes ?: 0,
                myMin = (r?.speech?.seconds ?: 0) / 60,
                conversations = m?.conversations ?: cs.size,
                people = named,
                mood = e?.mood ?: m?.mood,
                productivity = e?.productivity,
                tasksMade = m?.tasksMade ?: 0,
                tasksDone = m?.tasksDone ?: 0,
                lateMin = m?.lateNightMinutes ?: 0,
                wpm = r?.speech?.wpm?.takeIf { it > 0 },
                fillers = r?.speech?.fillerPer100?.takeIf { (r.speech.words) > 0 },
                confidence = r?.confidence,
                coachPoints = r?.coachPoints ?: 0,
                journal = !e?.diary.isNullOrBlank(),
                topics = r?.topics?.map { it.name }.orEmpty(),
                categories = m?.categories ?: cs.groupingBy { it.category ?: "other" }.eachCount(),
            )
        }.toList()
    }

    /** Line spans for the heatmap (no text loaded). */
    suspend fun spans(db: Db, from: LocalDate, to: LocalDate): List<Pair<Long, Long>> {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli(); val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return db.utterances().range(start, end).filter { it.speaker != "Song" && it.speaker != "EchoMind" }.map { it.startAt to it.endAt }
    }

    /** Languages over [from]..[to]: the whole period, and your own seconds per language each day. */
    suspend fun languages(db: Db, from: LocalDate, to: LocalDate): Pair<Languages.Day, Map<LocalDate, Languages.Split>> {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli(); val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val lines = db.utterances().range(start, end).map { Languages.Line(it.speaker, it.text, it.startAt, it.endAt, it.conversationId) }
        val perDay = lines.groupBy { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() }.mapValues { (_, ls) -> Languages.day(ls, zone).mine }
        return Languages.day(lines, zone) to perDay
    }
}
