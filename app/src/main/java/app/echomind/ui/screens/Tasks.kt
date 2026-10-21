package app.echomind.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.QuickAdd
import app.echomind.brain.Recurrence
import app.echomind.data.Project
import app.echomind.data.SubtaskCount
import app.echomind.data.TaskRow
import app.echomind.ui.ConversationRoute
import app.echomind.ui.Fmt
import app.echomind.ui.ListsRoute
import app.echomind.ui.LocalNav
import app.echomind.ui.RemindersRoute
import app.echomind.ui.TaskRoute
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.Pill
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Palette
import app.echomind.ui.theme.Sp
import app.echomind.util.Js
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

// ───────────────────────────── shared bits ─────────────────────────────

@Composable
fun priorityColor(p: Int): Color = when (p) {
    1 -> Color(0xFFE5484D)
    2 -> Color(0xFFF08C00)
    3 -> Color(0xFF3E7BFA)
    else -> MaterialTheme.colorScheme.outline
}

private val zone get() = ZoneId.systemDefault()
private fun startOf(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
private fun at(d: LocalDate, t: LocalTime) = d.atTime(t).atZone(zone).toInstant().toEpochMilli()

fun labelsOf(t: TaskRow): List<String> = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), t.labels) }.getOrDefault(emptyList())
private fun encodeLabels(l: List<String>) = Js.json.encodeToString(ListSerializer(String.serializer()), l)

/** Quick times offered everywhere a time is picked. */
fun timePresets(now: Long = System.currentTimeMillis()): List<Pair<String, Long>> {
    val today = LocalDate.now()
    val hour = LocalTime.now().hour
    return buildList {
        add("In 1 hour" to now + 3_600_000L)
        if (hour < 17) add("This evening · 6 PM" to at(today, LocalTime.of(18, 0)))
        if (hour < 21) add("Tonight · 9 PM" to at(today, LocalTime.of(21, 0)))
        add("Tomorrow · 9 AM" to at(today.plusDays(1), LocalTime.of(9, 0)))
        add("Tomorrow · 6 PM" to at(today.plusDays(1), LocalTime.of(18, 0)))
        val sat = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
        add("This weekend · Sat 10 AM" to at(sat, LocalTime.of(10, 0)))
        add("Next week · Mon 9 AM" to at(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)), LocalTime.of(9, 0)))
    }.filter { it.second > now }
}

