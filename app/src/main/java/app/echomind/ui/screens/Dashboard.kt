package app.echomind.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.echomind.App
import app.echomind.brain.Analytics
import app.echomind.brain.Analytics.DayPoint
import app.echomind.ui.DayNumbersRoute
import app.echomind.ui.DayReviewRoute
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.ChartLegend
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.LanguageCard
import app.echomind.ui.components.MetricChip
import app.echomind.ui.components.StackedColumns
import app.echomind.ui.components.langSlices
import app.echomind.ui.components.Pill
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.Tile
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

private enum class Span(val label: String) { WEEK("Week"), MONTH("Month"), YEAR("Year") }

private fun bounds(span: Span, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (span) {
    Span.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
    Span.MONTH -> anchor.withDayOfMonth(1).let { it to it.plusMonths(1).minusDays(1) }
    Span.YEAR -> anchor.withDayOfYear(1).let { it to it.plusYears(1).minusDays(1) }
}
private fun shift(span: Span, d: LocalDate, n: Long) = when (span) { Span.WEEK -> d.plusWeeks(n); Span.MONTH -> d.plusMonths(n); Span.YEAR -> d.plusYears(n) }
private fun hm(m: Int) = if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"

/** Your life in numbers: a report for the week, month or year, then the patterns behind it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen() {
    val g = App.graph
    val nav = LocalNav.current
    var spanName by rememberSaveable { mutableStateOf(Span.WEEK.name) }
    var anchorStr by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val span = Span.valueOf(spanName)
    val anchor = LocalDate.parse(anchorStr)
    val (from, to) = bounds(span, anchor)
    val today = LocalDate.now()
    val end = minOf(to, today)
    val prevFrom = shift(span, from, -1); val prevTo = from.minusDays(1)
    // Current period, the one before, and the last 30 days for "your usual".
    val data by produceState<Triple<List<DayPoint>, List<DayPoint>, List<DayPoint>>?>(null, spanName, anchorStr) {
        val decode = { j: String -> g.dayReview.decode(j) }
        val cur = Analytics.load(g.db, from, end, decode)
        val prev = Analytics.load(g.db, prevFrom, prevTo, decode)
        val usual = Analytics.load(g.db, end.minusDays(29), end, decode)
        value = Triple(cur, prev, usual)
    }
    val heat by produceState<Array<IntArray>?>(null, spanName, anchorStr) { value = Analytics.heatmap(Analytics.spans(g.db, from, end)) }
    val langs by produceState<Pair<app.echomind.brain.Languages.Day, Map<LocalDate, app.echomind.brain.Languages.Split>>?>(null, spanName, anchorStr) { value = Analytics.languages(g.db, from, end) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Dashboard", onBack = { nav.back() }) { IconButton(onClick = { nav.go(DayNumbersRoute) }) { Icon(Icons.Rounded.Today, "A day in numbers") } }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Sp.gutter)) {
            Span.entries.forEachIndexed { i, sp -> SegmentedButton(span == sp, { spanName = sp.name }, SegmentedButtonDefaults.itemShape(i, Span.entries.size)) { Text(sp.label) } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Sp.s), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { anchorStr = shift(span, anchor, -1).toString() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Earlier") }
            Text(
                when (span) {
                    Span.WEEK -> "${from.format(DateTimeFormatter.ofPattern("d MMM"))} – ${to.format(DateTimeFormatter.ofPattern("d MMM"))}"
                    Span.MONTH -> from.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                    Span.YEAR -> "${from.year}"
                }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(enabled = to < today, onClick = { anchorStr = shift(span, anchor, 1).toString() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Later") }
        }
        val d = data
        if (d == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }; return@Column }
        val (cur, prev, usual) = d
        val recorded = cur.filter { it.recorded }
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.m), state = app.echomind.ui.components.rememberKeptListState()) {
            if (recorded.isEmpty()) { item { EmptyState("Nothing recorded in this ${span.label.lowercase()}", "Turn on listening and your numbers fill in day by day.") }; return@LazyColumn }

            // 1. The report: a headline, the key numbers against last period, and what stood out.
            item {
                EchoCard(color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(Analytics.headline(cur, prev), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("${recorded.size} day${if (recorded.size == 1) "" else "s"} recorded · compared with the ${span.label.lowercase()} before", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(Sp.m))
                    val talk = Analytics.sum(cur) { it.talkMin }; val talkPrev = Analytics.sum(prev) { it.talkMin }
                    val ppl = cur.flatMap { it.people }.toSet().size; val pplPrev = prev.flatMap { it.people }.toSet().size
                    val done = Analytics.sum(cur) { it.tasksDone }; val donePrev = Analytics.sum(prev) { it.tasksDone }
                    val mood = Analytics.avg(cur) { it.mood }; val moodPrev = Analytics.avg(prev) { it.mood }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat(hm(talk), "talk", Analytics.deltaPct(talk.toDouble(), talkPrev.toDouble()))
                        Stat("$ppl", "people", Analytics.deltaPct(ppl.toDouble(), pplPrev.toDouble()))
                        Stat("$done", "tasks done", Analytics.deltaPct(done.toDouble(), donePrev.toDouble()))
                        Stat(mood?.let { "%+.1f".format(it) } ?: "–", "mood", null, moodDelta = if (mood != null && moodPrev != null) mood - moodPrev else null)
                    }
                    Analytics.noticed(usual.filter { !it.date.isBefore(from) } .ifEmpty { usual })?.let { n ->
                        Spacer(Modifier.height(Sp.m))
                        Text("Noticed: ${n.text}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = n.date?.let { dt -> Modifier.clickable { nav.go(DayReviewRoute(dt.toString())) } } ?: Modifier)
                    }
                }
            }
            // 2. Against your usual (30-day average per recorded day).
            item {
                val perDay = { l: List<DayPoint>, f: (DayPoint) -> Int -> l.filter { it.recorded }.map(f).takeIf { it.isNotEmpty() }?.average() }
                val sem = LocalSemantic.current
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    MetricChip(Icons.Rounded.RecordVoiceOver, perDay(cur) { it.talkMin }?.let { hm(it.toInt()) } ?: "–", "talk a day", Modifier.weight(1f), hint = usualHint(perDay(cur) { it.talkMin }, perDay(usual) { it.talkMin }))
                    MetricChip(Icons.Rounded.Groups, perDay(cur) { it.people.size }?.let { "%.1f".format(it) } ?: "–", "people a day", Modifier.weight(1f), tint = MaterialTheme.colorScheme.tertiary, hint = usualHint(perDay(cur) { it.people.size }, perDay(usual) { it.people.size }))
                    MetricChip(Icons.Rounded.Bolt, Analytics.avg(cur) { it.productivity }?.let { "${it.toInt()}" } ?: "–", "productivity", Modifier.weight(1f), tint = sem.warning, hint = usualHint(Analytics.avg(cur) { it.productivity }, Analytics.avg(usual) { it.productivity }))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.padding(top = Sp.s)) {
                    val made = Analytics.sum(cur) { it.tasksMade }; val done = Analytics.sum(cur) { it.tasksDone }
                    MetricChip(Icons.Rounded.TaskAlt, "$done / $made", "tasks done", Modifier.weight(1f), tint = sem.positive, hint = if (made > 0) "${done * 100 / made}% finished" else null)
                    MetricChip(Icons.Rounded.Forum, "${Analytics.sum(cur) { it.conversations }}", "conversations", Modifier.weight(1f), tint = MaterialTheme.colorScheme.secondary)
                    MetricChip(Icons.Rounded.Bedtime, hm(Analytics.sum(cur) { it.lateMin }), "talk after 11 PM", Modifier.weight(1f), tint = MaterialTheme.colorScheme.outline)
                }
            }

            if (span == Span.YEAR) {
                item { SectionHeader("Your year in pixels") }
                item { YearPixels(from.year, cur) { nav.go(DayReviewRoute(it.toString())) } }
                item { SectionHeader("Talk each month") }
                item { Bars(cur.groupBy { it.date.monthValue }.let { m -> (1..12).map { k -> (m[k]?.sumOf { it.talkMin } ?: 0).toDouble() } }, (1..12).map { java.time.Month.of(it).name.take(1) }) { hm(it.toInt()) } }
            } else {
                item { SectionHeader("Talk each day") }
                item { Bars(cur.map { it.talkMin.toDouble() }, cur.map { if (span == Span.WEEK) it.date.dayOfWeek.name.take(1) else if (it.date.dayOfMonth % 5 == 1) "${it.date.dayOfMonth}" else "" }, highlight = cur.indexOfFirst { it.date == today }) { hm(it.toInt()) } }
                item { SectionHeader("Mood and productivity") }
                item { Lines(cur.map { it.mood?.toDouble() }, cur.map { it.productivity?.let { p -> p / 50.0 - 1 } }) }
            }

            heat?.takeIf { h -> h.any { r -> r.any { it > 0 } } }?.let { h ->
                item { SectionHeader("When you talk") }
                item { Heatmap(h) }
            }
            langs?.takeIf { it.first.mine.total > 0 }?.let { (all, perDay) ->
                item { SectionHeader("Languages") }
                item { LanguageCard(all, "Your languages this ${span.label.lowercase()}") }
                if (span != Span.YEAR) item {
                    val lc = app.echomind.ui.components.langColors()
                    EchoCard {
                        Text("Language each day", style = MaterialTheme.typography.titleSmall)
                        StackedColumns(
                            cur.map { p -> langSlices(perDay[p.date] ?: app.echomind.brain.Languages.Split(), lc) },
                            cur.map { if (span == Span.WEEK) it.date.dayOfWeek.name.take(1) else if (it.date.dayOfMonth % 5 == 1) "${it.date.dayOfMonth}" else "" },
                            Modifier.padding(top = Sp.s), highlight = cur.indexOfFirst { it.date == today },
                        )
                        ChartLegend(langSlices(all.mine, lc), Modifier.padding(top = Sp.s))
                    }
                }
            }
            item { SectionHeader("By weekday") }
            item {
                val wd = Analytics.byWeekday(cur.ifEmpty { usual }) { it.talkMin.toDouble() }
                Bars(wd.map { it ?: 0.0 }, listOf("M", "T", "W", "T", "F", "S", "S")) { hm(it.toInt()) }
            }

            // 3. People and topics.
            val people = cur.flatMap { p -> p.people.map { it to p.date } }.groupBy({ it.first }, { it.second })
            val peoplePrev = prev.flatMap { it.people }.groupingBy { it }.eachCount()
            if (people.isNotEmpty()) {
                item { SectionHeader("Who you talked with") }
                item {
                    EchoCard {
                        people.entries.sortedByDescending { it.value.size }.take(8).forEach { (name, ds) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${ds.size} day${if (ds.size == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge)
                                val before = peoplePrev[name] ?: 0
                                Text(if (before == 0) "new" else if (ds.size > before) "▲" else if (ds.size < before) "▼" else "=", style = MaterialTheme.typography.labelMedium,
                                    color = if (before == 0 || ds.size > before) LocalSemantic.current.positive else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Sp.s).width(32.dp))
                            }
                        }
                    }
                }
            }
            val topics = cur.flatMap { it.topics }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(14)
            if (topics.isNotEmpty()) {
                item { SectionHeader("What you talked about") }
                item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { topics.forEach { (t, n) -> Pill(if (n > 1) "$t ×$n" else t) } } }
            }

            // 4. How you speak.
            if (cur.any { it.wpm != null || it.confidence != null }) {
                item { SectionHeader("How you speak") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                        Tile("words / min", Analytics.avg(cur) { it.wpm }?.let { "${it.toInt()}" } ?: "–", Modifier.weight(1f), hint = usualHint(Analytics.avg(cur) { it.wpm }, Analytics.avg(usual) { it.wpm }))
                        Tile("fillers / 100", Analytics.avg(cur) { it.fillers }?.let { "%.1f".format(it) } ?: "–", Modifier.weight(1f), hint = usualHint(Analytics.avg(cur) { it.fillers }, Analytics.avg(usual) { it.fillers }, lowerIsBetter = true))
                        Tile("confidence", Analytics.avg(cur) { it.confidence }?.let { "${it.toInt()}" } ?: "–", Modifier.weight(1f), hint = usualHint(Analytics.avg(cur) { it.confidence }, Analytics.avg(usual) { it.confidence }))
                    }
                }
            }

            // 5. Influences, with honest confidence.
            val inf = Analytics.influences(usual + cur.filter { p -> usual.none { it.date == p.date } })
            item { SectionHeader("What seems to affect your days", action = if (inf.any { it.confidence == Analytics.Confidence.LOW }) (if (showAll) "Hide weak" else "Show all") else null, onAction = { showAll = !showAll }) }
            val shown = inf.filter { showAll || it.confidence != Analytics.Confidence.LOW }.take(8)
            if (shown.isEmpty()) item { Text("Needs more days: patterns appear once there are at least three days with and without something.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            shown.forEach { i ->
                item {
                    EchoCard {
                        Text(Analytics.sentence(i), style = MaterialTheme.typography.bodyLarge)
                        Text("${i.confidence.name.lowercase().replaceFirstChar { it.uppercase() }} confidence · ${i.nWith} days with, ${i.nWithout} without", style = MaterialTheme.typography.labelSmall,
                            color = when (i.confidence) { Analytics.Confidence.HIGH -> LocalSemantic.current.positive; Analytics.Confidence.MEDIUM -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.outline })
                    }
                }
            }

            // 6. Streaks.
            item {
                val written = usual.filter { it.journal }.map { it.date }.toSet() + cur.filter { it.journal }.map { it.date }
                val rec = usual.filter { it.recorded }.map { it.date }.toSet() + recorded.map { it.date }
                SectionHeader("Streaks")
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Tile("diary streak", "${Analytics.streak(written)}", Modifier.weight(1f), hint = "best ${Analytics.longestStreak(written)}")
                    Tile("days recorded", "${Analytics.streak(rec)}", Modifier.weight(1f), hint = "in a row")
                    Tile("coach tips", "${Analytics.sum(cur) { it.coachPoints }}", Modifier.weight(1f), hint = "this ${span.label.lowercase()}")
                }
            }
        }
    }
}

private fun usualHint(now: Double?, usual: Double?, lowerIsBetter: Boolean = false): String? {
    if (now == null || usual == null || usual == 0.0) return null
    val pct = ((now - usual) / usual * 100).toInt()
    if (abs(pct) < 5) return "as usual"
    return "${if (pct > 0) "▲" else "▼"} ${abs(pct)}% vs usual"
}

@Composable
private fun Stat(value: String, label: String, deltaPct: Int?, moodDelta: Double? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        val (txt, up) = when {
            deltaPct != null && deltaPct != 0 -> "${if (deltaPct > 0) "▲" else "▼"} ${abs(deltaPct)}%" to (deltaPct > 0)
            moodDelta != null && abs(moodDelta) >= 0.05 -> "${if (moodDelta > 0) "▲" else "▼"} ${"%.1f".format(abs(moodDelta))}" to (moodDelta > 0)
            else -> null to false
        }
        txt?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (up) LocalSemantic.current.positive else MaterialTheme.colorScheme.error) }
    }
}

/** Simple bars with labels; the tallest bar's value is printed. */
@Composable
private fun Bars(values: List<Double>, labels: List<String>, highlight: Int = -1, fmt: (Double) -> String) {
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    EchoCard {
        Text("Most: ${fmt(max)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(96.dp).padding(top = Sp.s), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { i, v ->
                Box(Modifier.weight(1f).fillMaxHeight((v / max).toFloat().coerceIn(0.02f, 1f)).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(if (i == highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary.copy(alpha = if (v > 0) 0.8f else 0.15f)))
            }
        }
        Row(Modifier.fillMaxWidth()) { labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1) } }
    }
}

