package app.echomind.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.Things
import app.echomind.data.EntityRow
import app.echomind.ui.ConversationRoute
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.ThingRoute
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.Pill
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer

fun thingIcon(kind: String): ImageVector = when (kind) {
    "place" -> Icons.Rounded.Place; "movie" -> Icons.Rounded.Movie; "show" -> Icons.Rounded.Tv; "book" -> Icons.Rounded.MenuBook
    "music" -> Icons.Rounded.MusicNote; "tech" -> Icons.Rounded.Memory; "product" -> Icons.Rounded.ShoppingBag; "org" -> Icons.Rounded.Business
    "food" -> Icons.Rounded.Fastfood; "event" -> Icons.Rounded.Event; "game" -> Icons.Rounded.SportsEsports; else -> Icons.Rounded.Category
}

@Composable
fun thingColor(kind: String): Color = when (kind) {
    "place" -> LocalSemantic.current.positive; "movie", "show" -> MaterialTheme.colorScheme.tertiary; "book" -> LocalSemantic.current.warning
    "music" -> LocalSemantic.current.recording; "tech" -> MaterialTheme.colorScheme.primary; "food" -> LocalSemantic.current.cloud
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
fun ThingBadge(kind: String, size: Int = 40) {
    val c = thingColor(kind)
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 3).dp)).background(c.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(thingIcon(kind), null, tint = c, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** Everything named in your conversations: shows, places, tech, books… searchable, by kind. */
@Composable
fun ThingsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val all by remember { g.db.memory().observeEntities() }.collectAsStateWithLifecycle(emptyList())
    val things = all.filter { it.kind in Things.KEYS }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var wanted by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val q = Things.key(query)
    val shown = things.filter { (kind == null || it.kind == kind) && (!wanted || it.status == "want" || it.status == "recommended") }
        .filter { q.isBlank() || Things.key(it.name).contains(q) || Things.aliases(it).any { a -> Things.key(a).contains(q) } || it.summary.lowercase().contains(query.lowercase()) }
        .sortedByDescending { it.lastSeenAt }
    val counts = things.groupingBy { it.kind }.eachCount()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Things", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                    placeholder = { Text("Search shows, places, apps, books…") })
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    item { FilterChip(kind == null && !wanted, { kind = null; wanted = false }, label = { Text("All · ${things.size}") }) }
                    item { FilterChip(wanted, { wanted = !wanted }, label = { Text("To watch / try") }) }
                    items(Things.Kind.entries.filter { (counts[it.key] ?: 0) > 0 }) { k ->
                        FilterChip(kind == k.key, { kind = if (kind == k.key) null else k.key }, label = { Text("${k.plural} · ${counts[k.key]}") },
                            leadingIcon = { Icon(thingIcon(k.key), null, Modifier.size(18.dp)) })
                    }
                }
            }
            if (things.isEmpty()) item {
                EmptyState("Nothing named yet", "When a place, show, movie, book, app or anything else with a name comes up in a conversation, it's noted here with what was said about it. Older conversations are read at night.")
            }
            items(shown, key = { it.id }) { e -> ThingRow(e) { nav.go(ThingRoute(e.id)) } }
            item {
                val st by g.settings.flow.collectAsStateWithLifecycle()
                Column(Modifier.padding(top = Sp.l)) {
                    SectionHeader("Settings")
                    app.echomind.ui.components.SwitchRow("Note named things", "Places, shows, books, apps and more from your conversations (on the phone with the on-device model)", st.extractThings) { v -> g.scope.launch { g.settings.update { it.copy(extractThings = v) } } }
                    app.echomind.ui.components.SwitchRow("Look them up online", "Only the name is sent to Wikidata, for a one-line description; logged in the data ledger", st.thingsOnline) { v -> g.scope.launch { g.settings.update { it.copy(thingsOnline = v) } } }
                }
            }
        }
    }
}

