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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import app.echomind.ui.components.EmptyState
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
 * The calendar: a calm month (or one week) on top, and the chosen day below as
 * one timeline — events, tasks and conversations in time order — under its
 * diary page. Swipe the month to change months, the day to change days.
 * Agenda lists what's coming in the next 30 days.
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
    var shade by rememberSaveable { mutableStateOf(Shade.MOOD) }
    var agenda by rememberSaveable { mutableStateOf(false) }
    var weekOnly by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("all") }
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
    // Something on most days (a daily stand-up) is routine: it doesn't mark a day as planned.
    val routine = remember(events) { events.filter { it.status == "confirmed" }.groupBy { it.title.trim().lowercase() }.filterValues { l -> l.map { Fmt.date(it.startAt) }.distinct().size >= 5 }.keys }
    val tasksByDay = remember(tasks) { tasks.groupBy { Fmt.date(it.completedAt ?: it.dueAt ?: 0) } }
    val convsByDay = remember(convs) { convs.filter { it.state == "done" && !it.ignored }.groupBy { Fmt.date(it.startAt) } }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.READ_CALENDAR] == true) scope.launch { g.settings.update { it.copy(showPhoneCalendar = true) } }
    }
    fun pick(d: LocalDate) { selectedStr = d.toString(); if (YearMonth.from(d) != ym) month = YearMonth.from(d).toString() }
    fun openEvent(e: EventRow) {
        if (e.source == "device" && e.deviceId != null) runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.deviceId)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else nav.go(EventRoute(e.id))
    }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { nav.go(NewEventRoute(selectedStr)) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Event") }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(Sp.s), state = app.echomind.ui.components.rememberKeptListState()) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(top = Sp.s), verticalAlignment = Alignment.CenterVertically) {
                    Text(ym.format(DateTimeFormatter.ofPattern(if (ym.year == LocalDate.now().year) "MMMM" else "MMMM yyyy")), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    if (ym != YearMonth.now() || selected != LocalDate.now()) TextButton(onClick = { month = YearMonth.now().toString(); selectedStr = LocalDate.now().toString() }) { Text("Today") }
                    IconButton(onClick = { nav.go(TimelineRoute) }) { Icon(Icons.Rounded.Search, "Search what was said") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        Shade.entries.forEach { sh -> DropdownMenuItem(text = { Text("Colour days by ${sh.label.lowercase()}${if (shade == sh) "  ✓" else ""}") }, onClick = { shade = sh; menu = false }) }
                        DropdownMenuItem(text = { Text("Read my week aloud") }, onClick = { menu = false; scope.launch { val w = g.agenda.weekAhead(); g.speaker.speak(w.title, w.parts) } })
                        DropdownMenuItem(text = { Text(if (s.showPhoneCalendar) "Hide phone calendars" else "Show phone calendars") }, onClick = {
                            menu = false
                            if (s.showPhoneCalendar) scope.launch { g.settings.update { it.copy(showPhoneCalendar = false) } }
                            else if (g.agenda.canRead()) scope.launch { g.settings.update { it.copy(showPhoneCalendar = true) } }
                            else perms.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        })
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(!agenda, { agenda = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Month") }
                    SegmentedButton(agenda, { agenda = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Agenda") }
                }
            }
            if (agenda) { agendaItems(nav, ::openEvent); return@LazyColumn }

            item(key = "grid") {
                AnimatedContent(ym to weekOnly, transitionSpec = {
                    val dir = if (targetState.first > initialState.first) 1 else -1
                    (slideInHorizontally(tween(240)) { it / 5 * dir } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(tween(200)) { -it / 5 * dir } + fadeOut(tween(120)))
                }, label = "month") { (shown, week) ->
                    MonthGrid(shown, gridStart, selected, shade, byDay, eventsByDay.mapValues { (_, l) -> l.filter { it.title.trim().lowercase() !in routine } }, tasksByDay, weekOnly = week,
                        onPick = ::pick,
                        onSwipe = { dir -> if (week) pick(selected.plusWeeks(dir.toLong())) else month = if (dir > 0) ym.plusMonths(1).toString() else ym.minusMonths(1).toString() })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { weekOnly = !weekOnly }) { Text(if (weekOnly) "Show the month" else "Show one week") }
                }
            }
            if (!s.showPhoneCalendar && !g.agenda.canRead()) item(key = "perm") {
                EchoCard(onClick = { perms.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CalendarMonth, null)
                        Spacer(Modifier.width(Sp.m))
                        Column(Modifier.weight(1f)) {
                            Text("Show your phone's calendar too", style = MaterialTheme.typography.titleSmall)
                            Text("Meetings from Google Calendar and others appear here. Read on the phone only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (suggested.isNotEmpty()) {
                item(key = "sugh") { SectionHeader("Heard in conversations · ${suggested.size}") }
                items(suggested, key = { "s${it.id}" }) { e -> SuggestedEvent(e) }
            }

            // The day: swipe to move between days.
            item(key = "dayh") { DayHeader(selected, byDay[selected.toString()], onPrev = { pick(selected.minusDays(1)) }, onNext = { pick(selected.plusDays(1)) }) }
            val dayEvents = eventsByDay[selected].orEmpty()
            val dayTasks = tasksByDay[selected].orEmpty()
            val dayConvs = convsByDay[selected].orEmpty()
            item(key = "filters") {
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    listOf("all" to "All", "events" to "Events · ${dayEvents.size}", "tasks" to "Tasks · ${dayTasks.size}", "talk" to "Talk · ${dayConvs.size}").forEach { (k, l) ->
                        androidx.compose.material3.FilterChip(filter == k, { filter = k }, label = { Text(l) })
                    }
                }
            }
            // One timeline, in time order. All-day events and undated tasks go first.
            data class Item(val at: Long, val key: String, val content: @Composable () -> Unit)
            val timeline = buildList {
                if (filter == "all" || filter == "events") dayEvents.forEach { e -> add(Item(if (e.allDay) 0 else e.startAt, "e${e.id}_${e.deviceId}_${e.startAt}") { TimelineRow(if (e.allDay) "All day" else Fmt.time(e.startAt), MaterialTheme.colorScheme.primary) { EventRowView(e, showTime = false) { openEvent(e) } } }) }
                if (filter == "all" || filter == "tasks") dayTasks.forEach { t -> val at = t.remindAt ?: t.dueAt ?: 0; add(Item(at, "t${t.id}") { TimelineRow(timeOf(t), MaterialTheme.colorScheme.tertiary) { CalendarTask(t) { nav.go(TaskRoute(t.id)) } } }) }
                if (filter == "all" || filter == "talk") dayConvs.forEach { c -> add(Item(c.startAt, "c${c.id}") { TimelineRow(Fmt.time(c.startAt), MaterialTheme.colorScheme.outline) { ConversationRow(c) { nav.go(ConversationRoute(c.id)) } } }) }
            }.sortedBy { it.at }
            if (timeline.isEmpty()) item(key = "empty") {
                Text(if (selected >= LocalDate.now()) "Nothing planned. Tap Event to add one, or just mention plans in conversation." else "Nothing on this day.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Sp.s))
            }
            timeline.forEach { it2 -> item(key = it2.key) { it2.content() } }
            if (selected <= LocalDate.now() && filter == "all") {
                item(key = "more") {
                    var open by rememberSaveable(selectedStr) { mutableStateOf(false) }
                    TextButton(onClick = { open = !open }) { Text(if (open) "Hide sounds and songs" else "Sounds and songs of this day") }
                    if (open) Column { ActivityTimeline(selected); DaySongs(selected) }
                }
            }
        }
    }
}

private fun timeOf(t: TaskRow): String {
    val at = t.remindAt ?: t.dueAt ?: return "Any time"
    val lt = Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
    return if (lt.hour == 9 && lt.minute == 0 && t.remindAt == null) "Any time" else Fmt.time(at)
}

/** A time on the left, a coloured rail, the item on the right. */
@Composable
private fun TimelineRow(time: String, color: Color, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.width(58.dp).padding(top = 12.dp)) {
            Text(time, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
        }
        Box(Modifier.padding(top = 14.dp).size(width = 3.dp, height = 22.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(Sp.s))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** The next 30 days: events and open tasks, under sticky day headings, empty days skipped. */
private fun androidx.compose.foundation.lazy.LazyListScope.agendaItems(nav: app.echomind.ui.Navigator, openEvent: (EventRow) -> Unit) {
    item(key = "agenda") {
        val g = App.graph
        val start = LocalDate.now()
        val (a, b) = start.ms() to start.plusDays(30).ms()
        val ev by produceState(emptyList<EventRow>()) { value = g.agenda.range(a, b).filter { it.status == "confirmed" } }
        val ts by remember { g.db.tasks().observeCalendar(a, b) }.collectAsStateWithLifecycle(emptyList())
        val evBy = ev.groupBy { Fmt.date(it.startAt) }
        val tsBy = ts.filter { it.status == "open" }.groupBy { Fmt.date(it.dueAt ?: it.remindAt ?: 0) }
        val daysWith = (evBy.keys + tsBy.keys).filter { !it.isBefore(start) }.sorted()
        Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            if (daysWith.isEmpty()) EmptyState("Nothing in the next 30 days", "Events from your calendars and tasks with a date show up here.")
            daysWith.forEach { d ->
                Text(Fmt.day(d) + if (Fmt.day(d) != Fmt.dayLong(d)) " · " + d.format(DateTimeFormatter.ofPattern("EEE d MMM")) else "", style = MaterialTheme.typography.titleSmall,
                    color = if (d == start) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = Sp.m))
                evBy[d].orEmpty().sortedBy { if (it.allDay) 0 else it.startAt }.forEach { e -> TimelineRow(if (e.allDay) "All day" else Fmt.time(e.startAt), MaterialTheme.colorScheme.primary) { EventRowView(e, showTime = false) { openEvent(e) } } }
                tsBy[d].orEmpty().forEach { t -> TimelineRow(timeOf(t), MaterialTheme.colorScheme.tertiary) { CalendarTask(t) { nav.go(TaskRoute(t.id)) } } }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    ym: YearMonth, start: LocalDate, selected: LocalDate, shade: Shade,
    days: Map<String, DayEntry>, events: Map<LocalDate, List<EventRow>>, tasks: Map<LocalDate, List<TaskRow>>, weekOnly: Boolean,
    onPick: (LocalDate) -> Unit, onSwipe: (Int) -> Unit,
) {
    val today = LocalDate.now()
    val sem = LocalSemantic.current
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    var drag by remember { mutableStateOf(0f) }
    Column(Modifier.pointerInput(ym, weekOnly) {
        detectHorizontalDragGestures(onDragEnd = { if (drag < -80) onSwipe(1) else if (drag > 80) onSwipe(-1); drag = 0f }) { _, d -> drag += d }
    }) {
        Row(Modifier.fillMaxWidth()) {
            (0 until 7).forEach { i ->
                Text(start.plusDays(i.toLong()).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(Sp.xs))
        val weeks = if (weekOnly) listOf(selected.minusDays(((selected.dayOfWeek.value - start.dayOfWeek.value + 7) % 7).toLong())) else (0 until 6).map { start.plusDays(it * 7L) }.filter { YearMonth.from(it) == ym || YearMonth.from(it.plusDays(6)) == ym }
        for (weekStart in weeks) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) {
                    val d = weekStart.plusDays(i.toLong())
                    val inMonth = weekOnly || YearMonth.from(d) == ym
                    val entry = days[d.toString()]
                    val metrics = entry?.metrics?.let { runCatching { Js.json.decodeFromString(DayMetrics.serializer(), it) }.getOrNull() }
                    // One soft colour for how the day went…
                    val tint: Color? = when (shade) {
                        Shade.PRODUCTIVITY -> score(entry)?.let { lerp(base, sem.positive, 0.12f + 0.5f * it / 100f) }
                        Shade.MOOD -> (entry?.mood ?: metrics?.mood)?.let { m -> if (m >= 0) lerp(base, sem.positive, 0.1f + 0.4f * m) else lerp(base, MaterialTheme.colorScheme.tertiary, 0.08f + 0.3f * -m) }
                        Shade.TALK -> metrics?.talkMinutes?.takeIf { it > 0 }?.let { lerp(base, MaterialTheme.colorScheme.primary, 0.1f + 0.5f * minOf(1f, it / 240f)) }
                    }
                    // …and at most two marks: something planned, and a diary written.
                    val planned = events[d].orEmpty().isNotEmpty() || tasks[d].orEmpty().any { it.status == "open" }
                    val diary = !entry?.diary.isNullOrBlank()
                    Column(
                        Modifier.weight(1f).aspectRatio(0.9f).clip(MaterialTheme.shapes.small)
                            .background(if (inMonth) (tint ?: base) else Color.Transparent)
                            .then(if (d == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
                            .clickable { onPick(d) }.padding(top = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(if (d == today) MaterialTheme.colorScheme.primary else Color.Transparent), contentAlignment = Alignment.Center) {
                            Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelLarge, fontWeight = if (d == today || d == selected) FontWeight.Bold else FontWeight.Normal,
                                color = when { d == today -> MaterialTheme.colorScheme.onPrimary; inMonth -> MaterialTheme.colorScheme.onSurface; else -> MaterialTheme.colorScheme.outlineVariant })
                        }
                        Spacer(Modifier.height(3.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (inMonth && planned) Dot(MaterialTheme.colorScheme.primary, 5)
                            if (inMonth && diary) Box(Modifier.size(width = 8.dp, height = 3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.m), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Sp.xs)) {
            Legend(MaterialTheme.colorScheme.primary, "planned")
            Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(width = 10.dp, height = 3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary)); Spacer(Modifier.width(4.dp)); Text("diary", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("colour: ${shade.label.lowercase()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun Legend(c: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Dot(c, 6); Spacer(Modifier.width(4.dp)); Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The chosen day: its name with arrows (or swipe), its diary page and your rating. */
@Composable
private fun DayHeader(d: LocalDate, e: DayEntry?, onPrev: () -> Unit, onNext: () -> Unit) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var writing by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(0f) }
    Column(Modifier.padding(top = Sp.m).pointerInput(d) {
        detectHorizontalDragGestures(onDragEnd = { if (drag < -80) onNext() else if (drag > 80) onPrev(); drag = 0f }) { _, x -> drag += x }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Fmt.day(d).let { if (it == "Today" || it == "Yesterday" || it == "Tomorrow") it else d.format(DateTimeFormatter.ofPattern("EEEE")) }, style = MaterialTheme.typography.titleLarge)
                Text(d.format(DateTimeFormatter.ofPattern("d MMMM yyyy")) + if (remember(d) { g.travel.wasTravelling(d) }) "  ·  ✈ travel day" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous day") }
            IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next day") }
        }
        val diary = e?.diary?.takeIf { it.isNotBlank() }
        if (diary != null) app.echomind.ui.components.DiaryPage(diary, e.headline, null, maxLines = 4, modifier = Modifier.padding(top = Sp.s), onClick = { nav.go(app.echomind.ui.DayReviewRoute(d.toString())) })
        if (d <= LocalDate.now()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = { nav.go(app.echomind.ui.DayReviewRoute(d.toString())) }) { Text("Day review") }
            TextButton(onClick = { nav.go(app.echomind.ui.MapRoute(d.toString())) }) { Text("Map") }
            if (diary == null && e?.generatedAt == null && g.ai.ready(app.echomind.core.Feature.JOURNAL)) {
                TextButton(enabled = !writing, onClick = { writing = true; scope.launch { g.journal.write(d, force = true); writing = false } }) { Text(if (writing) "Writing…" else "Write the diary") }
            }
            Spacer(Modifier.weight(1f))
            (1..5).forEach { n ->
                IconButton(onClick = { scope.launch { val cur = g.db.days().get(d.toString()) ?: DayEntry(day = d.toString()); g.db.days().upsert(cur.copy(rating = if (cur.rating == n) null else n)) } }, modifier = Modifier.size(32.dp)) {
                    Icon(if ((e?.rating ?: 0) >= n) Icons.Rounded.Star else Icons.Rounded.StarOutline, "Rate the day $n of 5", Modifier.size(20.dp), tint = if ((e?.rating ?: 0) >= n) LocalSemantic.current.warning else MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun EventRowView(e: EventRow, showTime: Boolean = true, onClick: () -> Unit) {
    EchoCard(onClick = onClick, padding = PaddingValues(Sp.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showTime) Column(Modifier.width(64.dp)) {
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
    var remind by remember(e) { mutableIntStateOf(e?.remindBeforeMin ?: g.settings.value.eventRemindDefault) }
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
        LazyColumn(contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.m), state = app.echomind.ui.components.rememberKeptListState()) {
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
