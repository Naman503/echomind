package app.echomind.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.DayMetrics
import app.echomind.brain.Improvement
import app.echomind.brain.Memory
import app.echomind.data.Fact
import app.echomind.data.Note
import app.echomind.ui.ConversationRoute
import app.echomind.ui.DayRoute
import app.echomind.ui.Fmt
import app.echomind.ui.JournalRoute
import app.echomind.ui.LedgerRoute
import app.echomind.ui.LocalNav
import app.echomind.ui.MemoryRoute
import app.echomind.ui.NewNoteRoute
import app.echomind.ui.NoteRoute
import app.echomind.ui.NotesRoute
import app.echomind.ui.PeopleRoute
import app.echomind.ui.PersonRoute
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.Pill
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.Tile
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import app.echomind.util.Js
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate

/** "You": everything that is yours — knowledge, what was captured, privacy — plus settings. */
@Composable
fun LibraryScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val notes by remember { g.db.notes().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val facts by remember { g.db.memory().observeActiveFacts() }.collectAsStateWithLifecycle(emptyList())
    val people by remember { g.db.memory().observeEntities() }.collectAsStateWithLifecycle(emptyList())
    val days by remember { g.db.days().observeRecent(400) }.collectAsStateWithLifecycle(emptyList())
    val songs by remember { g.db.sounds().observeSongs() }.collectAsStateWithLifecycle(emptyList())
    val live = notes.filter { !it.archived }
    val persons = people.count { it.kind == "person" }
    val streak = run {
        var n = 0; var d = LocalDate.now()
        val written = days.filter { !it.diary.isNullOrBlank() }.map { it.day }.toSet()
        if (d.toString() !in written) d = d.minusDays(1)
        while (d.toString() in written) { n++; d = d.minusDays(1) }
        n
    }
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(Sp.m), state = app.echomind.ui.components.rememberKeptListState()) {
        item {
            app.echomind.ui.components.TabHeader(s.userName.ifBlank { "You" }, listOfNotNull(
                "${days.count { !it.diary.isNullOrBlank() }} days written",
                streak.takeIf { it > 1 }?.let { "$it-day streak" },
                "$persons people",
            ).joinToString(" · ")) {
                IconButton(onClick = { nav.go(app.echomind.ui.SettingsRoute) }) { Icon(Icons.Rounded.Settings, "Settings") }
            }
        }
        item {
            SectionHeader("Your knowledge")
            app.echomind.ui.components.NavGroup {
                app.echomind.ui.components.NavRow(Icons.Rounded.StickyNote2, "Notes", "Living notes on your projects and topics", trailing = "${live.size}") { nav.go(NotesRoute) }
                app.echomind.ui.components.NavRow(Icons.Rounded.Groups, "People", "Who they are, how you know them", trailing = "$persons") { nav.go(PeopleRoute) }
                app.echomind.ui.components.NavRow(Icons.Rounded.Category, "Things", "Shows, places, apps, books… you talked about", trailing = "${people.count { it.kind in app.echomind.brain.Things.KEYS }}") { nav.go(app.echomind.ui.ThingsRoute) }
                app.echomind.ui.components.NavRow(Icons.Rounded.Psychology, "Memory", "Facts EchoMind remembers — fix anything wrong", trailing = "${facts.size}") { nav.go(MemoryRoute) }
                app.echomind.ui.components.NavRow(Icons.Rounded.Forum, "Past questions", "Everything you asked") { nav.go(app.echomind.ui.ChatsRoute) }
            }
        }
        item {
            SectionHeader("Captured")
            app.echomind.ui.components.NavGroup {
                app.echomind.ui.components.NavRow(Icons.Rounded.Mic, "Recordings", "Kept ${retentionLabel(s.keepRecordingsDays)}", tint = MaterialTheme.colorScheme.tertiary) { nav.go(app.echomind.ui.RecordingsRoute) }
                app.echomind.ui.components.NavRow(Icons.Rounded.MusicNote, "Songs", "What you sang", tint = MaterialTheme.colorScheme.tertiary, trailing = songs.size.takeIf { it > 0 }?.toString()) { nav.go(app.echomind.ui.SongsRoute) }
                app.echomind.ui.components.NavRow(Icons.Rounded.QuestionAnswer, "Check-ins", "Your answers to the daily questions", tint = MaterialTheme.colorScheme.tertiary) { nav.go(app.echomind.ui.CheckInsRoute) }
            }
        }
        item {
            SectionHeader("Organise & privacy")
            app.echomind.ui.components.NavGroup {
                app.echomind.ui.components.NavRow(Icons.Rounded.AutoAwesome, "Organise", "Folders, tags and circles", tint = MaterialTheme.colorScheme.secondary) { nav.go(app.echomind.ui.OrganizeRoute) }
                app.echomind.ui.components.NavRow(Icons.Rounded.Shield, "Data ledger", "Everything that ever left the phone", tint = MaterialTheme.colorScheme.secondary) { nav.go(LedgerRoute) }
            }
        }
        item {
            SectionHeader("App")
            app.echomind.ui.components.NavGroup {
                app.echomind.ui.components.NavRow(Icons.Rounded.Settings, "Settings", "Recording, AI, journal, reminders, appearance", tint = MaterialTheme.colorScheme.outline) { nav.go(app.echomind.ui.SettingsRoute) }
                app.echomind.ui.components.NavRow(Icons.Rounded.Thermostat, "Phone health", "Temperature, battery and work limits", tint = MaterialTheme.colorScheme.outline) { nav.go(app.echomind.ui.HealthRoute) }
                app.echomind.ui.components.NavRow(Icons.Rounded.Flight, "Travel mode", if (s.travel) "On — EchoMind is resting" else "Save battery on trips", tint = MaterialTheme.colorScheme.outline) { nav.go(app.echomind.ui.TravelRoute) }
            }
        }
        if (live.isNotEmpty()) {
            item { SectionHeader("Recent notes", action = "All notes", onAction = { nav.go(NotesRoute) }) }
            items(live.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt }).take(3), key = { it.id }) { n -> NoteCard(n) { nav.go(NoteRoute(n.id)) } }
        }
    }
}