@Composable
fun ThingRow(e: EntityRow, onClick: () -> Unit) {
    EchoCard(onClick = onClick, padding = PaddingValues(Sp.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThingBadge(e.kind)
            Spacer(Modifier.width(Sp.m))
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(Things.Kind.of(e.kind)?.label, e.detail?.takeIf { it.isNotBlank() } ?: e.summary.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${e.mentions} mention${if (e.mentions == 1) "" else "s"} · last ${Fmt.day(e.lastSeenAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Things.statusLabel(e.kind, e.status)?.let { Pill(it) }
        }
    }
}

/** One thing: what it is, its status, and every time it came up with the words that were said. */
@Composable
fun ThingScreen(id: Long) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val e by remember(id) { g.db.memory().observeEntity(id) }.collectAsStateWithLifecycle(null)
    val mentions by remember(id) { g.db.mentions().observeFor(id) }.collectAsStateWithLifecycle(emptyList())
    val all by remember { g.db.memory().observeEntities() }.collectAsStateWithLifecycle(emptyList())
    var renaming by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val t = e ?: return
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar(t.name, onBack = { nav.back() }) { IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.Delete, "Delete") } }
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(Sp.m)) {
            item {
                EchoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThingBadge(t.kind, 52)
                        Spacer(Modifier.width(Sp.m))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleLarge)
                            Text(Things.Kind.of(t.kind)?.label ?: t.kind, style = MaterialTheme.typography.labelLarge, color = thingColor(t.kind))
                        }
                    }
                    t.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Sp.s)) }
                    t.summary.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                    Things.aliases(t).takeIf { it.isNotEmpty() }?.let { Text("Also heard as: ${it.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp)) }
                    Text("First ${Fmt.day(t.firstSeenAt)} · last ${Fmt.day(t.lastSeenAt)} · ${mentions.size} mention${if (mentions.size == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
                }
            }
            item {
                SectionHeader("Status")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    items(listOf("want", "recommended", "done", "liked", "disliked")) { st ->
                        FilterChip(t.status == st, { scope.launch { g.db.memory().updateEntity(t.copy(status = if (t.status == st) null else st)) } }, label = { Text(Things.statusLabel(t.kind, st)!!) })
                    }
                }
            }
            item {
                SectionHeader("Kind")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    items(Things.Kind.entries) { k ->
                        FilterChip(t.kind == k.key, { scope.launch { g.db.memory().updateEntity(t.copy(kind = k.key)) } }, label = { Text(k.label) }, leadingIcon = { Icon(thingIcon(k.key), null, Modifier.size(18.dp)) })
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    TextButton(onClick = { renaming = true }) { Text("Fix the name") }
                    TextButton(onClick = { merging = true }) { Text("Same as…") }
                }
            }
            item { SectionHeader("When it came up") }
            if (mentions.isEmpty()) item { Text("No mentions kept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(mentions, key = { it.id }) { m ->
                EchoCard(onClick = { nav.go(ConversationRoute(m.conversationId, m.utteranceId)) }) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.FormatQuote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Sp.s))
                        Column(Modifier.weight(1f)) {
                            Text(m.quote, style = MaterialTheme.typography.bodyMedium)
                            Text(listOfNotNull(Fmt.dayTime(m.at), m.speaker?.let { if (it == "me") "you" else it }, Things.statusLabel(t.kind, m.intent)).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(t.name) }
        AlertDialog(onDismissRequest = { renaming = false }, title = { Text("Name") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                val old = t.name
                scope.launch {
                    val al = (Things.aliases(t) + old).filter { !it.equals(name.trim(), true) }.distinctBy { it.lowercase() }
                    g.db.memory().updateEntity(t.copy(name = name.trim(), aliases = app.echomind.util.Js.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(String.serializer()), al), extId = null))
                }
                renaming = false
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } })
    }
    if (merging) {
        val others = all.filter { it.kind in Things.KEYS && it.id != t.id }.sortedBy { it.name.lowercase() }
        AlertDialog(onDismissRequest = { merging = false }, title = { Text("Same thing as…") },
            text = {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(others, key = { it.id }) { o ->
                        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            ThingBadge(o.kind, 28); Spacer(Modifier.width(Sp.s))
                            TextButton(onClick = { scope.launch { g.things.merge(t, o); nav.back(); nav.go(ThingRoute(o.id)) }; merging = false }) { Text(o.name) }
                        }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { merging = false }) { Text("Cancel") } })
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete ${t.name}?") }, text = { Text("Its mentions go too. Conversations aren't changed.") },
        confirmButton = { TextButton(onClick = { scope.launch { g.things.delete(t) }; deleting = false; nav.back() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}