/** Mood (primary) and productivity (tertiary), both on a -1..1 scale. Gaps where a day has no value. */
@Composable
private fun Lines(a: List<Double?>, b: List<Double?>) {
    val ca = MaterialTheme.colorScheme.primary; val cb = MaterialTheme.colorScheme.tertiary; val grid = MaterialTheme.colorScheme.outlineVariant
    EchoCard {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1f)
            fun series(v: List<Double?>, c: Color) {
                if (v.size < 2) return
                val step = size.width / (v.size - 1)
                val path = Path(); var started = false
                v.forEachIndexed { i, x ->
                    if (x == null) { started = false; return@forEachIndexed }
                    val o = Offset(i * step, (size.height / 2 - (x.coerceIn(-1.0, 1.0) * size.height / 2.2)).toFloat())
                    if (!started) { path.moveTo(o.x, o.y); started = true } else path.lineTo(o.x, o.y)
                    drawCircle(c, 4f, o)
                }
                drawPath(path, c, style = Stroke(width = 4f))
            }
            series(b, cb); series(a, ca)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.l), modifier = Modifier.padding(top = Sp.s)) {
            Legend(ca, "Mood"); Legend(cb, "Productivity")
        }
    }
}

@Composable
private fun Legend(c: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(c)); Spacer(Modifier.width(4.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Weekday × hour of talk, six shades. */
@Composable
private fun Heatmap(h: Array<IntArray>) {
    val max = h.maxOf { r -> r.max() }.coerceAtLeast(1)
    val low = MaterialTheme.colorScheme.surfaceContainerHighest; val high = MaterialTheme.colorScheme.primary
    EchoCard {
        listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { d, label ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(14.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    (0..23).forEach { hr ->
                        val v = h[d][hr]
                        val q = if (v == 0) 0f else (kotlin.math.ceil(v * 5.0 / max) / 5.0).toFloat()
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(2.dp)).background(lerp(low, high, q)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 2.dp)) {
            listOf("12a", "6a", "12p", "6p").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f)) }
        }
    }
}