/** A round checkbox ringed in the priority colour, Todoist-style. */
@Composable
fun TaskCheck(done: Boolean, priority: Int, onToggle: () -> Unit) {
    val c = priorityColor(priority)
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(if (done) c else c.copy(alpha = if (priority < 4) 0.12f else 0f))
                .border(2.dp, c, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (done) Icon(Icons.Rounded.Check, "Done", tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskLine(t: TaskRow, projects: Map<Long, Project>, sub: SubtaskCount?, showList: Boolean = true, onToggle: () -> Unit, onClick: () -> Unit) {
    val now = System.currentTimeMillis()
    val overdue = t.status == "open" && t.dueAt != null && t.dueAt < startOf(LocalDate.now())
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(end = Sp.m, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.Top) {
        TaskCheck(t.status == "done", t.priority, onToggle)
        Column(Modifier.weight(1f).padding(top = 9.dp)) {
            Text(
                t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis,
                textDecoration = if (t.status == "done") TextDecoration.LineThrough else null,
                color = if (t.status == "done") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            t.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
                (t.dueAt)?.let { due ->
                    val timeShown = Instant.ofEpochMilli(due).atZone(zone).toLocalTime().let { it.hour != 9 || it.minute != 0 } || t.remindAt != null
                    Meta(Icons.Rounded.Schedule, Fmt.day(due) + if (timeShown) " ${Fmt.time(due)}" else "", if (overdue) MaterialTheme.colorScheme.error else if (Fmt.day(due) == "Today") LocalSemantic.current.positive else null)
                }
                if (t.remindAt != null && t.remindAt > now) Meta(Icons.Rounded.NotificationsActive, if (t.dueAt == null) Fmt.dayTime(t.remindAt) else "")
                t.recurrence?.let { Meta(Icons.Rounded.Repeat, Recurrence.label(it) ?: "") }
                sub?.takeIf { it.total > 0 }?.let { Meta(Icons.Rounded.FormatListBulleted, "${it.done}/${it.total}") }
                labelsOf(t).forEach { Meta(Icons.Rounded.Label, it) }
                if (t.conversationId != null && t.origin != "user") Meta(Icons.Rounded.GraphicEq, "From a conversation")
                if (showList) t.projectId?.let { projects[it] }?.let { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.color(p.color)))
                        Spacer(Modifier.width(4.dp))
                        Text(p.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Meta(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color? = null) {
    val c = color ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c, modifier = Modifier.size(13.dp))
        if (text.isNotEmpty()) { Spacer(Modifier.width(3.dp)); Text(text, style = MaterialTheme.typography.labelMedium, color = c) }
    }
}

/** A task row you can swipe: right to complete, left to reschedule. */
@Composable
fun SwipeTask(t: TaskRow, projects: Map<Long, Project>, sub: SubtaskCount?, showList: Boolean, onComplete: () -> Unit, onReschedule: () -> Unit, onClick: () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium)
                    .background(if (toEnd) LocalSemantic.current.positive.copy(alpha = 0.85f) else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f))
                    .padding(horizontal = Sp.xl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
            ) {
                Icon(if (toEnd) Icons.Rounded.Check else Icons.Rounded.Snooze, null, tint = Color.White)
                Spacer(Modifier.width(Sp.s))
                Text(if (toEnd) "Complete" else "Reschedule", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        },
        onDismiss = { v ->
            if (v == SwipeToDismissBoxValue.StartToEnd) onComplete() else if (v == SwipeToDismissBoxValue.EndToStart) onReschedule()
            scope.launch { state.reset() }
        },
    ) {
        Box(Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            TaskLine(t, projects, sub, showList, onToggle = onComplete, onClick = onClick)
        }
    }
}

/** Reschedule sheet: quick presets, a calendar, or remove the date. */
@Composable
fun RescheduleSheet(t: TaskRow, onDone: () -> Unit) {
    val g = App.graph
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDone) {
        Column(Modifier.padding(horizontal = Sp.xl).padding(bottom = Sp.xxl)) {
            Text("Reschedule", style = MaterialTheme.typography.titleMedium)
            Text(t.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Sp.m))
            timePresets().forEach { (label, ms) ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { scope.launch { g.tasks.reschedule(t, ms); onDone() } }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(Sp.l))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { picking = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Alarm, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(Sp.l)); Text("Pick date & time…", style = MaterialTheme.typography.bodyLarge)
            }
            if (t.dueAt != null) Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { scope.launch { g.tasks.reschedule(t, null); onDone() } }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(Sp.l)); Text("No date", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    if (picking) DateTimeDialog(t.dueAt) { ms -> picking = false; if (ms != null) scope.launch { g.tasks.reschedule(t, ms); onDone() } }
}

/** Always-visible quick add with a live preview of what was understood. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickAddBar(defaultProjectId: Long? = null, placeholder: String = "Add a task — e.g. Call mom tomorrow 6pm #family !1", onAdded: (String) -> Unit = {}) {
    val g = App.graph
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    val parsed = remember(text) { if (text.isBlank()) null else QuickAdd.parse(text) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = Sp.m, vertical = Sp.s)) {
        if (parsed != null && (parsed.due != null || parsed.list != null || parsed.priority < 4 || parsed.recurrence != null || parsed.labels.isNotEmpty())) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)) {
                parsed.due?.let { Pill((if (parsed.remind) "🔔 " else "") + Fmt.day(it.at) + if (it.hasTime) " ${Fmt.time(it.at)}" else "") }
                parsed.recurrence?.let { Pill(Recurrence.label(it) ?: it) }
                parsed.list?.let { Pill("# $it") }
                if (parsed.priority < 4) Pill("P${parsed.priority}", color = priorityColor(parsed.priority).copy(alpha = 0.18f), textColor = priorityColor(parsed.priority))
                parsed.labels.forEach { Pill("@$it") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Spacer(Modifier.width(Sp.s))
            FilledIconButton(onClick = {
                val t = text.trim()
                if (t.isNotEmpty()) scope.launch { g.tasks.addQuick(t, defaultProjectId); onAdded(QuickAdd.parse(t).title) }
                text = ""
            }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.Send, "Add") }
        }
    }
}

// ───────────────────────────── Tasks tab ─────────────────────────────

private enum class View(val label: String) { TODAY("Today"), UPCOMING("Upcoming"), ALL("All"), REVIEW("Review"), DONE("Done") }

@Composable
fun TasksScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val open by remember { g.db.tasks().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    val review by remember { g.db.tasks().observeByStatus("suggested") }.collectAsStateWithLifecycle(emptyList())
    val done by remember { g.db.tasks().observeDone() }.collectAsStateWithLifecycle(emptyList())
    val projectsList by remember { g.db.projects().observe() }.collectAsStateWithLifecycle(emptyList())
    val subCounts by remember { g.db.tasks().observeSubtaskCounts() }.collectAsStateWithLifecycle(emptyList())
    val todayStart = startOf(LocalDate.now())
    val doneToday by remember(todayStart) { g.db.tasks().observeDoneSince(todayStart) }.collectAsStateWithLifecycle(emptyList())
    val projects = remember(projectsList) { projectsList.associateBy { it.id } }
    val subs = remember(subCounts) { subCounts.associateBy { it.id } }
    var view by rememberSaveable { mutableStateOf(if (review.isNotEmpty()) View.REVIEW else View.TODAY) }
    var listFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var reschedule by remember { mutableStateOf<TaskRow?>(null) }
    val snackbar = remember { SnackbarHostState() }

    fun complete(t: TaskRow) = scope.launch {
        val nextId = g.tasks.complete(t)
        val r = snackbar.showSnackbar(if (nextId != null) "Done — next one scheduled" else "Completed", actionLabel = "Undo", withDismissAction = true)
        if (r == SnackbarResult.ActionPerformed) {
            g.tasks.reopen(t)
            nextId?.let { id -> g.db.tasks().get(id)?.let { g.tasks.delete(it) } }
        }
    }

    Scaffold(contentWindowInsets = WindowInsets(0), snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.ime)) {
            Row(Modifier.padding(start = Sp.gutter, end = 4.dp, top = Sp.s), verticalAlignment = Alignment.CenterVertically) {
                Text("Tasks", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Rounded.Search, "Search") }
                IconButton(onClick = { nav.go(RemindersRoute) }) { Icon(Icons.Rounded.NotificationsActive, "Reminders") }
                IconButton(onClick = { nav.go(ListsRoute) }) { Icon(Icons.Rounded.FormatListBulleted, "Lists") }
            }
            if (searching) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = Sp.gutter), placeholder = { Text("Search tasks") }, singleLine = true, shape = MaterialTheme.shapes.extraLarge)
            LazyRow(contentPadding = PaddingValues(horizontal = Sp.gutter, vertical = Sp.s), horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                items(View.entries.toList(), key = { it.name }) { v ->
                    val count = when (v) { View.REVIEW -> review.size; else -> 0 }
                    FilterChip(view == v && listFilter == null, { view = v; listFilter = null }, label = { Text(v.label + if (count > 0) " · $count" else "") })
                }
                items(projectsList, key = { "p${it.id}" }) { p ->
                    FilterChip(listFilter == p.id, { listFilter = p.id; view = View.ALL }, label = { Text(p.name) },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.color(p.color))) })
                }
            }

            val filtered = open.filter { (listFilter == null || it.projectId == listFilter) && (query.isBlank() || it.title.contains(query, true) || (it.detail ?: "").contains(query, true)) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = Sp.l), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                fun section(title: String, tasks: List<TaskRow>, showList: Boolean = true) {
                    if (tasks.isEmpty()) return
                    item(key = "h$title") { SectionHeader("$title · ${tasks.size}") }
                    items(tasks, key = { "t${it.id}" }) { t ->
                        SwipeTask(t, projects, subs[t.id], showList, onComplete = { complete(t) }, onReschedule = { reschedule = t }, onClick = { nav.go(TaskRoute(t.id)) })
                    }
                }
                val endToday = startOf(LocalDate.now().plusDays(1))
                when (if (listFilter != null) View.ALL else view) {
                    View.TODAY -> {
                        val overdue = filtered.filter { it.dueAt != null && it.dueAt < todayStart }.sortedBy { it.dueAt }
                        val today = filtered.filter { it.dueAt != null && it.dueAt in todayStart until endToday }.sortedWith(compareBy({ it.priority }, { it.dueAt }))
                        item(key = "progress") { TodayProgress(doneToday.count { it.parentId == null }, overdue.size + today.size) }
                        if (overdue.isEmpty() && today.isEmpty()) item { EmptyState("Nothing due today", "Add something below, or swipe a task left to plan it for today.") }
                        section("Overdue", overdue)
                        section("Today", today)
                    }
                    View.UPCOMING -> {
                        val days = (0..13).map { LocalDate.now().plusDays(it.toLong()) }
                        var any = false
                        for (d in days) {
                            val list = filtered.filter { it.dueAt != null && it.dueAt in startOf(d) until startOf(d.plusDays(1)) }.sortedBy { it.dueAt }
                            if (list.isNotEmpty()) any = true
                            section(Fmt.day(d) + if (d.dayOfWeek == DayOfWeek.MONDAY && d != LocalDate.now()) " · new week" else "", list)
                        }
                        section("Later", filtered.filter { it.dueAt != null && it.dueAt >= startOf(LocalDate.now().plusDays(14)) }.sortedBy { it.dueAt })
                        if (!any) item { EmptyState("Nothing planned", "Tasks with a date in the next two weeks show here, day by day.") }
                    }
                    View.ALL -> {
                        val byList = filtered.groupBy { it.projectId }
                        section("Inbox", byList[null].orEmpty(), showList = false)
                        projectsList.forEach { p -> section(p.name, byList[p.id].orEmpty(), showList = false) }
                        if (filtered.isEmpty()) item { EmptyState(if (listFilter != null) "This list is empty" else "No open tasks", "Add one below — #list, !1 priority, @label, \"every week\" all work.") }
                    }
                    View.REVIEW -> {
                        if (review.isEmpty()) item { EmptyState("All reviewed", "When the AI hears a commitment or a request, it waits here for your OK — nothing notifies you until you accept it.") }
                        else item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Found in your conversations. Accept what's real.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                TextButton(onClick = { scope.launch { review.forEach { g.tasks.accept(it) } } }) { Text("Accept all") }
                            }
                        }
                        items(review, key = { "r${it.id}" }) { t -> ReviewCard(t) }
                    }
                    View.DONE -> {
                        if (done.isEmpty()) item { EmptyState("Nothing done yet", "Completed tasks are kept here.") }
                        items(done.filter { it.parentId == null }, key = { "d${it.id}" }) { t ->
                            TaskLine(t, projects, subs[t.id], onToggle = { scope.launch { g.tasks.reopen(t) } }, onClick = { nav.go(TaskRoute(t.id)) })
                        }
                    }
                }
            }
            if (view != View.REVIEW && view != View.DONE) QuickAddBar(listFilter) { title -> scope.launch { snackbar.showSnackbar("Added “$title”") } }
        }
    }
    reschedule?.let { t -> RescheduleSheet(t) { reschedule = null } }
}

@Composable
private fun TodayProgress(done: Int, left: Int) {
    val total = done + left
    if (total == 0) return
    EchoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.size(44.dp), strokeWidth = 5.dp, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                Text("${done * 100 / total}%", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(Sp.l))
            Column {
                Text(if (left == 0) "All done for today 🎉" else "$left left today", style = MaterialTheme.typography.titleSmall)
                Text("$done completed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(t: TaskRow) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var dismissing by remember { mutableStateOf(false) }
    EchoCard(modifier = Modifier.animateContentSize(), onClick = t.conversationId?.let { { nav.go(ConversationRoute(it)) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (t.owner != "me") Pill(if (t.owner == "other") "Someone else" else "Unclear who")
        }
        t.quote?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(listOfNotNull(t.remindAt?.let { "Reminder ${Fmt.dayTime(it)}" } ?: t.dueText, Fmt.ago(t.createdAt)).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(Sp.s))
        if (!dismissing) {
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                Button(onClick = { scope.launch { g.tasks.accept(t) } }) { Text(if (t.remindAt != null) "Accept & remind" else "Accept") }
                TextButton(onClick = { nav.go(TaskRoute(t.id)) }) { Text("Edit") }
                TextButton(onClick = { dismissing = true }) { Text("Dismiss") }
            }
        } else {
            Text("Why? (helps it learn)", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                listOf("Not a task", "Already done", "Was TV / media", "Not mine").forEach { r -> SuggestionChip(onClick = { scope.launch { g.tasks.dismiss(t, r) } }, label = { Text(r) }) }
            }
        }
    }
}

// ───────────────────────────── Task detail ─────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskScreen(id: Long) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val task by remember(id) { g.db.tasks().observe(id) }.collectAsStateWithLifecycle(null)
    val subtasks by remember(id) { g.db.tasks().observeSubtasks(id) }.collectAsStateWithLifecycle(emptyList())
    val projects by remember { g.db.projects().observe() }.collectAsStateWithLifecycle(emptyList())
    val t = task ?: return
    var title by remember(t.id) { mutableStateOf(t.title) }
    var notes by remember(t.id) { mutableStateOf(t.detail.orEmpty()) }
    var newSub by remember { mutableStateOf("") }
    var pickDue by remember { mutableStateOf(false) }
    var pickRemind by remember { mutableStateOf(false) }
    var repeatMenu by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var labelDialog by remember { mutableStateOf(false) }

    fun persistText() {
        if (title.trim() != t.title || notes.trim() != t.detail.orEmpty()) scope.launch { g.tasks.save(t.copy(title = title.trim().ifEmpty { t.title }, detail = notes.trim().ifEmpty { null })) }
    }
    androidx.compose.runtime.DisposableEffect(t.id) { onDispose { persistText() } }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.ime)) {
        ScreenTopBar(if (t.status == "suggested") "Suggested task" else "Task", onBack = { persistText(); nav.back() }) {
            IconButton(onClick = { scope.launch { g.tasks.delete(t); nav.back() } }) { Icon(Icons.Rounded.Delete, "Delete") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = Sp.gutter, vertical = Sp.s), verticalArrangement = Arrangement.spacedBy(Sp.m)) {
            item {
                Row(verticalAlignment = Alignment.Top) {
                    TaskCheck(t.status == "done", t.priority) { scope.launch { if (t.status == "done") g.tasks.reopen(t) else { persistText(); g.tasks.complete(t) } } }
                    TextField(title, { title = it }, Modifier.weight(1f), textStyle = MaterialTheme.typography.titleLarge,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                }
                TextField(notes, { notes = it }, Modifier.fillMaxWidth(), placeholder = { Text("Notes") }, minLines = 2,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Sp.s), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    AssistChip(onClick = { pickDue = true }, label = { Text(t.dueAt?.let { Fmt.dayTime(it) } ?: "Date") }, leadingIcon = { Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp)) },
                        trailingIcon = if (t.dueAt != null) ({ Icon(Icons.Rounded.Close, "Clear", Modifier.size(16.dp).clickable { scope.launch { g.tasks.reschedule(t, null) } }) }) else null)
                    AssistChip(onClick = { pickRemind = true }, label = { Text(t.remindAt?.let { Fmt.dayTime(it) } ?: "Reminder") }, leadingIcon = { Icon(Icons.Rounded.Alarm, null, Modifier.size(18.dp)) },
                        trailingIcon = if (t.remindAt != null) ({ Icon(Icons.Rounded.Close, "Clear", Modifier.size(16.dp).clickable { scope.launch { g.tasks.setReminder(t, null) } }) }) else null)
                    Box {
                        AssistChip(onClick = { repeatMenu = true }, label = { Text(Recurrence.label(t.recurrence) ?: "Repeat") }, leadingIcon = { Icon(Icons.Rounded.Repeat, null, Modifier.size(18.dp)) })
                        DropdownMenu(repeatMenu, { repeatMenu = false }) {
                            DropdownMenuItem(text = { Text("Does not repeat") }, onClick = { repeatMenu = false; scope.launch { g.tasks.save(t.copy(recurrence = null)) } })
                            Recurrence.presets.forEach { (rule, label) -> DropdownMenuItem(text = { Text(label) }, onClick = {
                                repeatMenu = false
                                scope.launch { g.tasks.save(t.copy(recurrence = rule, dueAt = t.dueAt ?: at(LocalDate.now(), LocalTime.of(9, 0)))) }
                            }) }
                            val dow = (t.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).dayOfWeek } ?: LocalDate.now().dayOfWeek).name.take(3)
                            DropdownMenuItem(text = { Text("Every ${dow.lowercase().replaceFirstChar(Char::uppercase)}") }, onClick = { repeatMenu = false; scope.launch { g.tasks.save(t.copy(recurrence = "weekly:$dow")) } })
                            DropdownMenuItem(text = { Text("Every 2 weeks") }, onClick = { repeatMenu = false; scope.launch { g.tasks.save(t.copy(recurrence = "every:2:weeks")) } })
                        }
                    }
                    Box {
                        AssistChip(onClick = { listMenu = true }, label = { Text(projects.firstOrNull { it.id == t.projectId }?.name ?: "Inbox") }, leadingIcon = { Icon(Icons.Rounded.FormatListBulleted, null, Modifier.size(18.dp)) })
                        DropdownMenu(listMenu, { listMenu = false }) {
                            DropdownMenuItem(text = { Text("Inbox") }, onClick = { listMenu = false; scope.launch { g.tasks.save(t.copy(projectId = null)) } })
                            projects.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.color(p.color))) },
                                onClick = { listMenu = false; scope.launch { g.tasks.save(t.copy(projectId = p.id)) } }) }
                            DropdownMenuItem(text = { Text("Manage lists…") }, onClick = { listMenu = false; nav.go(ListsRoute) })
                        }
                    }
                    AssistChip(onClick = { labelDialog = true }, label = { Text(labelsOf(t).joinToString { "@$it" }.ifEmpty { "Labels" }) }, leadingIcon = { Icon(Icons.Rounded.Label, null, Modifier.size(18.dp)) })
                }
            }
            item {
                Text("Priority", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    (1..4).forEach { p ->
                        FilterChip(t.priority == p, { scope.launch { g.tasks.save(t.copy(priority = p)) } }, label = { Text(if (p == 4) "None" else "P$p") },
                            leadingIcon = { Icon(Icons.Rounded.Flag, null, tint = priorityColor(p), modifier = Modifier.size(16.dp)) })
                    }
                }
            }
            item { SectionHeader("Subtasks${if (subtasks.isNotEmpty()) " · ${subtasks.count { it.status == "done" }}/${subtasks.size}" else ""}") }
            items(subtasks, key = { "s${it.id}" }) { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TaskCheck(s.status == "done", 4) { scope.launch { g.tasks.toggleSubtask(s) } }
                    Text(s.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                        textDecoration = if (s.status == "done") TextDecoration.LineThrough else null)
                    IconButton(onClick = { scope.launch { g.tasks.delete(s) } }) { Icon(Icons.Rounded.Close, "Remove", tint = MaterialTheme.colorScheme.outline) }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newSub, { newSub = it }, Modifier.weight(1f), placeholder = { Text("Add a subtask") }, singleLine = true)
                    IconButton(enabled = newSub.isNotBlank(), onClick = { val s = newSub; newSub = ""; scope.launch { g.tasks.addSubtask(t, s) } }) { Icon(Icons.Rounded.Add, "Add") }
                }
            }
            item {
                SectionHeader("About")
                t.quote?.let { Text("You said: “$it”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Created ${Fmt.dayTime(t.createdAt)}${t.completedAt?.let { " · completed ${Fmt.dayTime(it)}" } ?: ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                t.conversationId?.takeIf { it > 0 }?.let { cid -> TextButton(onClick = { nav.go(ConversationRoute(cid)) }) { Text("Open the conversation") } }
                if (t.status == "suggested") Button(onClick = { persistText(); scope.launch { g.tasks.accept(t) } }) { Text("Accept") }
            }
        }
    }
    if (pickDue) DateTimeDialog(t.dueAt) { ms -> pickDue = false; if (ms != null) scope.launch { g.tasks.save(t.copy(dueAt = ms)) } }
    if (pickRemind) ReminderSheet(t.remindAt ?: t.dueAt, onPick = { ms -> pickRemind = false; if (ms != null) scope.launch { g.tasks.setReminder(t, ms) } })
    if (labelDialog) {
        var text by remember { mutableStateOf(labelsOf(t).joinToString(", ")) }
        AlertDialog(onDismissRequest = { labelDialog = false }, title = { Text("Labels") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Comma separated, e.g. phone, errands") }) },
            confirmButton = { TextButton(onClick = {
                labelDialog = false
                scope.launch { g.tasks.save(t.copy(labels = encodeLabels(text.split(',').map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() }.distinct()))) }
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { labelDialog = false }) { Text("Cancel") } })
    }
}

/** Reminder time: presets, then a picker. */
@Composable
fun ReminderSheet(current: Long?, onPick: (Long?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { onPick(null) }) {
        Column(Modifier.padding(horizontal = Sp.xl).padding(bottom = Sp.xxl)) {
            Text("Remind me", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Sp.s))
            timePresets().forEach { (label, ms) ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onPick(ms) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Alarm, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(Sp.l)); Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { picking = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(Sp.l)); Text("Pick date & time…", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    if (picking) DateTimeDialog(current) { ms -> picking = false; if (ms != null) onPick(ms) }
}

@Composable
fun DateTimeDialog(initial: Long?, done: (Long?) -> Unit) {
    val start = initial?.let { Instant.ofEpochMilli(it).atZone(zone) }
    val date = rememberDatePickerState(initialSelectedDateMillis = (start?.toLocalDate() ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    val time = rememberTimePickerState(initialHour = start?.hour ?: 9, initialMinute = start?.minute ?: 0)
    var step by remember { mutableIntStateOf(0) }
    if (step == 0) {
        DatePickerDialog(
            onDismissRequest = { done(null) },
            confirmButton = { TextButton(onClick = { step = 1 }) { Text("Next") } },
            dismissButton = { TextButton(onClick = { done(null) }) { Text("Cancel") } },
        ) { DatePicker(date) }
    } else {
        AlertDialog(
            onDismissRequest = { done(null) },
            text = { TimePicker(time) },
            confirmButton = {
                TextButton(onClick = {
                    val d = Instant.ofEpochMilli(date.selectedDateMillis ?: System.currentTimeMillis()).atZone(ZoneOffset.UTC).toLocalDate()
                    done(at(d, LocalTime.of(time.hour, time.minute)))
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { done(null) }) { Text("Cancel") } },
        )
    }
}

// ───────────────────────────── Reminders ─────────────────────────────

@Composable
fun RemindersScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val reminders by remember { g.db.tasks().observeAllReminders() }.collectAsStateWithLifecycle(emptyList())
    var adding by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val groups = listOf(
        "Missed" to { r: Long -> r < now },
        "Today" to { r: Long -> r >= now && r < startOf(today.plusDays(1)) },
        "Tomorrow" to { r: Long -> r >= startOf(today.plusDays(1)) && r < startOf(today.plusDays(2)) },
        "This week" to { r: Long -> r >= startOf(today.plusDays(2)) && r < startOf(today.plusDays(7)) },
        "Later" to { r: Long -> r >= startOf(today.plusDays(7)) },
    )
    Scaffold(
        topBar = { ScreenTopBar("Reminders", onBack = { nav.back() }) },
        floatingActionButton = { androidx.compose.material3.ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Alarm, null) }, text = { Text("Reminder") }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            if (reminders.isEmpty()) item { EmptyState("No reminders", "Say \"remind me…\" out loud, accept a suggestion, or tap Reminder.") }
            for ((label, test) in groups) {
                val list = reminders.filter { test(it.remindAt!!) }
                if (list.isEmpty()) continue
                item(key = "h$label") { SectionHeader("$label · ${list.size}") }
                items(list, key = { "r${it.id}" }) { t -> ReminderCard(t, missed = label == "Missed", onOpen = { nav.go(TaskRoute(t.id)) }) }
            }
        }
    }
    if (adding) NewReminderDialog { title, at -> adding = false; if (title != null && at != null) scope.launch { g.tasks.addByUser(title, at) } }
}

@Composable
private fun ReminderCard(t: TaskRow, missed: Boolean, onOpen: () -> Unit) {
    val g = App.graph
    val scope = rememberCoroutineScope()
    val r = t.remindAt ?: return
    EchoCard(onClick = onOpen, color = if (missed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(76.dp)) {
                Text(Fmt.time(r), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text(Fmt.day(r), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val rel = relative(r)
                Text(listOfNotNull(rel, Recurrence.label(t.recurrence)).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(Sp.s))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InputChip(false, { scope.launch { g.tasks.complete(t) } }, label = { Text("Done") }, leadingIcon = { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) })
            InputChip(false, { scope.launch { g.tasks.snooze(t.id, 15) } }, label = { Text("+15m") })
            InputChip(false, { scope.launch { g.tasks.snooze(t.id, 60) } }, label = { Text("+1h") })
            InputChip(false, { scope.launch { g.tasks.setReminder(t, at(LocalDate.now().plusDays(1), LocalTime.of(9, 0))) } }, label = { Text("Tomorrow") })
        }
    }
}

private fun relative(at: Long, now: Long = System.currentTimeMillis()): String {
    val d = at - now
    val m = kotlin.math.abs(d) / 60_000
    val s = when {
        m < 1 -> "now"
        m < 60 -> "$m min"
        m < 24 * 60 -> "${m / 60} h ${m % 60} min".replace(" 0 min", "")
        else -> "${m / (24 * 60)} days"
    }
    return if (d >= 0) "in $s" else "$s ago"
}

@Composable
private fun NewReminderDialog(done: (String?, Long?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var at by remember { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    val parsed = remember(title) { if (title.isBlank()) null else QuickAdd.parse(title) }
    AlertDialog(
        onDismissRequest = { done(null, null) },
        title = { Text("New reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                OutlinedTextField(title, { title = it }, label = { Text("Remind me to… (\"kal 5 baje\" works)") }, modifier = Modifier.fillMaxWidth())
                val understood = parsed?.due?.takeIf { it.hasTime }?.at
                if (understood != null && at == null) Text("Will remind ${Fmt.dayTime(understood)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(timePresets().take(5)) { (label, ms) -> FilterChip(at == ms, { at = ms }, label = { Text(label) }) }
                    item { FilterChip(false, { picking = true }, label = { Text("Pick…") }) }
                }
                at?.let { Text("At ${Fmt.dayTime(it)}", style = MaterialTheme.typography.labelMedium) }
            }
        },
        confirmButton = {
            val when_ = at ?: parsed?.due?.takeIf { it.hasTime }?.at
            TextButton(enabled = title.isNotBlank() && when_ != null, onClick = { done(parsed?.title ?: title, when_) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { done(null, null) }) { Text("Cancel") } },
    )
    if (picking) DateTimeDialog(at) { ms -> picking = false; if (ms != null) at = ms }
}

// ───────────────────────────── Lists ─────────────────────────────

@Composable
fun ListsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val projects by remember { g.db.projects().observe() }.collectAsStateWithLifecycle(emptyList())
    val open by remember { g.db.tasks().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<Project?>(null) }
    var creating by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { ScreenTopBar("Lists", onBack = { nav.back() }) },
        floatingActionButton = { androidx.compose.material3.ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("List") }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            item {
                EchoCard {
                    Text("Inbox", style = MaterialTheme.typography.titleMedium)
                    Text("${open.count { it.projectId == null }} open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(projects, key = { it.id }) { p ->
                EchoCard(onClick = { editing = p }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Palette.color(p.color)))
                        Spacer(Modifier.width(Sp.m))
                        Column {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text("${open.count { it.projectId == p.id }} open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { Text("Tip: type #name in quick add to file a task — a new list is created if needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
    if (creating || editing != null) {
        val p = editing
        var name by remember(p) { mutableStateOf(p?.name.orEmpty()) }
        var color by remember(p) { mutableIntStateOf(p?.color ?: 1) }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (p == null) "New list" else "Edit list") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.m)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    ColorRow(color) { color = it }
                    if (p != null) TextButton(onClick = { scope.launch { g.db.projects().detachTasks(p.id); g.db.projects().delete(p.id) }; editing = null }) {
                        Text("Delete list (tasks move to Inbox)", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                scope.launch {
                    if (p == null) g.db.projects().insert(Project(name = name.trim(), color = color, sortOrder = projects.size, createdAt = System.currentTimeMillis()))
                    else g.db.projects().update(p.copy(name = name.trim(), color = color))
                }
                creating = false; editing = null
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ColorRow(selected: Int, includeNone: Boolean = false, onPick: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(if (includeNone) (0..Palette.size).toList() else (1..Palette.size).toList()) { i ->
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(if (i == 0) MaterialTheme.colorScheme.surfaceContainerHighest else Palette.color(i))
                    .border(if (i == selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable { onPick(i) },
            )
        }
    }
}
