package app.echomind.ui.screens

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.DayMetrics
import app.echomind.data.DayEntry
import app.echomind.data.EventRow
import app.echomind.data.TaskRow
import app.echomind.ui.ConversationRoute
import app.echomind.ui.DayRoute
import app.echomind.ui.EventRoute
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.NewEventRoute
import app.echomind.ui.TaskRoute
import app.echomind.ui.TimelineRoute
import app.echomind.ui.components.Dot
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.ListenButton
import app.echomind.ui.components.Pill
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.SwitchRow
import app.echomind.ui.components.rememberResumeTick
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import app.echomind.util.Js
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private val zone get() = ZoneId.systemDefault()
private fun LocalDate.ms() = atStartOfDay(zone).toInstant().toEpochMilli()

/** How a day is coloured on the month grid. */
private enum class Shade(val label: String) { PRODUCTIVITY("Productivity"), MOOD("Mood"), TALK("Time talking") }

/** A score for colouring: your own rating wins, then the journal's judgement. */
private fun score(d: DayEntry?): Int? = d?.rating?.let { it * 20 } ?: d?.productivity

/**
 * The calendar: a month of days coloured by how they went, with what's
 * planned (yours, found in conversations, and the phone's calendars) and the
 * journal of each day underneath.
 */
@Composable
fun CalendarScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selectedStr by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var shade by rememberSaveable { mutableStateOf(Shade.PRODUCTIVITY) }
    var menu by remember { mutableStateOf(false) }
    val ym = YearMonth.parse(month)
    val selected = LocalDate.parse(selectedStr)
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val gridStart = ym.atDay(1).let { d -> d.minusDays(((d.dayOfWeek.value - first.value + 7) % 7).toLong()) }
    val gridEnd = gridStart.plusDays(42)
    val (from, to) = gridStart.ms() to gridEnd.ms()

    val days by remember(month) { g.db.days().observeRange(gridStart.toString(), gridEnd.toString()) }.collectAsStateWithLifecycle(emptyList())
    val convs by remember(month) { g.db.conversations().inRange(from, to) }.collectAsStateWithLifecycle(emptyList())
    val tasks by remember(month) { g.db.tasks().observeCalendar(from, to) }.collectAsStateWithLifecycle(emptyList())
    val own by remember(month) { g.db.events().observeRange(from, to) }.collectAsStateWithLifecycle(emptyList())
    val suggested by remember { g.db.events().observeSuggested() }.collectAsStateWithLifecycle(emptyList())
    val resume = rememberResumeTick()
    val events by produceState(emptyList<EventRow>(), month, own, s.showPhoneCalendar, resume) { value = g.agenda.range(from, to) }
    val byDay = remember(days) { days.associateBy { it.day } }
    val eventsByDay = remember(events) { events.filter { it.status == "confirmed" }.groupBy { Fmt.date(it.startAt) } }
    val tasksByDay = remember(tasks) { tasks.groupBy { Fmt.date(it.completedAt ?: it.dueAt ?: 0) } }
    val convsByDay = remember(convs) { convs.filter { it.state == "done" && !it.ignored }.groupBy { Fmt.date(it.startAt) } }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.READ_CALENDAR] == true) scope.launch { g.settings.update { it.copy(showPhoneCalendar = true) } }
    }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        topBar = {
            ScreenTopBar("Calendar") {
                IconButton(onClick = { nav.go(TimelineRoute) }) { Icon(Icons.Rounded.Search, "Search what was said") }
                ListenButton("Read my week") { g.agenda.weekAhead() }
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    Shade.entries.forEach { sh -> DropdownMenuItem(text = { Text("Colour by ${sh.label.lowercase()}${if (shade == sh) "  ✓" else ""}") }, onClick = { shade = sh; menu = false }) }
                    DropdownMenuItem(text = { Text(if (s.showPhoneCalendar) "Hide phone calendars" else "Show phone calendars") }, onClick = {
                        menu = false
                        if (s.showPhoneCalendar) scope.launch { g.settings.update { it.copy(showPhoneCalendar = false) } }
                        else if (g.agenda.canRead()) scope.launch { g.settings.update { it.copy(showPhoneCalendar = true) } }
                        else perms.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                    })
                }
            }
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { nav.go(NewEventRoute(selectedStr)) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Event") }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            item(key = "month") {
                MonthHeader(ym, onPrev = { month = ym.minusMonths(1).toString() }, onNext = { month = ym.plusMonths(1).toString() },
                    onToday = { month = YearMonth.now().toString(); selectedStr = LocalDate.now().toString() })
                AnimatedContent(ym, transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(240)) { it / 5 * dir } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(tween(200)) { -it / 5 * dir } + fadeOut(tween(120)))
                }, label = "month") { shown ->
                    MonthGrid(shown, gridStart, selected, shade, byDay, eventsByDay, tasksByDay, convsByDay,
                        onPick = { d -> selectedStr = d.toString(); if (YearMonth.from(d) != shown) month = YearMonth.from(d).toString() },
                        onSwipe = { dir -> month = if (dir > 0) ym.plusMonths(1).toString() else ym.minusMonths(1).toString() })
                }
                MonthStats(ym, byDay, eventsByDay, shade)
            }
            if (!s.showPhoneCalendar && !g.agenda.canRead()) item(key = "perm") {
                EchoCard(onClick = { perms.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CalendarMonth, null)
                        Spacer(Modifier.width(Sp.m))
                        Column(Modifier.weight(1f)) {
                            Text("Show your phone's calendar too", style = MaterialTheme.typography.titleSmall)
                            Text("Meetings from Google Calendar and others appear here, with a heads-up about who you're meeting. Read on the phone only.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (suggested.isNotEmpty()) {
                item(key = "sugh") { SectionHeader("Heard in conversations · ${suggested.size}") }
                items(suggested, key = { "s${it.id}" }) { e -> SuggestedEvent(e) }
            }
            item(key = "dayh") { DayHeader(selected, byDay[selected.toString()]) }
            val dayEvents = eventsByDay[selected].orEmpty()
            val dayTasks = tasksByDay[selected].orEmpty()
            val dayConvs = convsByDay[selected].orEmpty()
            if (dayEvents.isEmpty() && dayTasks.isEmpty() && dayConvs.isEmpty()) item(key = "empty") {
                Text(if (selected >= LocalDate.now()) "Nothing planned. Tap + to add an event, or just mention plans in conversation." else "Nothing recorded on this day.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Sp.s))
            }
            if (dayEvents.isNotEmpty()) {
                item(key = "evh") { SectionHeader("Events") }
                items(dayEvents, key = { "e${it.id}_${it.deviceId}_${it.startAt}" }) { e ->
                    EventRowView(e) {
                        if (e.source == "device" && e.deviceId != null) runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.deviceId)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else nav.go(EventRoute(e.id))
                    }
                }
            }
            if (dayTasks.isNotEmpty()) {
                item(key = "tkh") { SectionHeader("Tasks") }
                items(dayTasks, key = { "t${it.id}" }) { t -> CalendarTask(t) { nav.go(TaskRoute(t.id)) } }
            }
            if (dayConvs.isNotEmpty()) {
                item(key = "cvh") { SectionHeader("Conversations · ${dayConvs.size}") }
                items(dayConvs.sortedBy { it.startAt }, key = { "c${it.id}" }) { c -> ConversationRow(c) { nav.go(ConversationRoute(c.id)) } }
            }
        }
    }
}