/** One square per day of the year, coloured by mood; tap a day to open its review. */
@Composable
private fun YearPixels(year: Int, points: List<DayPoint>, open: (LocalDate) -> Unit) {
    val byDate = points.associateBy { it.date }
    val bad = MaterialTheme.colorScheme.error; val neutral = MaterialTheme.colorScheme.secondary; val good = LocalSemantic.current.positive
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    EchoCard {
        (1..12).forEach { m ->
            val first = LocalDate.of(year, m, 1)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                Text(first.month.name.take(1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(14.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    (1..31).forEach { day ->
                        val d = if (day <= first.lengthOfMonth()) first.withDayOfMonth(day) else null
                        val p = d?.let { byDate[it] }
                        val c = when {
                            d == null -> Color.Transparent
                            p == null || !p.recorded -> empty
                            p.mood == null -> neutral.copy(alpha = 0.5f)
                            p.mood >= 0 -> lerp(neutral, good, p.mood.coerceIn(0f, 1f))
                            else -> lerp(neutral, bad, (-p.mood).coerceIn(0f, 1f))
                        }
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(1.dp)).background(c).then(if (p?.recorded == true) Modifier.clickable { open(d) } else Modifier))
                    }
                }
            }
        }
        Text("Each square is a day: green brighter, red heavier, grey not recorded.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Sp.s))
    }
}
