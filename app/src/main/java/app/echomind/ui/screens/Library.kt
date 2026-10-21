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
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Psychology
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

@Composable
fun LibraryScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val notes by remember { g.db.notes().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val facts by remember { g.db.memory().observeActiveFacts() }.collectAsStateWithLifecycle(emptyList())
    val people by remember { g.db.memory().observeEntities() }.collectAsStateWithLifecycle(emptyList())
    val days by remember { g.db.days().observeRecent(30) }.collectAsStateWithLifecycle(emptyList())
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.m)) {
        item { Text("Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = Sp.s)) }
        item { LibraryEntry(Icons.Rounded.StickyNote2, "Notes", "${notes.size} living notes that update themselves") { nav.go(NotesRoute) } }
        item { LibraryEntry(Icons.Rounded.Psychology, "Memory", "${facts.size} things it remembers about your life") { nav.go(MemoryRoute) } }
        item { LibraryEntry(Icons.Rounded.Groups, "People", "${people.count { it.kind == "person" }} people from your conversations") { nav.go(PeopleRoute) } }
        item { LibraryEntry(Icons.Rounded.AutoStories, "Journal", "${days.count { it.generatedAt != null }} days written · things to improve") { nav.go(JournalRoute) } }
        item { LibraryEntry(Icons.Rounded.Shield, "Data ledger", "Every time anything left this phone") { nav.go(LedgerRoute) } }
        val recent = notes.take(4)
        if (recent.isNotEmpty()) {
            item { SectionHeader("Recently updated notes") }
            items(recent, key = { it.id }) { n -> NoteCard(n) { nav.go(NoteRoute(n.id)) } }
        }
    }
}

@Composable
private fun LibraryEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    EchoCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(Sp.l))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NoteCard(n: Note, onClick: () -> Unit) {
    EchoCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (n.pinned) Icon(Icons.Rounded.PushPin, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            if (n.locked) Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        }
        Text(n.body.lines().filter { it.isNotBlank() && !it.startsWith("#") }.take(3).joinToString("\n") { it.removePrefix("- ").let { l -> "• $l" } },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text("Updated ${Fmt.ago(n.updatedAt)}${if (n.version > 1) " · v${n.version}" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun NotesScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val notes by remember { g.db.notes().observeAll() }.collectAsStateWithLifecycle(emptyList())
    Scaffold(
        topBar = { ScreenTopBar("Notes", onBack = { nav.back() }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { nav.go(NewNoteRoute) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Note") }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(Sp.gutter, Sp.s, Sp.gutter, 96.dp), verticalArrangement = Arrangement.spacedBy(Sp.m)) {
            if (notes.isEmpty()) item { EmptyState("No notes yet", "When conversations keep coming back to a topic — a project, a trip, a health issue — a note forms and keeps itself up to date.") }
            items(notes, key = { it.id }) { n -> NoteCard(n) { nav.go(NoteRoute(n.id)) } }
        }
    }
}

/** A living note: AI-written lines in a softer tone, your own lines in full contrast, with its history of changes. */
@Composable
fun NoteScreen(id: Long?) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val note by remember(id) { id?.let { g.db.notes().observe(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsStateWithLifecycle(null)
    val revisions by remember(id) { id?.let { g.db.notes().observeRevisions(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    var editing by remember(id) { mutableStateOf(id == null) }
    var title by remember(note?.id) { mutableStateOf(note?.title.orEmpty()) }
    var body by remember(note?.id, note?.version) { mutableStateOf(note?.body.orEmpty()) }
    var history by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        ScreenTopBar(if (id == null) "New note" else if (editing) "Edit note" else "", onBack = { nav.back() }) {
            val n = note
            if (n != null && !editing) {
                IconButton(onClick = { scope.launch { g.db.notes().update(n.copy(pinned = !n.pinned)) } }) { Icon(Icons.Rounded.PushPin, if (n.pinned) "Unpin" else "Pin", tint = if (n.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
                IconButton(onClick = { scope.launch { g.db.notes().update(n.copy(locked = !n.locked)) } }) { Icon(if (n.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, if (n.locked) "Let AI update" else "Stop AI updates") }
                IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Edit") }
            }
            if (editing) TextButton(enabled = title.isNotBlank() || body.isNotBlank(), onClick = {
                scope.launch {
                    val n = note
                    if (n == null) { val newId = g.notes.createByUser(title, body); nav.back(); nav.go(NoteRoute(newId)) }
                    else { g.notes.saveByUser(n, title, body); editing = false }
                }
            }) { Text("Save") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            if (editing) {
                item { OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(body, { body = it }, label = { Text("Note (\"## Heading\" and \"- point\" lines)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp)) }
                if (note != null) item { Text("Lines you write are yours: the AI will never change or remove them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                return@LazyColumn
            }
            val n = note ?: return@LazyColumn
            item {
                Text(n.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Updated ${Fmt.ago(n.updatedAt)} · ${if (n.locked) "AI updates off" else "Kept up to date by AI"}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Sp.s))
            }
            val mine = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), n.userLines) }.getOrDefault(emptyList()).map { it.trim() }.toSet()
            items(n.body.lines().withIndex().toList(), key = { it.index }) { (_, line) ->
                val t = line.trim()
                when {
                    t.startsWith("#") -> Text(t.trimStart('#', ' '), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Sp.s))
                    t.isEmpty() -> Spacer(Modifier.height(4.dp))
                    else -> Text(
                        if (t.startsWith("- ")) "•  ${t.removePrefix("- ")}" else t,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (t in mine) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (revisions.isNotEmpty()) {
                item {
                    SectionHeader("History · ${revisions.size} changes", action = if (history) "Hide" else "Show", onAction = { history = !history })
                }
                if (history) items(revisions, key = { "r${it.id}" }) { r ->
                    EchoCard(onClick = r.conversationId?.takeIf { it > 0 }?.let { { nav.go(ConversationRoute(it)) } }) {
                        Text(r.reason, style = MaterialTheme.typography.bodyMedium)
                        Text("${Fmt.dayTime(r.createdAt)} · ${if (r.by == "user") "you" else "AI"} · v${r.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        val added = r.after.lines().map { it.trim() }.filter { it.isNotEmpty() && it !in r.before.lines().map { b -> b.trim() } }
                        val removed = r.before.lines().map { it.trim() }.filter { it.isNotEmpty() && it !in r.after.lines().map { a -> a.trim() } }
                        added.take(4).forEach { Text("+ $it", style = MaterialTheme.typography.bodySmall, color = LocalSemantic.current.positive) }
                        removed.take(4).forEach { Text("− $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item {
                Spacer(Modifier.height(Sp.l))
                TextButton(onClick = { scope.launch { g.db.notes().delete(n.id); nav.back() } }) { Text("Delete note", color = MaterialTheme.colorScheme.error) }
            }
        }
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
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(Sp.gutter, Sp.s, Sp.gutter, 96.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
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
fun PeopleScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val all by remember { g.db.memory().observeEntities() }.collectAsStateWithLifecycle(emptyList())
    var kind by remember { mutableStateOf("person") }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("People & things", onBack = { nav.back() })
        Row(Modifier.padding(horizontal = Sp.gutter), horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
            listOf("person" to "People", "project" to "Projects", "place" to "Places", "topic" to "Topics").forEach { (k, l) -> FilterChip(kind == k, { kind = k }, label = { Text(l) }) }
        }
        val list = all.filter { it.kind == kind || (kind == "topic" && it.kind == "org") }
        LazyColumn(contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            if (list.isEmpty()) item { EmptyState("Nobody yet", "People appear as they come up in your conversations.") }
            items(list, key = { it.id }) { e ->
                EchoCard(onClick = { nav.go(PersonRoute(e.id)) }) {
                    Text(e.name, style = MaterialTheme.typography.titleMedium)
                    if (e.summary.isNotBlank()) Text(e.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Mentioned ${e.mentions}× · last ${Fmt.ago(e.lastSeenAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
fun PersonScreen(id: Long) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val e by remember(id) { g.db.memory().observeEntity(id) }.collectAsStateWithLifecycle(null)
    val facts by remember(id) { g.db.memory().observeFactsFor(id) }.collectAsStateWithLifecycle(emptyList())
    val convs by produceState(emptyList<app.echomind.data.Conversation>(), e?.name) {
        val name = e?.name?.lowercase() ?: return@produceState
        value = g.db.conversations().inRangeOnce(0, Long.MAX_VALUE).filter { (it.people ?: "").lowercase().contains(name) || it.contact?.lowercase()?.contains(name) == true }.sortedByDescending { it.startAt }.take(20)
    }
    val p = e ?: return
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar(p.name, onBack = { nav.back() }) { IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Edit") } }
        LazyColumn(contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            item {
                Text(p.summary.ifBlank { "No summary yet." }, style = MaterialTheme.typography.bodyLarge)
                Text("First heard ${Fmt.day(p.firstSeenAt)} · ${p.mentions} mentions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(Sp.s))
                Button(onClick = { nav.go(app.echomind.ui.AskRoute(question = "What's going on with ${p.name}?")) }) { Text("Ask about ${p.name.substringBefore(' ')}") }
            }
            if (facts.isNotEmpty()) {
                item { SectionHeader("What it knows") }
                items(facts, key = { it.id }) { f ->
                    Text("•  ${f.statement}${if (f.status != "active") "  (no longer current)" else ""}", style = MaterialTheme.typography.bodyLarge,
                        color = if (f.status == "active") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                }
            }
            if (convs.isNotEmpty()) {
                item { SectionHeader("Conversations") }
                items(convs, key = { "c${it.id}" }) { c -> ConversationRow(c) { nav.go(ConversationRoute(c.id)) } }
            }
            item {
                Spacer(Modifier.height(Sp.l))
                TextButton(onClick = { scope.launch { g.db.memory().deleteEntity(p.id); nav.back() } }) { Text("Remove ${p.name}", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (editing) {
        var name by remember { mutableStateOf(p.name) }
        var summary by remember { mutableStateOf(p.summary) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text("Edit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(summary, { summary = it }, label = { Text("Who they are") }, minLines = 2)
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { g.db.memory().updateEntity(p.copy(name = name.trim(), summary = summary.trim(), pinned = true)) }; editing = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
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
        LazyColumn(contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.m)) {
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
                    d.diary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar(Fmt.dayLong(date), onBack = { nav.back() }) { IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Edit") } }
        val e = d
        LazyColumn(contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            item {
                Text(e?.headline ?: "Not written yet", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(Sp.s))
                Text(e?.diary ?: "", style = MaterialTheme.typography.bodyLarge)
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
