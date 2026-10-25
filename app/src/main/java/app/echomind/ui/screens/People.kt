package app.echomind.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.Organizer
import app.echomind.brain.People
import app.echomind.data.EntityRow
import app.echomind.data.Relation
import app.echomind.ui.AskRoute
import app.echomind.ui.CirclesRoute
import app.echomind.ui.ConversationRoute
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.PersonRoute
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.Pill
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import app.echomind.util.Js
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

private val circleOrder = People.CIRCLES

@Composable
private fun circleColor(c: String?): Color = when (c) {
    "family" -> LocalSemantic.current.recording
    "work" -> MaterialTheme.colorScheme.primary
    "friends" -> LocalSemantic.current.positive
    "neighbourhood" -> MaterialTheme.colorScheme.tertiary
    "services" -> LocalSemantic.current.warning
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun Avatar(e: EntityRow, size: Int = 40) {
    val c = circleColor(e.circle)
    Box(Modifier.size(size.dp).background(c.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
        Text(e.name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }, color = c, fontWeight = FontWeight.SemiBold,
            style = if (size >= 56) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge)
    }
}

/** "Sales lead at Eazeebox · my manager" — what someone is, without needing the name. */
private fun quickClue(e: EntityRow) = listOfNotNull(
    listOfNotNull(e.role, e.org?.let { "at $it" }).joinToString(" ").ifBlank { null },
    e.relationToMe?.takeIf { it.isNotBlank() },
).joinToString(" · ").ifBlank { e.summary }

@Composable
fun PeopleScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val all by remember { g.db.memory().observeEntities() }.collectAsStateWithLifecycle(emptyList())
    var kind by rememberSaveable { mutableStateOf("person") }
    var query by rememberSaveable { mutableStateOf("") }
    var practice by remember { mutableStateOf(false) }
    val since = System.currentTimeMillis() - g.settings.value.namesWindowDays * 86_400_000L
    val people = all.filter { it.kind == "person" }
    val recent = remember(people) { people.filter { it.firstSeenAt >= since }.sortedByDescending { it.firstSeenAt }.take(12) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("People", onBack = { nav.back() }) {
            IconButton(onClick = { practice = true }, enabled = people.any { quickClue(it).isNotBlank() }) { Icon(Icons.Rounded.Quiz, "Practise names") }
            IconButton(onClick = { nav.go(CirclesRoute) }) { Icon(Icons.Rounded.AccountTree, "Circles and connections") }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = Sp.gutter), singleLine = true, shape = MaterialTheme.shapes.extraLarge,
            placeholder = { Text("Name, job, company, “the plumber”…") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } })
        LazyRow(contentPadding = PaddingValues(horizontal = Sp.gutter, vertical = Sp.s), horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
            items(listOf("person" to "People", "org" to "Organisations", "project" to "Projects", "place" to "Places", "topic" to "Topics")) { (k, l) -> FilterChip(kind == k, { kind = k }, label = { Text(l) }) }
        }
        val q = query.trim().lowercase()
        val list = all.filter { it.kind == kind }.filter { e ->
            q.isEmpty() || listOfNotNull(e.name, e.role, e.org, e.relationToMe, e.summary, e.aliases, e.story).any { it.lowercase().contains(q) }
        }
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s), state = app.echomind.ui.components.rememberKeptListState()) {
            if (kind == "person" && q.isEmpty() && recent.isNotEmpty()) {
                item { SectionHeader("Names to remember · new this month") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                        items(recent, key = { "r${it.id}" }) { e ->
                            EchoCard(onClick = { nav.go(PersonRoute(e.id)) }, modifier = Modifier.width(200.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(e, 36); Spacer(Modifier.width(Sp.s))
                                    Text(e.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.height(Sp.xs))
                                Text(quickClue(e).ifBlank { "Who is this? Tap to add" }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(e.howMet ?: "First heard ${Fmt.day(e.firstSeenAt)}", style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
            if (list.isEmpty()) item { EmptyState(if (q.isEmpty()) "Nobody yet" else "No match", if (q.isEmpty()) "People appear as they come up in your conversations." else "Try a job, a company, or part of a name.") }
            if (kind == "person") {
                list.groupBy { it.circle ?: "other" }.toSortedMap(compareBy { circleOrder.indexOf(it).let { i -> if (i < 0) 99 else i } }).forEach { (circle, ps) ->
                    item(key = "h$circle") { SectionHeader("${People.circleLabel(circle)} · ${ps.size}") }
                    items(ps.sortedByDescending { it.lastSeenAt }, key = { it.id }) { e -> PersonRow(e) { nav.go(PersonRoute(e.id)) } }
                }
            } else items(list, key = { it.id }) { e -> PersonRow(e) { nav.go(PersonRoute(e.id)) } }
        }
    }
    if (practice) PracticeNames(people.filter { quickClue(it).isNotBlank() }) { practice = false }
}

@Composable
private fun PersonRow(e: EntityRow, onClick: () -> Unit) {
    EchoCard(onClick = onClick, padding = PaddingValues(Sp.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(e)
            Spacer(Modifier.width(Sp.m))
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.titleMedium)
                quickClue(e).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text("Mentioned ${e.mentions}× · last ${Fmt.ago(e.lastSeenAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** Flashcards for names: the clue first, the name on tap. */
@Composable
private fun PracticeNames(pool: List<EntityRow>, onDone: () -> Unit) {
    val deck = remember { pool.sortedByDescending { it.firstSeenAt }.take(20).shuffled() }
    var i by remember { mutableStateOf(0) }
    var shown by remember { mutableStateOf(false) }
    var known by remember { mutableStateOf(0) }
    val e = deck.getOrNull(i)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (e == null) "Done" else "Who is this? ${i + 1} of ${deck.size}") },
        text = {
            if (e == null) Text("You knew $known of ${deck.size}. Names stick better a little at a time — come back tomorrow.")
            else Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                Text(quickClue(e), style = MaterialTheme.typography.titleMedium)
                e.howMet?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                AnimatedVisibility(shown) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Avatar(e, 40); Spacer(Modifier.width(Sp.m)); Text(e.name, style = MaterialTheme.typography.headlineSmall) }
                }
            }
        },
        confirmButton = {
            when {
                e == null -> TextButton(onClick = onDone) { Text("Close") }
                !shown -> TextButton(onClick = { shown = true }) { Text("Show name") }
                else -> Row {
                    TextButton(onClick = { i++; shown = false }) { Text("Didn't know") }
                    TextButton(onClick = { known++; i++; shown = false }) { Text("Knew it") }
                }
            }
        },
        dismissButton = { if (e != null) TextButton(onClick = onDone) { Text("Stop") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonScreen(id: Long) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val e by remember(id) { g.db.memory().observeEntity(id) }.collectAsStateWithLifecycle(null)
    val facts by remember(id) { g.db.memory().observeFactsFor(id) }.collectAsStateWithLifecycle(emptyList())
    val rels by remember(id) { g.db.relations().observeFor(id) }.collectAsStateWithLifecycle(emptyList())
    val everyone by remember { g.db.memory().observeEntities() }.collectAsStateWithLifecycle(emptyList())
    val byId = remember(everyone) { everyone.associateBy { it.id } }
    val convs by produceState(emptyList<app.echomind.data.Conversation>(), e?.name) {
        val name = e?.name?.lowercase() ?: return@produceState
        value = g.db.conversations().inRangeOnce(0, Long.MAX_VALUE).filter { (it.people ?: "").lowercase().contains(name) || it.contact?.lowercase()?.contains(name) == true }.sortedByDescending { it.startAt }.take(20)
    }
    val p = e ?: return
    var editing by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    val details = remember(p.details) { g.people.details(p) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("", onBack = { nav.back() }) {
            app.echomind.ui.components.ListenButton("Read about ${p.name}") {
                app.echomind.brain.Briefing.Script(p.name, listOfNotNull(p.name + ".", quickClue(p).takeIf { it.isNotBlank() }?.let { "$it." }, p.story ?: p.summary.takeIf { it.isNotBlank() }) + details.map { "Remember: $it." })
            }
            IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Edit") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                if (p.kind == "person") DropdownMenuItem(text = { Text("Same person as…") }, onClick = { menu = false; merging = true })
                DropdownMenuItem(text = { Text("Remove ${p.name}", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; scope.launch { g.people.delete(p); nav.back() } })
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s), state = app.echomind.ui.components.rememberKeptListState()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p, 64); Spacer(Modifier.width(Sp.l))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.headlineSmall)
                        quickClue(p).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Spacer(Modifier.height(Sp.s))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    if (p.kind == "person") Pill(People.circleLabel(p.circle), circleColor(p.circle).copy(alpha = 0.15f), circleColor(p.circle))
                    val aliases = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), p.aliases) }.getOrDefault(emptyList())
                    if (aliases.isNotEmpty()) Pill("Also: ${aliases.joinToString(", ")}")
                }
            }
            item {
                EchoCard {
                    Text(p.story ?: p.summary.ifBlank { "Nothing written yet." }, style = MaterialTheme.typography.bodyLarge)
                    p.howMet?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = Sp.xs)) }
                    Row(Modifier.padding(top = Sp.s), horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                        OutlinedButton(enabled = !writing && g.ai.ready(app.echomind.core.Feature.MEMORY), onClick = { writing = true; scope.launch { g.people.writeStory(p.id); writing = false } }) {
                            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(Sp.xs))
                            Text(if (writing) "Writing…" else if (p.story == null) "Write their story" else "Update")
                        }
                        TextButton(onClick = { nav.go(AskRoute(question = "What's going on with ${p.name}?")) }) { Text("Ask") }
                    }
                }
            }
            if (details.isNotEmpty()) {
                item { SectionHeader("Remember next time") }
                items(details, key = { "d$it" }) { Text("•  $it", style = MaterialTheme.typography.bodyLarge) }
            }
            item { SectionHeader("Connections", action = "Add", onAction = { connecting = true }) }
            if (rels.isEmpty()) item { Text("No connections yet. Add who they work with, family, or where they work.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rels, key = { "rel${it.id}" }) { r -> RelationRow(r, p.id, byId) }
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
            item { Text("First heard ${Fmt.day(p.firstSeenAt)} · ${p.mentions} mentions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = Sp.l)) }
        }
    }
    if (editing) EditPerson(p) { editing = false }
    if (connecting) {
        var type by remember { mutableStateOf("") }
        var other by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { connecting = false }, title = { Text("Connect ${p.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    OutlinedTextField(type, { type = it }, label = { Text("${p.name.substringBefore(' ')} is… (manager of, wife of, works at)") }, singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("works at", "manager of", "colleague of", "friend of", "wife of", "husband of", "brother of", "sister of", "client of").forEach { s ->
                            AssistChip(onClick = { type = s }, label = { Text(s) })
                        }
                    }
                    OutlinedTextField(other, { other = it }, label = { Text("…whom (person or organisation)") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(enabled = type.isNotBlank() && other.isNotBlank(), onClick = { scope.launch { g.people.relate(p.name, type, other, null, System.currentTimeMillis()) }; connecting = false }) { Text("Connect") } },
            dismissButton = { TextButton(onClick = { connecting = false }) { Text("Cancel") } })
    }
    if (merging) {
        val others = everyone.filter { it.kind == p.kind && it.id != p.id }.sortedBy { it.name }
        AlertDialog(onDismissRequest = { merging = false }, title = { Text("${p.name} is the same as…") },
            text = {
                LazyColumn(Modifier.height(360.dp), state = app.echomind.ui.components.rememberKeptListState()) {
                    items(others, key = { it.id }) { o ->
                        Row(Modifier.fillMaxWidth().clickable { merging = false; scope.launch { g.people.mergeInto(p, o) } }.padding(vertical = Sp.s), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(o, 32); Spacer(Modifier.width(Sp.s))
                            Column { Text(o.name); quickClue(o).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                        }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { merging = false }) { Text("Cancel") } })
    }
}

@Composable
private fun RelationRow(r: Relation, me: Long, byId: Map<Long, EntityRow>) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val outgoing = r.fromId == me
    val other = byId[if (outgoing) r.toId else r.fromId] ?: return
    val self = byId[me]?.name?.substringBefore(' ') ?: ""
    Row(Modifier.fillMaxWidth().clickable { nav.go(PersonRoute(other.id)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(other, 32); Spacer(Modifier.width(Sp.s))
        Column(Modifier.weight(1f)) {
            Text(other.name, style = MaterialTheme.typography.bodyLarge)
            Text(if (outgoing) "$self is ${r.type} ${other.name.substringBefore(' ')}" else "${other.name.substringBefore(' ')} is ${r.type} $self", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { scope.launch { g.db.relations().delete(r.id) } }) { Icon(Icons.Rounded.Close, "Remove connection", tint = MaterialTheme.colorScheme.outline) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditPerson(p: EntityRow, onDone: () -> Unit) {
    val g = App.graph
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(p.name) }
    var relation by remember { mutableStateOf(p.relationToMe.orEmpty()) }
    var role by remember { mutableStateOf(p.role.orEmpty()) }
    var org by remember { mutableStateOf(p.org.orEmpty()) }
    var circle by remember { mutableStateOf(p.circle) }
    var summary by remember { mutableStateOf(p.summary) }
    var aliases by remember { mutableStateOf(runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), p.aliases) }.getOrDefault(emptyList()).joinToString(", ")) }
    AlertDialog(onDismissRequest = onDone, title = { Text("Edit") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Sp.s), state = app.echomind.ui.components.rememberKeptListState()) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) }
                if (p.kind == "person") {
                    item { OutlinedTextField(relation, { relation = it }, label = { Text("Who they are to you") }, singleLine = true, placeholder = { Text("my manager, cousin, plumber") }) }
                    item { OutlinedTextField(role, { role = it }, label = { Text("What they do") }, singleLine = true) }
                    item { OutlinedTextField(org, { org = it }, label = { Text("Where (company, school, group)") }, singleLine = true) }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            People.CIRCLES.forEach { c -> FilterChip(circle == c, { circle = c }, label = { Text(People.circleLabel(c)) }) }
                        }
                    }
                }
                item { OutlinedTextField(aliases, { aliases = it }, label = { Text("Also called (comma separated)") }, singleLine = true) }
                item { OutlinedTextField(summary, { summary = it }, label = { Text("Notes") }, minLines = 2) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    g.db.memory().updateEntity(p.copy(name = name.trim().ifEmpty { p.name }, relationToMe = relation.trim().ifEmpty { null }, role = role.trim().ifEmpty { null },
                        org = org.trim().ifEmpty { null }, circle = circle, summary = summary.trim(), pinned = true,
                        aliases = Js.json.encodeToString(ListSerializer(String.serializer()), aliases.split(',').map { it.trim() }.filter { it.isNotEmpty() })))
                }
                onDone()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } })
}