@Composable
private fun LibraryTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    EchoCard(onClick = onClick, modifier = modifier, padding = PaddingValues(Sp.m)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(Sp.xs))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val kindLabels = linkedMapOf(
    "relationship" to "People & relationships", "goal" to "Goals", "open_loop" to "Open loops", "routine" to "Routines",
    "preference" to "Preferences", "event" to "Events & plans", "fact" to "Facts",
)

@Composable
fun MemoryScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val facts by remember { g.db.memory().observeActiveFacts() }.collectAsStateWithLifecycle(emptyList())
    val former by remember { g.db.memory().observeFormerFacts() }.collectAsStateWithLifecycle(emptyList())
    val profile by remember { g.db.memory().observeKv(Memory.PROFILE) }.collectAsStateWithLifecycle(null)
    var editProfile by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var showFormer by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { ScreenTopBar("Memory", onBack = { nav.back() }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Remember") }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(Sp.gutter, Sp.s, Sp.gutter, 96.dp), verticalArrangement = Arrangement.spacedBy(Sp.s), state = app.echomind.ui.components.rememberKeptListState()) {
            item {
                Text("Each night the AI reviews the day: it keeps what will matter later, updates what changed, and lets go of what's finished. Pinned memories are never dropped.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                SectionHeader("About you", action = "Edit", onAction = { editProfile = true })
                EchoCard { Text(profile?.ifBlank { null } ?: "Builds up after the first few days.", style = MaterialTheme.typography.bodyMedium) }
            }
            item { OutlinedButton(onClick = { nav.go(PeopleRoute) }) { Text("People") } }
            for ((kind, label) in kindLabels) {
                val list = facts.filter { it.kind == kind }
                if (list.isEmpty()) continue
                item(key = "h$kind") { SectionHeader(label) }
                items(list, key = { "f${it.id}" }) { f -> FactRow(f) }
            }
            if (facts.isEmpty()) item { EmptyState("Nothing remembered yet", "Facts appear as conversations are turned into notes.") }
            if (former.isNotEmpty()) {
                item { SectionHeader("No longer current", action = if (showFormer) "Hide" else "Show", onAction = { showFormer = !showFormer }) }
                if (showFormer) items(former, key = { "o${it.id}" }) { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${f.subject}: ${f.statement}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (f.status == "superseded") "Replaced ${f.validTo?.let { Fmt.ago(it) } ?: ""}" else f.archivedReason ?: "Archived",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontStyle = FontStyle.Italic)
                        }
                        TextButton(onClick = { scope.launch { g.memory.restore(f) } }) { Text("Restore") }
                    }
                }
            }
        }
    }
    if (editProfile) {
        var text by remember { mutableStateOf(profile.orEmpty()) }
        AlertDialog(onDismissRequest = { editProfile = false }, title = { Text("About you") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp)) },
            confirmButton = { TextButton(onClick = { scope.launch { g.memory.setProfile(text) }; editProfile = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editProfile = false }) { Text("Cancel") } })
    }
    if (adding) {
        var subject by remember { mutableStateOf("") }
        var statement by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("Something to remember") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    OutlinedTextField(subject, { subject = it }, label = { Text("About (person, thing, or leave empty for you)") }, singleLine = true)
                    OutlinedTextField(statement, { statement = it }, label = { Text("What should it know?") }, minLines = 2)
                }
            },
            confirmButton = { TextButton(enabled = statement.isNotBlank(), onClick = { scope.launch { g.memory.addByUser(subject.ifBlank { "Me" }, statement) }; adding = false }) { Text("Remember") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    }
}