@Composable
private fun MonthHeader(ym: YearMonth, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Sp.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(ym.format(DateTimeFormatter.ofPattern(if (ym.year == LocalDate.now().year) "MMMM" else "MMMM yyyy")), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        if (ym != YearMonth.now()) TextButton(onClick = onToday) { Text("Today") }
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month") }
    }
}

@Composable
private fun MonthGrid(
    ym: YearMonth, start: LocalDate, selected: LocalDate, shade: Shade,
    days: Map<String, DayEntry>, events: Map<LocalDate, List<EventRow>>, tasks: Map<LocalDate, List<TaskRow>>, convs: Map<LocalDate, List<app.echomind.data.Conversation>>,
    onPick: (LocalDate) -> Unit, onSwipe: (Int) -> Unit,
) {
    val today = LocalDate.now()
    val sem = LocalSemantic.current
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    var drag by remember { mutableStateOf(0f) }
    Column(Modifier.pointerInput(ym) {
        detectHorizontalDragGestures(onDragEnd = { if (drag < -80) onSwipe(1) else if (drag > 80) onSwipe(-1); drag = 0f }) { _, d -> drag += d }
    }) {
        Row(Modifier.fillMaxWidth()) {
            (0 until 7).forEach { i ->
                Text(start.plusDays(i.toLong()).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(Sp.xs))
        for (w in 0 until 6) {
            val weekStart = start.plusDays(w * 7L)
            if (w == 5 && YearMonth.from(weekStart) != ym) break
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) {
                    val d = weekStart.plusDays(i.toLong())
                    val inMonth = YearMonth.from(d) == ym
                    val entry = days[d.toString()]
                    val metrics = entry?.metrics?.let { runCatching { Js.json.decodeFromString(DayMetrics.serializer(), it) }.getOrNull() }
                    val tint: Color? = when (shade) {
                        Shade.PRODUCTIVITY -> score(entry)?.let { lerp(base, sem.positive, 0.15f + 0.75f * it / 100f) }
                        Shade.MOOD -> (entry?.mood ?: metrics?.mood)?.let { m -> if (m >= 0) lerp(base, sem.positive, 0.2f + 0.7f * m) else lerp(base, sem.recording, 0.2f + 0.7f * -m) }
                        Shade.TALK -> metrics?.talkMinutes?.takeIf { it > 0 }?.let { lerp(base, MaterialTheme.colorScheme.primary, 0.15f + 0.7f * minOf(1f, it / 240f)) }
                    }
                    Column(
                        Modifier.weight(1f).aspectRatio(0.82f).clip(MaterialTheme.shapes.small)
                            .background(tint ?: if (inMonth) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
                            .then(if (d == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
                            .clickable { onPick(d) }.padding(top = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(if (d == today) MaterialTheme.colorScheme.primary else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelLarge, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                                color = when { d == today -> MaterialTheme.colorScheme.onPrimary; inMonth -> MaterialTheme.colorScheme.onSurface; else -> MaterialTheme.colorScheme.outlineVariant })
                        }
                        Spacer(Modifier.height(3.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (events[d].orEmpty().isNotEmpty()) Dot(MaterialTheme.colorScheme.primary, 5)
                            val ts = tasks[d].orEmpty()
                            if (ts.any { it.status == "open" }) Dot(MaterialTheme.colorScheme.tertiary, 5)
                            if (ts.any { it.status == "done" }) Dot(sem.positive, 5)
                            if (convs[d].orEmpty().isNotEmpty()) Dot(MaterialTheme.colorScheme.outline, 5)
                        }
                        if (inMonth) events[d].orEmpty().firstOrNull()?.let {
                            Text(it.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.padding(horizontal = 2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.m), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Sp.xs)) {
            Legend(MaterialTheme.colorScheme.primary, "event"); Legend(MaterialTheme.colorScheme.tertiary, "due"); Legend(sem.positive, "done"); Legend(MaterialTheme.colorScheme.outline, "talked")
        }
    }
}

@Composable
private fun Legend(c: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Dot(c, 6); Spacer(Modifier.width(4.dp)); Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MonthStats(ym: YearMonth, days: Map<String, DayEntry>, events: Map<LocalDate, List<EventRow>>, shade: Shade) {
    val inMonth = days.values.filter { YearMonth.from(LocalDate.parse(it.day)) == ym }
    val scores = inMonth.mapNotNull { score(it) }
    val good = scores.count { it >= 60 }
    val evCount = events.filterKeys { YearMonth.from(it) == ym }.values.sumOf { it.size }
    val talk = inMonth.sumOf { d -> d.metrics.let { runCatching { Js.json.decodeFromString(DayMetrics.serializer(), it) }.getOrNull()?.talkMinutes ?: 0 } }
    Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.padding(vertical = Sp.xs)) {
        app.echomind.ui.components.Tile("productive days", if (scores.isEmpty()) "–" else "$good", Modifier.weight(1f), hint = if (scores.isEmpty()) "no scores yet" else "avg ${scores.average().toInt()}")
        app.echomind.ui.components.Tile("events", "$evCount", Modifier.weight(1f))
        app.echomind.ui.components.Tile("hours talking", if (talk == 0) "–" else "${talk / 60}", Modifier.weight(1f), hint = if (shade == Shade.TALK) "shown" else null)
    }
}

@Composable
private fun DayHeader(d: LocalDate, e: DayEntry?) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var writing by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = Sp.m)) {
        Text(Fmt.dayLong(d), style = MaterialTheme.typography.titleLarge)
        EchoCard(onClick = { nav.go(DayRoute(d.toString())) }, modifier = Modifier.padding(top = Sp.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val sc = score(e)
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
                    CircularProgressIndicator(progress = { (sc ?: 0) / 100f }, modifier = Modifier.size(56.dp), color = LocalSemantic.current.positive,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, strokeWidth = 5.dp)
                    Text(sc?.toString() ?: "–", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.width(Sp.l))
                Column(Modifier.weight(1f)) {
                    Text(e?.headline ?: if (d > LocalDate.now()) "Not here yet" else if (d == LocalDate.now()) "Today so far" else "No journal for this day", style = MaterialTheme.typography.titleMedium)
                    (e?.productiveWhy ?: e?.diary?.take(140))?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (d <= LocalDate.now()) {
                Spacer(Modifier.height(Sp.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your rating", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    (1..5).forEach { n ->
                        IconButton(onClick = { scope.launch { val cur = g.db.days().get(d.toString()) ?: DayEntry(day = d.toString()); g.db.days().upsert(cur.copy(rating = if (cur.rating == n) null else n)) } }, modifier = Modifier.size(36.dp)) {
                            Icon(if ((e?.rating ?: 0) >= n) Icons.Rounded.Star else Icons.Rounded.StarOutline, "Rate $n", tint = if ((e?.rating ?: 0) >= n) LocalSemantic.current.warning else MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                if (e?.generatedAt == null && g.ai.ready(app.echomind.core.Feature.JOURNAL)) {
                    OutlinedButton(enabled = !writing, onClick = { writing = true; scope.launch { g.journal.write(d, force = true); writing = false } }) {
                        Text(if (writing) "Writing…" else "Write this day's journal")
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRowView(e: EventRow, onClick: () -> Unit) {
    EchoCard(onClick = onClick, padding = PaddingValues(Sp.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(64.dp)) {
                Text(if (e.allDay) "All day" else Fmt.time(e.startAt), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                e.endAt?.takeIf { !e.allDay }?.let { Text(Fmt.time(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
            }
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.bodyLarge)
                val ppl = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), e.people) }.getOrDefault(emptyList())
                listOfNotNull(e.location, ppl.takeIf { it.isNotEmpty() }?.joinToString(", "), if (e.source == "device") e.quote else null).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(when (e.source) { "device" -> Icons.Rounded.PhoneAndroid; "ai" -> Icons.Rounded.AutoAwesome; else -> Icons.Rounded.Person }, when (e.source) { "device" -> "From your phone's calendar"; "ai" -> "Heard in a conversation"; else -> "Added by you" },
                Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun SuggestedEvent(e: EventRow) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    EchoCard(onClick = { nav.go(EventRoute(e.id)) }, color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f), padding = PaddingValues(start = Sp.m, top = Sp.s, bottom = Sp.s, end = 0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.bodyLarge)
                Text("${Fmt.day(e.startAt)}${if (e.allDay) "" else ", ${Fmt.time(e.startAt)}"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                e.quote?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            IconButton(onClick = { scope.launch { g.agenda.dismiss(e) } }) { Icon(Icons.Rounded.Close, "Not an event") }
            IconButton(onClick = { scope.launch { g.agenda.confirm(e) } }) { Icon(Icons.Rounded.Check, "Add to calendar", tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun CalendarTask(t: TaskRow, onClick: () -> Unit) {
    val g = App.graph
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { scope.launch { if (t.status == "done") g.tasks.reopen(t) else g.tasks.complete(t) } }) {
            Icon(if (t.status == "done") Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, if (t.status == "done") "Mark not done" else "Mark done",
                tint = if (t.status == "done") LocalSemantic.current.positive else MaterialTheme.colorScheme.outline)
        }
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, color = if (t.status == "done") MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
            (t.remindAt ?: t.dueAt)?.let { at ->
                val lt = Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
                if (!(lt.hour == 9 && lt.minute == 0 && t.remindAt == null)) Text(Fmt.time(at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** Add or edit an event. */
@Composable
fun EventScreen(id: Long?, day: String? = null) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val existing by produceState<EventRow?>(null, id) { value = id?.let { g.db.events().get(it) } }
    if (id != null && existing == null) return
    val e = existing
    val startDay = e?.let { Fmt.date(it.startAt) } ?: day?.let { LocalDate.parse(it) } ?: LocalDate.now()
    val defaultStart = e?.let { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalTime() } ?: LocalTime.now().plusHours(1).withMinute(0)
    var title by remember(e) { mutableStateOf(e?.title.orEmpty()) }
    var date by remember(e) { mutableStateOf(startDay) }
    var allDay by remember(e) { mutableStateOf(e?.allDay ?: false) }
    var startTime by remember(e) { mutableStateOf(defaultStart) }
    var endTime by remember(e) { mutableStateOf(e?.endAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: defaultStart.plusHours(1)) }
    var location by remember(e) { mutableStateOf(e?.location.orEmpty()) }
    var people by remember(e) { mutableStateOf(e?.people?.let { runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty().joinToString(", ")) }
    var notes by remember(e) { mutableStateOf(e?.notes.orEmpty()) }
    var remind by remember(e) { mutableIntStateOf(e?.remindBeforeMin ?: 15) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf<String?>(null) }
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val prep by produceState(emptyList<String>(), e) { value = e?.let { g.agenda.prep(it) }.orEmpty() }

    fun build(status: String): EventRow {
        val start = if (allDay) date.ms() else date.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
        val end = if (allDay) null else date.atTime(endTime).atZone(zone).toInstant().toEpochMilli().let { if (it <= start) it + 86_400_000L else it }
        val now = System.currentTimeMillis()
        return (e ?: EventRow(title = "", startAt = 0, source = "user", createdAt = now, updatedAt = now)).copy(
            title = title.trim(), startAt = start, endAt = end, allDay = allDay, location = location.trim().ifEmpty { null },
            people = Js.json.encodeToString(ListSerializer(String.serializer()), people.split(',').map { it.trim() }.filter { it.isNotEmpty() }),
            notes = notes.trim().ifEmpty { null }, remindBeforeMin = remind, status = status,
        )
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar(if (e == null) "New event" else if (e.status == "suggested") "Heard in a conversation" else "Event", onBack = { nav.back() }) {
            if (e != null) IconButton(onClick = { scope.launch { g.agenda.delete(e); nav.back() } }) { Icon(Icons.Rounded.Delete, "Delete event") }
        }
        LazyColumn(contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.m)) {
            if (e?.quote != null && e.source == "ai") item {
                EchoCard(onClick = e.conversationId?.let { { nav.go(ConversationRoute(it)) } }, color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)) {
                    Text("“${e.quote}”", style = MaterialTheme.typography.bodyMedium)
                    Text("Open the conversation", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            item { OutlinedTextField(title, { title = it }, label = { Text("What") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickDate = true }) { Text(Fmt.dayLong(date)) }
                    FilterChip(allDay, { allDay = !allDay }, label = { Text("All day") })
                }
                if (!allDay) Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.padding(top = Sp.s), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickTime = "start" }) { Text(startTime.format(DateTimeFormatter.ofPattern("h:mm a"))) }
                    Text("to")
                    OutlinedButton(onClick = { pickTime = "end" }) { Text(endTime.format(DateTimeFormatter.ofPattern("h:mm a"))) }
                }
            }
            item { OutlinedTextField(location, { location = it }, label = { Text("Where") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(people, { people = it }, label = { Text("With (names, comma separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth()) }
            item {
                Text("Heads-up before", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.padding(top = Sp.xs)) {
                    listOf(-1 to "None", 5 to "5 min", 15 to "15 min", 30 to "30 min", 60 to "1 h").forEach { (m, l) -> FilterChip(remind == m, { remind = m }, label = { Text(l) }) }
                }
                if (g.agenda.canWrite()) SwitchRow("Also add to the phone's calendar", "Confirmed events are copied to your main calendar", s.writeToPhoneCalendar) { v ->
                    scope.launch { g.settings.update { it.copy(writeToPhoneCalendar = v) } }
                }
            }
            if (prep.isNotEmpty()) item {
                SectionHeader("Before you go")
                EchoCard { prep.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Button(enabled = title.isNotBlank(), onClick = { scope.launch { g.agenda.save(build("confirmed")); nav.back() } }) { Text(if (e?.status == "suggested") "Add to calendar" else "Save") }
                    if (e?.status == "suggested") OutlinedButton(onClick = { scope.launch { g.agenda.dismiss(e); nav.back() } }) { Text("Not an event") }
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }

    if (pickDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; pickDate = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } }) { DatePicker(st) }
    }
    pickTime?.let { which ->
        val init = if (which == "start") startTime else endTime
        val st = rememberTimePickerState(init.hour, init.minute, is24Hour = false)
        AlertDialog(onDismissRequest = { pickTime = null }, text = { TimePicker(st) },
            confirmButton = {
                TextButton(onClick = {
                    val t = LocalTime.of(st.hour, st.minute)
                    if (which == "start") { val len = java.time.Duration.between(startTime, endTime); startTime = t; endTime = t.plus(if (len.isNegative) java.time.Duration.ofHours(1) else len) } else endTime = t
                    pickTime = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = null }) { Text("Cancel") } })
    }
}