/**
 * Everyone you know as a tree: circles, then organisations, then people,
 * with how they connect to each other underneath.
 */
@Composable
fun CirclesScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val all by remember { g.db.memory().observeEntities() }.collectAsStateWithLifecycle(emptyList())
    val rels by remember { g.db.relations().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val byId = remember(all) { all.associateBy { it.id } }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    val people = all.filter { it.kind == "person" }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Circles", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), state = app.echomind.ui.components.rememberKeptListState()) {
            item {
                Text("You know ${people.size} people. Tap a circle to open it; tap a person for their page.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Sp.s))
            }
            if (people.isEmpty()) item { EmptyState("Nobody yet", "Circles fill in as people come up in your conversations.") }
            people.groupBy { it.circle ?: "other" }.toSortedMap(compareBy { circleOrder.indexOf(it).let { i -> if (i < 0) 99 else i } }).forEach { (circle, ps) ->
                val key = "c:$circle"
                val isOpen = open[key] ?: (ps.size <= 8)
                item(key = key) { TreeNode(0, People.circleLabel(circle), "${ps.size}", isOpen, circleColor(circle)) { open[key] = !isOpen } }
                if (isOpen) {
                    val byOrg = ps.groupBy { it.org?.takeIf { o -> o.isNotBlank() } }
                    byOrg.entries.sortedWith(compareBy({ it.key == null }, { it.key })).forEach { (org, members) ->
                        if (org != null && (members.size > 1 || circle == "work")) {
                            val okey = "o:$circle:$org"
                            val orgOpen = open[okey] ?: true
                            item(key = okey) { TreeNode(1, org, "${members.size}", orgOpen, MaterialTheme.colorScheme.secondary) { open[okey] = !orgOpen } }
                            if (orgOpen) members.sortedBy { it.name }.forEach { m -> item(key = "p:${m.id}:$okey") { TreePerson(2, m, rels, byId) { nav.go(PersonRoute(m.id)) } } }
                        } else members.sortedBy { it.name }.forEach { m -> item(key = "p:${m.id}:$circle") { TreePerson(1, m, rels, byId) { nav.go(PersonRoute(m.id)) } } }
                    }
                }
            }
        }
    }
}

