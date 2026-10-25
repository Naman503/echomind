package app.echomind.ui.screens

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.Coaching
import app.echomind.brain.ConvAnalysis
import app.echomind.brain.DayReviewData
import app.echomind.brain.Fix
import app.echomind.core.Settings
import app.echomind.data.Conversation
import app.echomind.data.TaskRow
import app.echomind.ui.CoachRoute
import app.echomind.ui.ConversationRoute
import app.echomind.ui.DayReviewRoute
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.PersonRoute
import app.echomind.ui.ReviewSettingsRoute
import app.echomind.ui.TaskRoute
import app.echomind.ui.TopicsRoute
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.Pill
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.SettingRow
import app.echomind.ui.components.SwitchRow
import app.echomind.ui.components.Tile
import app.echomind.ui.components.NavGroup
import app.echomind.ui.components.NavRow
import app.echomind.ui.components.TabHeader
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import app.echomind.util.Js
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate
import java.time.ZoneId

private val zoneR get() = ZoneId.systemDefault()
private fun dayBounds(d: LocalDate) = d.atStartOfDay(zoneR).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(zoneR).toInstant().toEpochMilli()
private fun strList(json: String?): List<String> = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), json ?: "[]") }.getOrDefault(emptyList())

@Composable
private fun ReviewPage(title: String, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}, content: LazyListScope.() -> Unit) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar(title, onBack = { nav.back() }, actions = actions)
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s), content = content)
    }
}

// ───────────────────────────── Insights hub ─────────────────────────────

