package app.echomind.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.data.TaskRow
import app.echomind.ui.ConversationRoute
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.Pill
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun TasksScreen() {
    val g = App.graph
    val scope = rememberCoroutineScope()
    val review by remember { g.db.tasks().observeByStatus("suggested") }.collectAsStateWithLifecycle(emptyList())
    val open by remember { g.db.tasks().observeByStatus("open") }.collectAsStateWithLifecycle(emptyList())
    val done by remember { g.db.tasks().observeDone() }.collectAsStateWithLifecycle(emptyList())
    var tab by rememberSaveable { mutableIntStateOf(if (review.isNotEmpty()) 0 else 1) }
    var adding by remember { mutableStateOf(false) }
    var reminderFor by remember { mutableStateOf<TaskRow?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Task") }) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).windowInsetsPadding(WindowInsets.statusBars)) {
            Text("Tasks", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = Sp.gutter, vertical = Sp.s))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Sp.gutter)) {
                listOf("Review${if (review.isNotEmpty()) " · ${review.size}" else ""}", "To do · ${open.size}", "Done").forEachIndexed { i, label ->
                    SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }
            LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, top = Sp.m, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                when (tab) {
                    0 -> {
                        if (review.isEmpty()) item { EmptyState("All reviewed", "When the AI hears a commitment or a request, it waits here for your OK — nothing notifies you until you accept it.") }
                        else item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Found in your conversations. Accept what's real.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                TextButton(onClick = { scope.launch { review.forEach { g.tasks.accept(it) } } }) { Text("Accept all") }
                            }
                        }
                        items(review, key = { it.id }) { t -> ReviewCard(t) }
                    }
                    1 -> {
                        if (open.isEmpty()) item { EmptyState("Nothing to do", "Accepted tasks and your own appear here.") }
                        items(open, key = { it.id }) { t -> TodoRow(t, onReminder = { reminderFor = t }) }
                    }
                    else -> {
                        if (done.isEmpty()) item { EmptyState("Nothing done yet", "Completed tasks are kept here.") }
                        items(done, key = { it.id }) { t ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = true, onCheckedChange = { scope.launch { g.tasks.reopen(t) } })
                                Column(Modifier.weight(1f)) {
                                    Text(t.title, style = MaterialTheme.typography.bodyLarge, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    t.completedAt?.let { Text(Fmt.ago(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) AddTaskDialog { title, at ->
        adding = false
        if (title != null) scope.launch { g.tasks.addByUser(title, at) }
    }
    reminderFor?.let { t ->
        ReminderPicker(t.remindAt) { at, clear ->
            reminderFor = null
            if (at != null || clear) scope.launch { g.tasks.setReminder(t, at) }
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
        val meta = listOfNotNull(t.remindAt?.let { "Reminder ${Fmt.dayTime(it)}" } ?: t.dueText, "${Fmt.ago(t.createdAt)}").joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(Sp.s))
        if (!dismissing) {
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                Button(onClick = { scope.launch { g.tasks.accept(t) } }) { Text(if (t.remindAt != null) "Accept & remind" else "Accept") }
                TextButton(onClick = { dismissing = true }) { Text("Dismiss") }
            }
        } else {
            Text("Why? (helps it learn)", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                listOf("Not a task", "Already done", "Was TV / media", "Not mine").forEach { r ->
                    SuggestionChip(onClick = { scope.launch { g.tasks.dismiss(t, r) } }, label = { Text(r) })
                }
            }
        }
    }
}

@Composable
private fun TodoRow(t: TaskRow, onReminder: () -> Unit) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    EchoCard(onClick = t.conversationId?.let { { nav.go(ConversationRoute(it)) } }, padding = PaddingValues(start = 4.dp, end = Sp.m, top = Sp.s, bottom = Sp.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = false, onCheckedChange = { scope.launch { g.tasks.complete(t) } })
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.bodyLarge)
                val overdue = t.dueAt != null && t.dueAt < System.currentTimeMillis()
                (t.remindAt ?: t.dueAt)?.let { Text((if (overdue) "Overdue · " else "") + Fmt.dayTime(it), style = MaterialTheme.typography.labelMedium,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            TextButton(onClick = onReminder) { Icon(Icons.Rounded.Alarm, "Reminder") }
        }
    }
}

private fun at(day: LocalDate, time: LocalTime) = day.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun presets(): List<Pair<String, Long>> {
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    return listOf(
        "In 1 hour" to now + 3_600_000L,
        "This evening (7 PM)" to at(today, LocalTime.of(19, 0)),
        "Tomorrow morning (9 AM)" to at(today.plusDays(1), LocalTime.of(9, 0)),
        "Tomorrow evening (7 PM)" to at(today.plusDays(1), LocalTime.of(19, 0)),
    ).filter { it.second > now }
}

@Composable
private fun AddTaskDialog(done: (String?, Long?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var at by remember { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { done(null, null) },
        title = { Text("New task") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("What needs doing?") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Sp.s))
                Column {
                    FilledTonalButton(onClick = { menu = true }) { Icon(Icons.Rounded.Alarm, null); Spacer(Modifier.width(6.dp)); Text(at?.let { Fmt.dayTime(it) } ?: "Add reminder") }
                    DropdownMenu(menu, { menu = false }) {
                        presets().forEach { (label, t) -> DropdownMenuItem(text = { Text(label) }, onClick = { at = t; menu = false }) }
                        DropdownMenuItem(text = { Text("Pick date & time…") }, onClick = { menu = false; picking = true })
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { done(title, at) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = { done(null, null) }) { Text("Cancel") } },
    )
    if (picking) DateTimeDialog(at) { picked -> picking = false; if (picked != null) at = picked }
}

@Composable
private fun ReminderPicker(current: Long?, done: (Long?, Boolean) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { done(null, false) },
        title = { Text("Remind me") },
        text = {
            Column {
                presets().forEach { (label, t) -> TextButton(onClick = { done(t, false) }) { Text(label) } }
                TextButton(onClick = { picking = true }) { Text("Pick date & time…") }
                if (current != null) TextButton(onClick = { done(null, true) }) { Text("Remove reminder", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { done(null, false) }) { Text("Cancel") } },
    )
    if (picking) DateTimeDialog(current) { picked -> picking = false; if (picked != null) done(picked, false) }
}

@Composable
fun DateTimeDialog(initial: Long?, done: (Long?) -> Unit) {
    val zone = ZoneId.systemDefault()
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