@Composable
private fun TreeNode(depth: Int, label: String, count: String, open: Boolean, color: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (depth * 20).dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ExpandMore, if (open) "Close" else "Open", Modifier.rotate(if (open) 0f else -90f), tint = color)
        Spacer(Modifier.width(Sp.s))
        Text(label, style = if (depth == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.weight(1f))
        Text(count, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun TreePerson(depth: Int, e: EntityRow, rels: List<Relation>, byId: Map<Long, EntityRow>, onClick: () -> Unit) {
    val links = rels.filter { (it.fromId == e.id || it.toId == e.id) && it.type !in setOf("works at", "works for") }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (depth * 20 + 8).dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
        Avatar(e, 30)
        Spacer(Modifier.width(Sp.s))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge)
            listOfNotNull(e.role, e.relationToMe).joinToString(" · ").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            links.take(4).forEach { r ->
                val out = r.fromId == e.id
                val other = byId[if (out) r.toId else r.fromId]?.name ?: return@forEach
                Text(if (out) "↳ ${r.type} $other" else "↳ $other is ${r.type} them", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The AI's proposal for folders, tags and circles — reviewed before anything moves. */
@Composable
fun OrganizeScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<Organizer.Plan?>(null) }
    val skipNotes = remember { mutableStateListOf<Long>() }
    val skipPeople = remember { mutableStateListOf<Long>() }
    val ready = g.ai.ready(app.echomind.core.Feature.MEMORY)
    fun run(everything: Boolean) { busy = true; plan = null; scope.launch { plan = g.organizer.propose(everything); busy = false } }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Organise with AI", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s), state = app.echomind.ui.components.rememberKeptListState()) {
            item {
                Text("The AI suggests a folder tree and tags for your notes, and a circle for each person. Nothing moves until you apply it. New notes are filed on their own every night.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Sp.s))
                if (!ready) Text(g.ai.readiness(app.echomind.core.Feature.MEMORY) ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Button(enabled = ready && !busy, onClick = { run(false) }) { Text("File what's unfiled") }
                    OutlinedButton(enabled = ready && !busy, onClick = { run(true) }) { Text("Re-organise everything") }
                }
            }
            if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { LoadingIndicator(); Spacer(Modifier.width(Sp.m)); Text("Thinking about where everything belongs…") } }
            plan?.let { pl ->
                if (pl.notes.isEmpty() && pl.people.isEmpty()) item { EmptyState("Everything is in its place", "Nothing to move.") }
                if (pl.notes.isNotEmpty()) {
                    item { SectionHeader("Notes · ${pl.notes.size}") }
                    items(pl.notes, key = { "n${it.noteId}" }) { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(m.noteId !in skipNotes, { if (it) skipNotes.remove(m.noteId) else skipNotes.add(m.noteId) })
                            Column(Modifier.weight(1f)) {
                                Text(m.title, style = MaterialTheme.typography.bodyLarge)
                                Text("${m.fromFolder ?: "Unfiled"} → ${m.folder}${if (m.tags.isNotEmpty()) "  ·  " + m.tags.joinToString(" ") { "#$it" } else ""}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (pl.people.isNotEmpty()) {
                    item { SectionHeader("People · ${pl.people.size}") }
                    items(pl.people, key = { "p${it.id}" }) { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(m.id !in skipPeople, { if (it) skipPeople.remove(m.id) else skipPeople.add(m.id) })
                            Column(Modifier.weight(1f)) {
                                Text(m.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${People.circleLabel(m.fromCircle)} → ${People.circleLabel(m.circle)}${m.org?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (pl.notes.isNotEmpty() || pl.people.isNotEmpty()) item {
                    Button(onClick = {
                        val chosen = Organizer.Plan(pl.notes.filter { it.noteId !in skipNotes }, pl.people.filter { it.id !in skipPeople })
                        scope.launch { g.organizer.apply(chosen); nav.back() }
                    }) { Text("Apply ${pl.notes.count { it.noteId !in skipNotes } + pl.people.count { it.id !in skipPeople }} changes") }
                }
            }
        }
    }
}