@Composable
private fun FactRow(f: Fact) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    EchoCard(onClick = f.conversationId?.let { { nav.go(ConversationRoute(it)) } }, padding = PaddingValues(start = Sp.l, top = Sp.m, bottom = Sp.m, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.subject, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(f.statement, style = MaterialTheme.typography.bodyLarge)
                Text("${"●".repeat(f.importance.coerceIn(1, 5))}${"○".repeat(5 - f.importance.coerceIn(1, 5))} · ${if (f.origin == "user") "added by you" else "since ${Fmt.day(f.validFrom)}"}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            IconButton(onClick = { scope.launch { g.memory.pin(f, !f.pinned) } }) { Icon(Icons.Rounded.PushPin, if (f.pinned) "Unpin" else "Pin", tint = if (f.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) }
            IconButton(onClick = { scope.launch { g.memory.archive(f) } }) { Icon(Icons.Rounded.Close, "Forget this", tint = MaterialTheme.colorScheme.outline) }
        }
    }
}

@Composable
fun JournalScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val days by remember { g.db.days().observeRecent(90) }.collectAsStateWithLifecycle(emptyList())
    var writing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Journal", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.m), state = app.echomind.ui.components.rememberKeptListState()) {
            item {
                Text("Each evening the AI writes your day: what happened, what's unresolved, and a few gentle things to improve.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Sp.s))
                OutlinedButton(enabled = !writing, onClick = {
                    writing = true
                    scope.launch { g.journal.write(LocalDate.now(), force = true); writing = false }
                }) { Text(if (writing) "Writing…" else "Write today's entry now") }
            }
            val written = days.filter { it.generatedAt != null || it.diary != null }
            if (written.isEmpty()) item { EmptyState("No entries yet", "The first entry is written on the evening of your first day.") }
            items(written, key = { it.day }) { d ->
                EchoCard(onClick = { nav.go(DayRoute(d.day)) }) {
                    Text(Fmt.day(LocalDate.parse(d.day)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(d.headline ?: "Your day", style = MaterialTheme.typography.titleMedium)
                    d.diary?.let { Text(it, style = app.echomind.ui.components.DiaryStyle.copy(fontSize = 15.sp, lineHeight = 22.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
fun DayScreen(day: String) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val d by remember(day) { g.db.days().observe(day) }.collectAsStateWithLifecycle(null)
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val date = LocalDate.parse(day)
    val dayEvents by produceState(emptyList<app.echomind.data.EventRow>(), day) {
        val (from, to) = g.journal.bounds(date)
        value = g.agenda.range(from, to).filter { it.status == "confirmed" }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar(Fmt.dayLong(date), onBack = { nav.back() }) {
            app.echomind.ui.components.ListenButton("Read this day") { g.briefing.day(date) }
            IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Edit") }
        }
        val e = d
        LazyColumn(contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.s), state = app.echomind.ui.components.rememberKeptListState()) {
            item {
                EchoCard(onClick = { nav.go(app.echomind.ui.DayReviewRoute(day)) }, color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("Open the full day review", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("People, topics, speaking coach, lessons and follow-ups", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            item {
                Text(e?.headline ?: "Not written yet", style = MaterialTheme.typography.headlineSmall)
                val sc = e?.rating?.let { it * 20 } ?: e?.productivity
                if (sc != null) {
                    Spacer(Modifier.height(Sp.s))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill("Productivity $sc", LocalSemantic.current.positive.copy(alpha = 0.15f), LocalSemantic.current.positive)
                        e?.productiveWhy?.let { Spacer(Modifier.width(Sp.s)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Spacer(Modifier.height(Sp.s))
                e?.diary?.takeIf { it.isNotBlank() }?.let { app.echomind.ui.components.DiaryPage(it, date = date) }
                if (e?.userEdited == true) Pill("Edited by you")
            }
            fun list(json: String?) = json?.let { runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()
            listOf("Highlights" to list(e?.highlights), "Wins" to list(e?.wins), "Loose ends" to list(e?.looseEnds), "For tomorrow" to list(e?.tomorrow)).forEach { (title, items) ->
                if (items.isNotEmpty()) {
                    item(key = title) { SectionHeader(title) }
                    items(items, key = { "$title$it" }) { Text("•  $it", style = MaterialTheme.typography.bodyLarge) }
                }
            }
            val improve = e?.improve?.let { runCatching { Js.json.decodeFromString(ListSerializer(Improvement.serializer()), it) }.getOrNull() }.orEmpty()
            if (improve.isNotEmpty()) {
                item { SectionHeader("Things to improve") }
                items(improve, key = { it.observation }) { i ->
                    EchoCard(color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)) {
                        Text(i.observation, style = MaterialTheme.typography.bodyLarge)
                        if (i.suggestion.isNotBlank()) Text("Try: ${i.suggestion}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (dayEvents.isNotEmpty()) {
                item { SectionHeader("On the calendar") }
                items(dayEvents, key = { "ev${it.id}_${it.deviceId}" }) { ev ->
                    Text("${if (ev.allDay) "All day" else Fmt.time(ev.startAt)}  ·  ${ev.title}", style = MaterialTheme.typography.bodyLarge)
                }
            }
            val m = e?.metrics?.let { runCatching { Js.json.decodeFromString(DayMetrics.serializer(), it) }.getOrNull() }
            if (m != null && m.conversations > 0) {
                item { SectionHeader("The day in numbers") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                        Tile("conversations", "${m.conversations}", Modifier.weight(1f))
                        Tile("minutes of talk", "${m.talkMinutes}", Modifier.weight(1f))
                        Tile("people", "${m.people}", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(Sp.s))
                    Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                        Tile("words heard", "${m.words}", Modifier.weight(1f), hint = if (m.myWords > 0) "${m.myWords * 100 / m.words.coerceAtLeast(1)}% yours" else null)
                        Tile("tasks made", "${m.tasksMade}", Modifier.weight(1f), hint = "${m.tasksDone} done")
                        Tile("calls", "${m.calls}", Modifier.weight(1f))
                    }
                }
            }
            item {
                Spacer(Modifier.height(Sp.m))
                OutlinedButton(enabled = !busy, onClick = { busy = true; scope.launch { g.journal.write(date, force = true); busy = false } }) { Text(if (busy) "Writing…" else "Rewrite with AI") }
            }
        }
    }
    if (editing) {
        var text by remember { mutableStateOf(d?.diary.orEmpty()) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text("Your entry") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp)) },
            confirmButton = { TextButton(onClick = { scope.launch { g.journal.saveByUser(day, text) }; editing = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
    }
}