/** One place for everything EchoMind learns from your days. */
@Composable
fun InsightsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val today = LocalDate.now()
    val reviews by remember { g.db.analyses().observeDays(today.minusDays(13).toString(), today.toString()) }.collectAsStateWithLifecycle(emptyList())
    val days by remember { g.db.days().observeRecent(14) }.collectAsStateWithLifecycle(emptyList())
    val chapters by remember { g.db.analyses().observeKind("chapter") }.collectAsStateWithLifecycle(emptyList())
    val progress by g.dayReview.progress.collectAsStateWithLifecycle()
    val decoded = reviews.mapNotNull { a -> g.dayReview.decode(a.json)?.let { LocalDate.parse(a.ref) to it } }
    val weekStart = today.minusDays(6)
    fun inWeek(d: LocalDate, back: Long) = !d.isBefore(weekStart.minusDays(7 * back)) && !d.isAfter(today.minusDays(7 * back))
    fun talk(back: Long) = days.filter { inWeek(LocalDate.parse(it.day), back) }.sumOf { e -> runCatching { app.echomind.util.Js.json.decodeFromString(app.echomind.brain.DayMetrics.serializer(), e.metrics ?: "{}").talkMinutes }.getOrDefault(0) }
    val people = decoded.filter { inWeek(it.first, 0) }.flatMap { it.second.people.filter { p -> p.named }.map { p -> p.name.lowercase() } }.toSet().size
    val journals = days.count { inWeek(LocalDate.parse(it.day), 0) && !it.diary.isNullOrBlank() }
    val coach = decoded.filter { inWeek(it.first, 0) }.sumOf { it.second.coachPoints }
    val latest = days.firstOrNull { !it.diary.isNullOrBlank() }

    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(Sp.m)) {
        item { TabHeader("Insights", "What your days are telling you") { IconButton(onClick = { nav.go(ReviewSettingsRoute) }) { Icon(Icons.Rounded.Settings, "Journal and review settings") } } }
        // This week at a glance, against last week.
        item {
            val now = talk(0); val before = talk(1)
            EchoCard(color = MaterialTheme.colorScheme.primaryContainer, onClick = { nav.go(app.echomind.ui.DashboardRoute) }) {
                Text("This week", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Row(Modifier.fillMaxWidth().padding(top = Sp.s), horizontalArrangement = Arrangement.SpaceBetween) {
                    Glance("${now / 60}h ${now % 60}m", "talk", if (before > 0) ((now - before) * 100 / before) else null)
                    Glance("$people", "people", null)
                    Glance("$journals/7", "diary days", null)
                    Glance("$coach", "coach tips", null)
                }
                val p = progress
                if (p != null) {
                    Text("Night review: ${p.label}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Sp.s))
                    LinearProgressIndicator(progress = { p.done.toFloat() / p.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                } else Text("Open the dashboard for trends, people and patterns", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(top = Sp.s))
            }
        }
        latest?.let { e -> item { app.echomind.ui.components.DiaryPage(e.diary.orEmpty(), e.headline, LocalDate.parse(e.day), maxLines = 5, onClick = { nav.go(DayReviewRoute(e.day)) }) } }
        item {
            SectionHeader("Reflect")
            NavGroup {
                NavRow(Icons.Rounded.Today, "Day review", "People, topics, coach and lessons for any day") { nav.go(DayReviewRoute((latest?.day ?: today.minusDays(1).toString()))) }
                NavRow(Icons.Rounded.AutoStories, "Your book", "Each week becomes a chapter", trailing = chapters.size.takeIf { it > 0 }?.let { "$it" }) { nav.go(app.echomind.ui.BookRoute) }
                NavRow(Icons.Rounded.Bedtime, "Journal", "Every diary entry, newest first", trailing = days.count { !it.diary.isNullOrBlank() }.takeIf { it > 0 }?.toString()) { nav.go(app.echomind.ui.JournalRoute) }
            }
        }
        item {
            SectionHeader("Understand")
            NavGroup {
                NavRow(Icons.Rounded.Insights, "Dashboard", "Trends, people, patterns, your year", tint = MaterialTheme.colorScheme.tertiary) { nav.go(app.echomind.ui.DashboardRoute) }
                NavRow(Icons.Rounded.RecordVoiceOver, "Speaking coach", "English, nervous moments, better ways", tint = MaterialTheme.colorScheme.tertiary) { nav.go(CoachRoute) }
                NavRow(Icons.Rounded.Tag, "Topics", "What you talk about", tint = MaterialTheme.colorScheme.tertiary) { nav.go(TopicsRoute) }
                NavRow(Icons.Rounded.Map, "Places & map", "Where you went and what happened there", tint = MaterialTheme.colorScheme.tertiary) { nav.go(app.echomind.ui.MapRoute()) }
                NavRow(Icons.Rounded.Flag, "Plans", "Today, this week, this month", tint = MaterialTheme.colorScheme.tertiary) { nav.go(app.echomind.ui.PlanRoute) }
            }
        }
    }
}

@Composable
private fun Glance(value: String, label: String, deltaPct: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        deltaPct?.takeIf { it != 0 }?.let { Text("${if (it > 0) "▲" else "▼"} ${kotlin.math.abs(it)}%", style = MaterialTheme.typography.labelSmall, color = if (it > 0) LocalSemantic.current.positive else MaterialTheme.colorScheme.error) }
    }
}

private fun LazyListScope.hubRow(vararg tiles: Pair<Triple<ImageVector, String, String>, () -> Unit>) {
    item(key = "hub" + tiles.first().first.second) {
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
            tiles.forEach { (t, go) ->
                Surface(onClick = go, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(Sp.l)) {
                        Icon(t.first, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(Sp.s))
                        Text(t.second, style = MaterialTheme.typography.titleSmall)
                        Text(t.third, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun hm(minute: Int): String {
    val h = (minute / 60) % 24; val m = minute % 60
    return "${if (h % 12 == 0) 12 else h % 12}:${"%02d".format(m)} ${if (h < 12) "AM" else "PM"}"
}

// ───────────────────────────── Day review ─────────────────────────────

private val sections = listOf(
    "overview" to "Overview", "people" to "People", "topics" to "Topics", "coach" to "Speaking coach",
    "lessons" to "Lessons", "commitments" to "Follow-ups", "conversations" to "Conversations",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayReviewScreen(day: String) {
    val g = App.graph
    val nav = LocalNav.current
    var date by rememberSaveable { mutableStateOf(day) }
    val d = LocalDate.parse(date)
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val entry by remember(date) { g.db.days().observe(date) }.collectAsStateWithLifecycle(null)
    val reviewRow by remember(date) { g.db.analyses().observe("day", date) }.collectAsStateWithLifecycle(null)
    val review = reviewRow?.let { g.dayReview.decode(it.json) }
    val progress by g.dayReview.progress.collectAsStateWithLifecycle()
    val (from, to) = dayBounds(d)
    val convsAll by remember(date) { g.db.conversations().inRange(from, to) }.collectAsStateWithLifecycle(emptyList())
    val convs = convsAll.filter { it.state == "done" && !it.ignored }.sortedBy { it.startAt }
    val analysesRows by remember(convs.map { it.id }) { g.db.analyses().observeMany("conv", convs.map { it.id.toString() }) }.collectAsStateWithLifecycle(emptyList())
    val analyses = analysesRows.associate { it.ref.toLong() to g.analyzer.decode(it.json) }
    val entities by remember { g.db.memory().observeEntities() }.collectAsStateWithLifecycle(emptyList())
    val tasksMade by produceState(emptyList<TaskRow>(), date, convs.size) { value = g.db.tasks().createdIn(from, to).filter { it.status != "dismissed" } }
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    fun show(k: String) = (focus == null || focus == k) && k !in s.reviewHidden

    ReviewPage(Fmt.day(d).let { if (it == "Today" || it == "Yesterday") it else Fmt.dayLong(d) }, actions = {
        IconButton(onClick = { date = d.minusDays(1).toString() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous day") }
        IconButton(enabled = d < LocalDate.now(), onClick = { date = d.plusDays(1).toString() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next day") }
        IconButton(onClick = { nav.go(ReviewSettingsRoute) }) { Icon(Icons.Rounded.Settings, "Review settings") }
    }) {
        item(key = "chips") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                item { FilterChip(focus == null, { focus = null }, label = { Text("All") }) }
                items(sections.filter { it.first !in s.reviewHidden }, key = { it.first }) { (k, label) -> FilterChip(focus == k, { focus = if (focus == k) null else k }, label = { Text(label) }) }
            }
        }
        // Status: reviewed, working, or waiting for tonight.
        item(key = "status") {
            val p = progress?.takeIf { it.day == date }
            EchoCard(color = MaterialTheme.colorScheme.surfaceContainer) {
                when {
                    p != null -> {
                        Text("Reviewing now · step ${p.done + 1} of ${p.total}", style = MaterialTheme.typography.titleSmall)
                        Text(p.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { p.done.toFloat() / p.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().padding(top = Sp.s))
                    }
                    convs.isEmpty() -> Text("No conversations were read on this day.", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        val stale = review == null || review.conversations < convs.size
                        Text(
                            if (review == null) "Not reviewed yet" else if (stale) "${convs.size - review.conversations} new conversation(s) since the review" else "Reviewed ${Fmt.dayTime(review.reviewedAt)}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            if (stale) (if (s.nightReview) "It runs by itself tonight after ${hm(s.nightReviewAt)}${if (s.nightNeedsCharging) " while charging" else ""}. It's slow and careful on the phone: a few minutes per conversation." else "Night review is off. You can still run it now.")
                            else "${review!!.conversations} conversations read carefully, coached and summed up.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.padding(top = Sp.s)) {
                            if (stale) Button(onClick = { g.capture.processNow(); g.brain.reviewNow(d) }) { Text("Review now") }
                            else OutlinedButton(onClick = {
                                g.scope.launch { g.db.analyses().delete("day", date); g.capture.processNow(); g.brain.reviewNow(d) }
                            }) { Text("Review again") }
                        }
                    }
                }
            }
        }
        if (show("overview")) overview(entry, review, convs)
        if (show("overview")) item(key = "where") {
            val notes by produceState<String?>(null, date) { value = runCatching { g.places.dayNotes(d) }.getOrNull() }
            notes?.let { n ->
                EchoCard(onClick = { nav.go(app.echomind.ui.MapRoute(date)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Where and weather", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("Map", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    n.lines().forEach { l ->
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 6.dp)) {
                            Icon(if (l.startsWith("Weather")) androidx.compose.material.icons.Icons.Rounded.WbCloudy else androidx.compose.material.icons.Icons.Rounded.Route, null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                            Spacer(Modifier.width(Sp.s))
                            Text(l.removePrefix("Places: ").removePrefix("Weather: "), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        if (review != null && show("people")) peopleSection(review, entities) { id -> nav.go(PersonRoute(id)) }
        if (review != null && show("topics")) topicsSection(review, convs) { nav.go(ConversationRoute(it)) }
        if (show("coach")) coachSection(convs, analyses, review, s) { cid, line -> nav.go(ConversationRoute(cid, line)) }
        if (review != null && show("lessons")) lessonsSection(review)
        if (show("commitments")) commitmentsSection(entry, review, tasksMade) { nav.go(TaskRoute(it)) }
        if (show("conversations")) {
            item(key = "conv_h") { SectionHeader("Conversations · ${convs.size}") }
            items(convs, key = { "c${it.id}" }) { c -> ConversationRow(c, analyses[c.id]) { nav.go(ConversationRoute(c.id)) } }
        }
    }
}

private fun LazyListScope.overview(entry: app.echomind.data.DayEntry?, review: DayReviewData?, convs: List<Conversation>) {
    item(key = "ov") {
        var expanded by remember { mutableStateOf(false) }
        val diary = entry?.diary?.takeIf { it.isNotBlank() }
        if (diary != null) app.echomind.ui.components.DiaryPage(diary, entry.headline, LocalDate.parse(entry.day), maxLines = if (expanded) Int.MAX_VALUE else 8, onClick = { expanded = !expanded })
        EchoCard(modifier = Modifier.padding(top = if (diary != null) Sp.s else 0.dp)) {
            if (diary == null) Text(entry?.headline?.takeIf { it.isNotBlank() } ?: "No journal yet", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.padding(top = Sp.s)) {
                entry?.productivity?.let { Pill("Productivity $it") }
                entry?.rating?.let { Pill("Your rating $it/5") }
            }
            entry?.productiveWhy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
    val sp = review?.speech
    item(key = "tiles1") {
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
            Tile("conversations", "${convs.size}", Modifier.weight(1f))
            Tile("people", "${review?.people?.count { it.named } ?: 0}", Modifier.weight(1f), hint = review?.unnamedVoices?.takeIf { it > 0 }?.let { "+$it unnamed voices" })
            Tile("you spoke", sp?.let { "${it.seconds / 60} min" } ?: "–", Modifier.weight(1f), hint = sp?.let { "${it.words} words" })
        }
    }
    if (sp != null && sp.words > 0) item(key = "tiles2") {
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
            Tile("words / min", if (sp.wpm > 0) "${sp.wpm}" else "–", Modifier.weight(1f), hint = when { sp.wpm == 0 -> null; sp.wpm > 170 -> "fast"; sp.wpm < 100 -> "slow"; else -> "comfortable" })
            Tile("fillers / 100 words", "%.1f".format(sp.fillerPer100), Modifier.weight(1f), hint = if (sp.fillerPer100 > 5) "high" else if (sp.fillerPer100 > 2) "some" else "low")
            Tile("restarts", "${sp.restarts}", Modifier.weight(1f), hint = "${(sp.hindiShare * 100).toInt()}% Hindi")
        }
    }
    if (review != null && (review.confidence != null || review.clarity != null || review.communication != null)) item(key = "scores") {
        EchoCard {
            review.confidence?.let { Score("Confidence", it) }
            review.clarity?.let { Score("Clarity", it) }
            review.communication?.let { Score("Communication", it) }
        }
    }
}

@Composable
private fun Score(label: String, v: Int) {
    val c = when { v >= 70 -> LocalSemantic.current.positive; v >= 45 -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.error }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
        LinearProgressIndicator(progress = { v / 100f }, color = c, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.weight(1f).height(8.dp))
        Text("$v", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(36.dp).padding(start = Sp.s))
    }
}

private fun LazyListScope.peopleSection(r: DayReviewData, entities: List<app.echomind.data.EntityRow>, open: (Long) -> Unit) {
    val named = r.people
    item(key = "ppl_h") { SectionHeader("People · ${named.count { it.named }} you talked with" + if (named.any { !it.named }) " · ${named.count { !it.named }} mentioned" else "") }
    if (named.isEmpty()) item { Text("No names came up. Name voices in a conversation and they'll be counted here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(named.take(30), key = { "p" + it.name }) { p ->
        val e = entities.firstOrNull { it.kind == "person" && it.name.equals(p.name, true) }
        val note = r.peopleNotes.firstOrNull { it.name.equals(p.name, true) }?.note
        EchoCard(onClick = e?.let { { open(it.id) } }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Text(p.name.take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(Modifier.width(Sp.m))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            if (p.named && p.seconds > 0) (if (p.seconds < 60) "<1 min of talk" else "${p.seconds / 60} min of talk") else "Mentioned",
                            "${p.conversations.size} conversation${if (p.conversations.size == 1) "" else "s"}",
                            e?.relationToMe?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Sp.s)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.topicsSection(r: DayReviewData, convs: List<Conversation>, open: (Long) -> Unit) {
    item(key = "tp_h") { SectionHeader("Topics · ${r.topics.size}") }
    if (r.topics.isEmpty()) item { Text("No topics yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(r.topics.take(25), key = { "t" + it.name }) { t ->
        EchoCard {
            Text(t.name, style = MaterialTheme.typography.titleSmall)
            if (t.detail.isNotBlank()) Text(t.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                t.conversations.mapNotNull { id -> convs.firstOrNull { it.id == id } }.forEach { c ->
                    Text(Fmt.time(c.startAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { open(c.id) }.padding(vertical = 2.dp))
                }
            }
        }
    }
    if (r.decisions.isNotEmpty()) {
        item(key = "dec_h") { SectionHeader("Decisions") }
        items(r.decisions.take(12), key = { "d$it" }) { Bullet(it) }
    }
}

@Composable
private fun Bullet(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** The coach's points for a set of conversations, with links back to the line. */
@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.coachSection(convs: List<Conversation>, analyses: Map<Long, ConvAnalysis?>, review: DayReviewData?, s: Settings, open: (Long, Long) -> Unit) {
    val coached = convs.mapNotNull { c -> analyses[c.id]?.coach?.let { c to it } }
    val english = coached.flatMap { (c, k) -> k.englishFixes.map { c to it } }
    val better = coached.flatMap { (c, k) -> k.betterWays.map { c to it } }
    val nervous = coached.flatMap { (c, k) -> k.nervous.map { c to it } }
    val slips = coached.flatMap { (c, k) -> k.slips.map { c to it } }
    val strengths = coached.flatMap { it.second.strengths }.distinct().take(4)
    item(key = "co_h") { SectionHeader("Speaking coach") }
    if (coached.isEmpty()) {
        item { Text("The coach reads your own lines at night: English mistakes, better ways to say things, nervous moments and slips.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    if (strengths.isNotEmpty()) item(key = "co_s") {
        EchoCard(color = LocalSemantic.current.positive.copy(alpha = 0.12f)) {
            Text("What went well", style = MaterialTheme.typography.titleSmall)
            strengths.forEach { Bullet(it) }
        }
    }
    review?.speech?.fillers?.takeIf { it.isNotEmpty() }?.let { f ->
        item(key = "co_f") {
            EchoCard {
                Text("Filler words", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    f.entries.take(10).forEach { (w, n) -> Pill("$w ×$n") }
                }
            }
        }
    }
    if (english.isNotEmpty() && s.coachEnglish) {
        item(key = "co_e") { SectionHeader("English · ${english.size}") }
        items(english, key = { "e${it.second.line}${it.second.said.hashCode()}" }) { (c, f) -> FixCard(f, c) { open(c.id, f.line) } }
    }
    if (better.isNotEmpty() && s.coachBetter) {
        item(key = "co_b") { SectionHeader("Better ways to say it · ${better.size}") }
        items(better, key = { "b${it.second.line}${it.second.said.hashCode()}" }) { (c, f) -> FixCard(f, c) { open(c.id, f.line) } }
    }
    if (nervous.isNotEmpty() && s.coachNervous) {
        item(key = "co_n") { SectionHeader("Where you sounded nervous · ${nervous.size}") }
        items(nervous, key = { "n${it.second.line}" }) { (c, n) ->
            EchoCard(onClick = { open(c.id, n.line) }) {
                Text("“${n.quote}”", style = MaterialTheme.typography.bodyMedium)
                Text(n.sign, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp))
                if (n.tip.isNotBlank()) Text("Next time: ${n.tip}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${Fmt.time(n.at)} · ${c.title.orEmpty()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (slips.isNotEmpty() && s.coachSlips) {
        item(key = "co_sl") { SectionHeader("Things you said that weren't right · ${slips.size}") }
        items(slips, key = { "s${it.second.line}" }) { (c, sl) ->
            EchoCard(onClick = { open(c.id, sl.line) }) {
                Text("“${sl.said}”", style = MaterialTheme.typography.bodyMedium)
                Text(sl.problem, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                if (sl.fix.isNotBlank()) Text("Better: ${sl.fix}", style = MaterialTheme.typography.bodyMedium, color = LocalSemantic.current.positive)
                Text("${Fmt.time(sl.at)} · ${c.title.orEmpty()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FixCard(f: Fix, c: Conversation?, onClick: () -> Unit) {
    EchoCard(onClick = onClick) {
        Text(f.said, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textDecoration = TextDecoration.LineThrough)
        Text(f.better, style = MaterialTheme.typography.bodyLarge, color = LocalSemantic.current.positive, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
        if (f.why.isNotBlank()) Text(f.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        Text("${Fmt.time(f.at)}${c?.title?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun LazyListScope.lessonsSection(r: DayReviewData) {
    if (r.lessons.isEmpty() && r.patterns.isEmpty() && r.bestMoment == null && r.hardMoment == null) return
    item(key = "le_h") { SectionHeader("Lessons from today") }
    items(r.lessons, key = { "l" + it.title }) { l ->
        EchoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lightbulb, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Sp.s))
                Text(l.title, style = MaterialTheme.typography.titleSmall)
            }
            if (l.detail.isNotBlank()) Text(l.detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (r.patterns.isNotEmpty()) item(key = "pat") {
        EchoCard {
            Text("Patterns", style = MaterialTheme.typography.titleSmall)
            r.patterns.forEach { Bullet(it) }
        }
    }
    if (r.bestMoment != null || r.hardMoment != null) item(key = "mom") {
        EchoCard {
            r.bestMoment?.let { Text("Best moment", style = MaterialTheme.typography.labelMedium, color = LocalSemantic.current.positive); Text(it, style = MaterialTheme.typography.bodyMedium) }
            r.hardMoment?.let { Text("Hardest moment", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Sp.s)); Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private fun LazyListScope.commitmentsSection(entry: app.echomind.data.DayEntry?, r: DayReviewData?, tasks: List<TaskRow>, open: (Long) -> Unit) {
    item(key = "cm_h") { SectionHeader("Tasks, follow-ups and loose ends") }
    if (tasks.isNotEmpty()) items(tasks, key = { "tk${it.id}" }) { t ->
        Row(Modifier.fillMaxWidth().clickable { open(t.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.TaskAlt, null, tint = if (t.status == "done") LocalSemantic.current.positive else MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Sp.s))
            Text(t.title, style = MaterialTheme.typography.bodyMedium, textDecoration = if (t.status == "done") TextDecoration.LineThrough else null, modifier = Modifier.weight(1f))
            t.remindAt?.let { Text(Fmt.dayTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        }
    } else item { Text("No new tasks on this day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    val lists = listOf(
        "Follow up" to r?.followups.orEmpty(),
        "Loose ends" to strList(entry?.looseEnds),
        "Tomorrow" to strList(entry?.tomorrow),
    ).filter { it.second.isNotEmpty() }
    lists.forEach { (title, l) ->
        item(key = "cl$title") {
            EchoCard {
                Text(title, style = MaterialTheme.typography.titleSmall)
                l.forEach { Bullet(it) }
            }
        }
    }
}

@Composable
private fun ConversationRow(c: Conversation, a: ConvAnalysis?, onClick: () -> Unit) {
    EchoCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.time(c.startAt), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(72.dp))
            Text(c.title.orEmpty(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            a?.coach?.count?.takeIf { it > 0 }?.let { Pill("$it tips") }
        }
        c.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
    }
}

// ───────────────────────────── Speaking coach (trends) ─────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoachScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val rows by remember { g.db.analyses().observeDays(today.minusDays(13).toString(), today.toString()) }.collectAsStateWithLifecycle(emptyList())
    val days = rows.mapNotNull { r -> g.dayReview.decode(r.json)?.let { LocalDate.parse(r.ref) to it } }
    val (from, _) = dayBounds(today.minusDays(6))
    val convs by remember { g.db.conversations().inRange(from, System.currentTimeMillis() + 60_000) }.collectAsStateWithLifecycle(emptyList())
    val done = convs.filter { it.state == "done" && !it.ignored }
    val aRows by remember(done.map { it.id }) { g.db.analyses().observeMany("conv", done.map { it.id.toString() }) }.collectAsStateWithLifecycle(emptyList())
    val analyses = aRows.associate { it.ref.toLong() to g.analyzer.decode(it.json) }
    val coached = done.mapNotNull { c -> analyses[c.id]?.coach?.let { c to it } }
    val allFixes = coached.flatMap { (c, k) -> k.englishFixes.map { c to it } }
    val rules = allFixes.groupBy { it.second.why.lowercase().trim() }.filterKeys { it.isNotEmpty() }.toList().sortedByDescending { it.second.size }

    ReviewPage("Speaking coach", actions = { IconButton(onClick = { nav.go(ReviewSettingsRoute) }) { Icon(Icons.Rounded.Settings, "Coach settings") } }) {
        item {
            Text("From your own lines, read at night. Speech recognition makes mistakes too, so trust patterns more than single points.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (days.isEmpty()) item { EmptyState("No reviews yet", "After the first night review, your speaking trends show here: pace, filler words, confidence and recurring English mistakes.") }
        else {
            item { SectionHeader("Last two weeks") }
            item { TrendCard("Filler words per 100 words", days.map { it.first to it.second.speech.fillerPer100 }, lowerIsBetter = true) { "%.1f".format(it) } }
            item { TrendCard("Words per minute", days.map { it.first to it.second.speech.wpm.toFloat() }) { "${it.toInt()}" } }
            item { TrendCard("Confidence", days.mapNotNull { (d, r) -> r.confidence?.let { d to it.toFloat() } }) { "${it.toInt()}" } }
            item { TrendCard("Coaching points", days.map { it.first to it.second.coachPoints.toFloat() }, lowerIsBetter = true) { "${it.toInt()}" } }
        }
        if (rules.isNotEmpty()) {
            item { SectionHeader("Your most common English mistakes") }
            items(rules.take(6), key = { "r" + it.first }) { (rule, list) ->
                EchoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rule.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Pill("×${list.size}")
                    }
                    val ex = list.first().second
                    Text(ex.said, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textDecoration = TextDecoration.LineThrough, modifier = Modifier.padding(top = 4.dp))
                    Text(ex.better, style = MaterialTheme.typography.bodyMedium, color = LocalSemantic.current.positive)
                }
            }
        }
        coachSection(done.sortedByDescending { it.startAt }, analyses, null, s) { cid, line -> nav.go(ConversationRoute(cid, line)) }
    }
}

@Composable
private fun TrendCard(title: String, points: List<Pair<LocalDate, Float>>, lowerIsBetter: Boolean = false, fmt: (Float) -> String) {
    EchoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            points.lastOrNull()?.let { Text(fmt(it.second), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        }
        if (points.size >= 2) {
            val first = points.first().second; val last = points.last().second
            val better = if (lowerIsBetter) last < first else last > first
            Text(if (last == first) "Steady" else "${if (better) "Better" else "Worse"} than ${Fmt.day(points.first().first)} (${fmt(first)})", style = MaterialTheme.typography.labelSmall,
                color = if (last == first) MaterialTheme.colorScheme.outline else if (better) LocalSemantic.current.positive else MaterialTheme.colorScheme.error)
        }
        val max = points.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1f
        Row(Modifier.fillMaxWidth().height(56.dp).padding(top = Sp.s), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            points.forEach { (_, v) ->
                Box(Modifier.weight(1f).fillMaxHeight((v / max).coerceIn(0.04f, 1f)).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)))
            }
        }
        if (points.isNotEmpty()) Row(Modifier.fillMaxWidth()) {
            Text(Fmt.day(points.first().first), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
            Text(Fmt.day(points.last().first), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ───────────────────────────── Topics ─────────────────────────────

@Composable
fun TopicsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val today = LocalDate.now()
    val rows by remember { g.db.analyses().observeDays(today.minusDays(29).toString(), today.toString()) }.collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    val topics = rows.flatMap { r -> g.dayReview.decode(r.json)?.topics.orEmpty().map { r.ref to it } }
        .groupBy { it.second.name.lowercase() }
        .map { (_, l) -> Triple(l.first().second.name, l.map { it.first }.distinct().sortedDescending(), l.first().second.detail) }
        .filter { query.isBlank() || it.first.contains(query, true) || it.third.contains(query, true) }
        .sortedWith(compareByDescending<Triple<String, List<String>, String>> { it.second.size }.thenByDescending { it.second.first() })
    ReviewPage("Topics") {
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search topics") }, singleLine = true, shape = MaterialTheme.shapes.extraLarge) }
        item { Text("What you talked about in the last 30 days, most frequent first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (topics.isEmpty()) item { EmptyState("No topics yet", "Topics come from the night review of each day.") }
        items(topics, key = { it.first }) { (name, days, detail) ->
            EchoCard(onClick = { nav.go(DayReviewRoute(days.first())) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (days.size > 1) Pill("${days.size} days")
                }
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(days.take(5).joinToString(" · ") { Fmt.day(LocalDate.parse(it)) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

// ───────────────────────────── Settings ─────────────────────────────

@Composable
fun ReviewSettingsScreen() {
    val g = App.graph
    val s by g.settings.flow.collectAsStateWithLifecycle()
    fun set(change: (Settings) -> Settings) { g.scope.launch { g.settings.update(change); g.brain.poke() } }
    val local = g.ai.llm(app.echomind.core.Feature.JOURNAL).local
    ReviewPage("Journal, review & coach") {
        item {
            SectionHeader("Your journal")
            Text("Written each night from your whole day, like a diary page in a film. Facts always come from what was really said.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Sp.l))
            Choice("Style", listOf("cinematic" to "Cinematic", "reflective" to "Reflective", "simple" to "Simple"), s.journalStyle) { k -> set { it.copy(journalStyle = k) } }
            Choice("Written as", listOf("first" to "Me (“I…”)", "second" to "A friend (“You…”)"), s.journalVoice) { k -> set { it.copy(journalVoice = k) } }
            Choice("Length", listOf("short" to "Short", "medium" to "Medium", "long" to "Long"), s.journalLength) { k -> set { it.copy(journalLength = k) } }
            Text(
                when (s.journalStyle) {
                    "simple" -> "Clear and warm, everyday words."
                    "reflective" -> "Calm and thoughtful: what happened, then what it meant."
                    else -> "Lyrical and dreamlike, with small moments and quiet meaning — never invented."
                } + " Changes apply to the next journal; use Review again on a day to rewrite it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Sp.l),
            )
        }
        item {
            SectionHeader("Context the AI should know")
            var about by remember(s.aboutMe) { mutableStateOf(s.aboutMe) }
            OutlinedTextField(about, { about = it.take(500) }, Modifier.fillMaxWidth().padding(horizontal = Sp.l), minLines = 3, maxLines = 6,
                label = { Text("About me") },
                placeholder = { Text("Who you are, your work, your projects and the people around you. e.g. I build EchoMind, a note-taking app, and work at Eazeebox. Rahul is my manager.") },
                supportingText = { Text("${about.length}/500 · read with every conversation, so names and projects are understood") })
            if (about != s.aboutMe) TextButton(onClick = { set { it.copy(aboutMe = about.trim()) } }, modifier = Modifier.padding(horizontal = Sp.s)) { Text("Save") }
            Spacer(Modifier.height(Sp.s))
            Text("Words it mishears", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Sp.l))
            Text("Fixed before the AI reads your day. e.g. heard “not taking”, meant “note-taking”.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Sp.l))
            app.echomind.brain.Corrections.parse(s.corrections).forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Sp.l)) {
                    Text("“${r.heard}”  →  “${r.meant}”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { set { it.copy(corrections = it.corrections.filterNot { l -> app.echomind.brain.Corrections.parse(listOf(l)).firstOrNull() == r }) } }) { Text("Remove") }
                }
            }
            var heard by remember { mutableStateOf("") }
            var meant by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Sp.l, vertical = Sp.s)) {
                OutlinedTextField(heard, { heard = it }, Modifier.weight(1f), singleLine = true, label = { Text("Heard") })
                OutlinedTextField(meant, { meant = it }, Modifier.weight(1f), singleLine = true, label = { Text("Meant") })
                TextButton(enabled = heard.isNotBlank() && meant.isNotBlank(), onClick = {
                    val line = "${heard.trim()} => ${meant.trim()}"
                    set { it.copy(corrections = (it.corrections + line).distinct()) }
                    heard = ""; meant = ""
                }) { Text("Add") }
            }
        }
        item {
            SectionHeader("When")
            SwitchRow("Night review", "Every night EchoMind reads the whole day again, slowly and carefully: people, topics, a speaking coach, lessons, and a better journal", s.nightReview) { v -> set { it.copy(nightReview = v) } }
            var at by remember(s.nightReviewAt) { mutableFloatStateOf(((s.nightReviewAt + 24 * 60 - 18 * 60) % (24 * 60)).toFloat()) }
            Text("Start after ${hm((at.toInt() + 18 * 60) % (24 * 60))}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Sp.l, vertical = Sp.s))
            Slider(at, { at = (Math.round(it / 30f) * 30f) }, valueRange = 0f..(9 * 60f), steps = 17, onValueChangeFinished = { set { s0 -> s0.copy(nightReviewAt = (at.toInt() + 18 * 60) % (24 * 60)) } },
                modifier = Modifier.padding(horizontal = Sp.l))
            Text("It runs until 7 AM, after all of the day's conversations are read. Earlier days that were missed are caught up first.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Sp.l))
            SwitchRow("Only while charging", "Recommended if your phone gets warm at night", s.nightNeedsCharging) { v -> set { it.copy(nightNeedsCharging = v) } }
            SwitchRow("Read older conversations again", "Conversations read before this update get the careful reading too", s.reReadOld) { v -> set { it.copy(reReadOld = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.padding(horizontal = Sp.l, vertical = Sp.s)) {
                OutlinedButton(onClick = { g.capture.processNow(); g.brain.reviewNow(LocalDate.now().minusDays(1)) }) { Text("Review yesterday now") }
                OutlinedButton(onClick = { g.capture.processNow(); g.brain.reviewNow(LocalDate.now()) }) { Text("Review today now") }
            }
        }
        item {
            SectionHeader("Speaking coach")
            SwitchRow("English corrections", "Grammar and word choice in what you said in English or Hinglish", s.coachEnglish) { v -> set { it.copy(coachEnglish = v) } }
            SwitchRow("Better ways to say it", "Clearer, kinder or more confident versions of your sentences", s.coachBetter) { v -> set { it.copy(coachBetter = v) } }
            SwitchRow("Nervous moments", "Fillers, restarts, over-explaining — with a tip for next time", s.coachNervous) { v -> set { it.copy(coachNervous = v) } }
            SwitchRow("Things said wrong", "Wrong facts, contradictions, rude or over-promising lines", s.coachSlips) { v -> set { it.copy(coachSlips = v) } }
            Text("How strict", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Sp.l, vertical = Sp.s))
            val opts = listOf("gentle" to "Gentle", "normal" to "Normal", "strict" to "Strict")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Sp.l)) {
                opts.forEachIndexed { i, (k, label) ->
                    SegmentedButton(s.coachStrictness == k, { set { it.copy(coachStrictness = k) } }, SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(label) }
                }
            }
            var max by remember(s.coachMax) { mutableFloatStateOf(s.coachMax.toFloat()) }
            Text("Up to ${max.toInt()} points per conversation", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Sp.l, vertical = Sp.s))
            Slider(max, { max = it }, valueRange = 3f..15f, steps = 11, onValueChangeFinished = { set { it.copy(coachMax = max.toInt()) } }, modifier = Modifier.padding(horizontal = Sp.l))
        }
        item {
            SectionHeader("Day review sections")
            sections.forEach { (k, label) ->
                SwitchRow(label, null, k !in s.reviewHidden) { v -> set { it.copy(reviewHidden = if (v) it.reviewHidden - k else (it.reviewHidden + k).distinct()) } }
            }
        }
        if (local) item {
            SectionHeader("On-device model")
            Text("How much the on-phone AI can read at once. Bigger means better understanding of long conversations, but more memory and heat. If the model can't take the bigger size, EchoMind uses 4K by itself.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Sp.l))
            val sizes = listOf(4096 to "4K", 8192 to "8K", 16384 to "16K")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Sp.l, vertical = Sp.s)) {
                sizes.forEachIndexed { i, (k, label) ->
                    SegmentedButton(s.localContext == k, { set { it.copy(localContext = k) } }, SegmentedButtonDefaults.itemShape(i, sizes.size)) { Text(label) }
                }
            }
            Text("Loaded now: ${g.localLlm.contextTokens} tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = Sp.l))
        }
    }
}

@Composable
private fun Choice(title: String, options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Sp.l, end = Sp.l, top = Sp.s, bottom = 4.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Sp.l)) {
        options.forEachIndexed { i, (k, label) ->
            SegmentedButton(value == k, { onPick(k) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ───────────────────────────── On a conversation ─────────────────────────────

/** Topics, decisions and the coach's points for one conversation, with a button to coach it now. */
@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.conversationInsights(c: Conversation, a: ConvAnalysis?, s: Settings, openLine: (Long) -> Unit) {
    if (a == null) return
    if (a.topics.isNotEmpty()) item(key = "ci_t") {
        SectionHeader("Topics")
        a.topics.forEach { t ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall)
                if (t.detail.isNotBlank()) Text(t.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (a.decisions.isNotEmpty()) item(key = "ci_d") {
        SectionHeader("Decisions")
        a.decisions.forEach { Bullet(it) }
    }
    a.stats?.takeIf { it.words >= 12 }?.let { st ->
        item(key = "ci_s") {
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.padding(top = Sp.s)) {
                Tile("your words", "${st.words}", Modifier.weight(1f), hint = if (st.wpm > 0) "${st.wpm}/min" else null)
                Tile("fillers / 100", "%.1f".format(st.fillerPer100), Modifier.weight(1f), hint = st.fillers.keys.take(2).joinToString(", ").ifEmpty { null })
                Tile("restarts", "${st.restarts}", Modifier.weight(1f))
            }
        }
    }
    if (a.coach == null) item(key = "ci_c") {
        val g = App.graph
        var running by remember { mutableStateOf(false) }
        EchoCard(modifier = Modifier.padding(top = Sp.s)) {
            Text("Speaking coach", style = MaterialTheme.typography.titleSmall)
            Text("Not coached yet. It happens in the night review, or now (a few minutes on the phone).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = !running, onClick = {
                running = true
                g.scope.launch {
                    runCatching {
                        val utts = g.db.utterances().forConversation(c.id)
                        val k = g.analyzer.coach(c, utts)
                        val base = g.analyzer.load(c.id) ?: ConvAnalysis(stats = g.analyzer.stats(utts))
                        g.analyzer.save(c.id, base.copy(coach = k, coachedAt = System.currentTimeMillis()))
                    }
                    running = false
                }
            }) { Text(if (running) "Coaching…" else "Coach this conversation") }
        }
    } else coachSection(listOf(c), mapOf(c.id to a), null, s) { _, line -> openLine(line) }
}
