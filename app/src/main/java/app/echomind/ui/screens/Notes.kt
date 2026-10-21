package app.echomind.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.data.Note
import app.echomind.ui.ConversationRoute
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.NewNoteRoute
import app.echomind.ui.NoteRoute
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Palette
import app.echomind.ui.theme.Sp
import app.echomind.util.Js
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate

private fun strings(json: String): List<String> = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), json) }.getOrDefault(emptyList())
private fun encode(l: List<String>) = Js.json.encodeToString(ListSerializer(String.serializer()), l)

/** Card colour: a soft tint of the note's colour over the normal surface. */
@Composable
fun noteSurface(color: Int): Color =
    if (color <= 0) MaterialTheme.colorScheme.surfaceContainerLow
    else Palette.color(color).copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)

private fun isChecklist(body: String) = body.lines().count { it.trimStart().startsWith("- [") } >= maxOf(1, body.lines().count { it.isNotBlank() } / 2)

/** Note templates for "New from template". */
object NoteTemplates {
    val all: List<Pair<String, () -> Pair<String, String>>> = listOf(
        "Meeting notes" to { "Meeting · ${Fmt.day(LocalDate.now())}" to "## Attendees\n- \n\n## Agenda\n- \n\n## Notes\n- \n\n## Action items\n- [ ] " },
        "Daily log" to { "Daily log · ${Fmt.dayLong(LocalDate.now())}" to "## Top 3\n- [ ] \n- [ ] \n- [ ] \n\n## Notes\n- \n\n## Grateful for\n- " },
        "Idea" to { "Idea" to "## The idea\n\n## Why it matters\n- \n\n## Next steps\n- [ ] " },
        "Project plan" to { "Project" to "## Goal\n\n## Milestones\n- [ ] \n\n## Open questions\n- \n\n## Risks\n- " },
        "Shopping list" to { "Shopping" to "- [ ] \n" },
        "Checklist" to { "" to "- [ ] " },
    )
}

@Composable
fun NoteCard(n: Note, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = noteSurface(n.color), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Sp.m)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(n.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (n.pinned) Icon(Icons.Rounded.PushPin, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                if (n.locked) Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(4.dp))
            n.body.lines().filter { it.isNotBlank() && !it.startsWith("#") && it.trim() != "---" }.take(6).forEach { raw ->
                val l = raw.trim()
                val (mark, text) = when {
                    l.startsWith("- [x]", true) -> "☑ " to l.substring(5).trim()
                    l.startsWith("- [ ]") -> "☐ " to l.substring(5).trim()
                    l.startsWith("- ") || l.startsWith("* ") -> "• " to l.substring(2)
                    else -> "" to l
                }
                Text(mark + text.replace("**", ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (mark == "☑ ") TextDecoration.LineThrough else null)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (n.origin == "ai") { Icon(Icons.Rounded.AutoAwesome, "Kept up to date by AI", Modifier.size(12.dp), tint = LocalSemantic.current.onDevice); Spacer(Modifier.width(4.dp)) }
                Text(listOfNotNull(n.folder, strings(n.tags).joinToString(" ") { "#$it" }.ifBlank { null }, Fmt.ago(n.updatedAt)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private enum class Filter(val label: String) { ALL("All"), PINNED("Pinned"), CHECKLISTS("Checklists"), MINE("Mine"), AI("Kept by AI"), ARCHIVED("Archived") }

@Composable
fun NotesScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val notes by remember { g.db.notes().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val prefs = LocalContext.current.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    var grid by remember { mutableStateOf(prefs.getBoolean("notesGrid", true)) }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("updated") }
    var sortMenu by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }

    val folders = remember(notes) { notes.mapNotNull { it.folder }.distinct().sorted() }
    val tags = remember(notes) { notes.flatMap { strings(it.tags) }.distinct().sorted() }
    val shown = notes.filter { n ->
        val f = when (filter) {
            Filter.ALL -> !n.archived
            Filter.PINNED -> n.pinned && !n.archived
            Filter.CHECKLISTS -> !n.archived && isChecklist(n.body)
            Filter.MINE -> !n.archived && n.origin == "user"
            Filter.AI -> !n.archived && n.origin == "ai"
            Filter.ARCHIVED -> n.archived
        }
        f && (folder == null || n.folder == folder) && (tag == null || tag in strings(n.tags)) &&
            (query.isBlank() || n.title.contains(query, true) || n.body.contains(query, true))
    }.let { l ->
        when (sort) {
            "created" -> l.sortedByDescending { it.createdAt }
            "title" -> l.sortedBy { it.title.lowercase() }
            else -> l.sortedByDescending { it.updatedAt }
        }.sortedByDescending { it.pinned }
    }

    Scaffold(
        topBar = {
            ScreenTopBar("Notes", onBack = { nav.back() }) {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Rounded.Search, "Search") }
                IconButton(onClick = { grid = !grid; prefs.edit().putBoolean("notesGrid", grid).apply() }) { Icon(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, "Change view") }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.MoreVert, "Sort") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf("updated" to "Sort by last edited", "created" to "Sort by created", "title" to "Sort by title").forEach { (k, l) ->
                            DropdownMenuItem(text = { Text(l + if (sort == k) "  ✓" else "") }, onClick = { sort = k; sortMenu = false })
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(onClick = { newMenu = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New") })
                DropdownMenu(newMenu, { newMenu = false }) {
                    DropdownMenuItem(text = { Text("Note") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { newMenu = false; nav.go(NewNoteRoute()) })
                    NoteTemplates.all.forEach { (name, _) ->
                        DropdownMenuItem(text = { Text(name) }, leadingIcon = { Icon(if (name.contains("list", true)) Icons.Rounded.CheckBox else Icons.Rounded.FormatListBulleted, null) },
                            onClick = { newMenu = false; nav.go(NewNoteRoute(template = name)) })
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (searching) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = Sp.gutter), placeholder = { Text("Search notes") }, singleLine = true, shape = MaterialTheme.shapes.extraLarge)
            LazyRow(contentPadding = PaddingValues(horizontal = Sp.gutter, vertical = Sp.s), horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                items(Filter.entries.toList(), key = { it.name }) { f -> FilterChip(filter == f && folder == null && tag == null, { filter = f; folder = null; tag = null }, label = { Text(f.label) }) }
                items(folders, key = { "f$it" }) { f -> FilterChip(folder == f, { folder = if (folder == f) null else f; tag = null }, label = { Text("📁 $f") }) }
                items(tags, key = { "t$it" }) { t -> FilterChip(tag == t, { tag = if (tag == t) null else t; folder = null }, label = { Text("#$t") }) }
            }
            if (shown.isEmpty()) {
                EmptyState(if (notes.isEmpty()) "No notes yet" else "Nothing here", if (notes.isEmpty()) "Write one, start from a template, or let the AI start one when a topic keeps coming up." else "Try another filter.")
            } else if (grid) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(Sp.s), verticalItemSpacing = Sp.s,
                ) { items(shown, key = { it.id }) { n -> NoteCard(n) { nav.go(NoteRoute(n.id)) } } }
            } else {
                LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    items(shown, key = { it.id }) { n -> NoteCard(n) { nav.go(NoteRoute(n.id)) } }
                }
            }
        }
    }
}

/** Markdown-lite rendering with tappable checkboxes. AI-written lines are softer than your own. */
@Composable
private fun NoteBody(body: String, mine: Set<String>, onToggle: (Int) -> Unit) {
    val lines = body.lines()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEachIndexed { i, raw ->
            val l = raw.trim()
            val soft = l !in mine
            val tone = if (soft) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            when {
                l.startsWith("### ") -> Text(l.removePrefix("### "), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                l.startsWith("## ") -> Text(l.removePrefix("## "), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                l.startsWith("# ") -> Text(l.removePrefix("# "), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
                l == "---" -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                l.startsWith("- [ ]") || l.startsWith("- [x]", true) -> {
                    val checked = l.startsWith("- [x]", true)
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onToggle(i) }.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        Icon(if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null, tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(Sp.s))
                        Text(inline(l.substring(5).trim()), style = MaterialTheme.typography.bodyLarge, color = if (checked) MaterialTheme.colorScheme.outline else tone,
                            textDecoration = if (checked) TextDecoration.LineThrough else null)
                    }
                }
                l.startsWith("- ") || l.startsWith("* ") -> Row { Text("•  ", style = MaterialTheme.typography.bodyLarge, color = tone); Text(inline(l.substring(2)), style = MaterialTheme.typography.bodyLarge, color = tone) }
                Regex("^\\d+[.)] ").containsMatchIn(l) -> Text(inline(l), style = MaterialTheme.typography.bodyLarge, color = tone)
                l.startsWith("> ") -> Row(Modifier.padding(vertical = 2.dp)) {
                    Box(Modifier.width(3.dp).heightIn(min = 22.dp).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(Sp.s))
                    Text(inline(l.removePrefix("> ")), style = MaterialTheme.typography.bodyLarge, color = tone)
                }
                l.isEmpty() -> Spacer(Modifier.height(6.dp))
                else -> Text(inline(l), style = MaterialTheme.typography.bodyLarge, color = tone)
            }
        }
    }
}

/** **bold** spans. */
private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var rest = s
    while (true) {
        val a = rest.indexOf("**")
        val b = if (a >= 0) rest.indexOf("**", a + 2) else -1
        if (a < 0 || b < 0) { append(rest); break }
        append(rest.substring(0, a))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(a + 2, b)) }
        rest = rest.substring(b + 2)
    }
}

/** Inserts formatting at the cursor: a line prefix, or wraps the selection. */
private fun format(v: TextFieldValue, kind: String): TextFieldValue {
    val text = v.text
    val start = v.selection.min
    val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (start == 0) 0 else if (it < 0) 0 else it + 1 }
    fun prefix(p: String): TextFieldValue {
        val line = text.substring(lineStart).substringBefore('\n')
        val stripped = line.removePrefix("- [ ] ").removePrefix("- ").removePrefix("## ")
        val newLine = if (line.startsWith(p)) stripped else p + stripped
        val nt = text.substring(0, lineStart) + newLine + text.substring(lineStart + line.length)
        val delta = newLine.length - line.length
        return TextFieldValue(nt, TextRange((v.selection.end + delta).coerceIn(0, nt.length)))
    }
    return when (kind) {
        "h" -> prefix("## ")
        "bullet" -> prefix("- ")
        "check" -> prefix("- [ ] ")
        "bold" -> {
            val sel = text.substring(v.selection.min, v.selection.max)
            val nt = text.substring(0, v.selection.min) + "**$sel**" + text.substring(v.selection.max)
            TextFieldValue(nt, TextRange(v.selection.min + 2 + sel.length + if (sel.isEmpty()) 0 else 2))
        }
        "rule" -> insert(v, "\n---\n")
        "time" -> insert(v, Fmt.time(System.currentTimeMillis()) + " ")
        else -> v
    }
}

private fun insert(v: TextFieldValue, s: String): TextFieldValue {
    val nt = v.text.substring(0, v.selection.min) + s + v.text.substring(v.selection.max)
    return TextFieldValue(nt, TextRange(v.selection.min + s.length))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteScreen(id: Long?, template: String? = null) {
    val g = App.graph
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val note by remember(id) { id?.let { g.db.notes().observe(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsStateWithLifecycle(null)
    val revisions by remember(id) { id?.let { g.db.notes().observeRevisions(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val tpl = remember(template) { template?.let { t -> NoteTemplates.all.firstOrNull { it.first == t }?.second?.invoke() } }
    var editing by rememberSaveable(id) { mutableStateOf(id == null) }
    var title by rememberSaveable(note?.id) { mutableStateOf(note?.title ?: tpl?.first.orEmpty()) }
    var body by remember(note?.id, note?.version) { mutableStateOf(TextFieldValue(note?.body ?: tpl?.second.orEmpty())) }
    var history by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var colorSheet by remember { mutableStateOf(false) }
    var organize by remember { mutableStateOf(false) }

    suspend fun save() {
        val n = note
        if (n == null) {
            if (title.isBlank() && body.text.isBlank()) return
            val newId = g.notes.createByUser(title.ifBlank { body.text.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart('#', '-', ' ', '[', ']')?.take(40) ?: "Note" }, body.text)
            nav.back(); nav.go(NoteRoute(newId))
        } else if (title != n.title || body.text != n.body) {
            g.notes.saveByUser(n, title, body.text)
        }
        editing = false
    }

    fun toggleLine(i: Int) {
        val n = note ?: return
        val lines = n.body.lines().toMutableList()
        val l = lines[i]
        lines[i] = if (l.trimStart().startsWith("- [ ]")) l.replaceFirst("- [ ]", "- [x]") else l.replaceFirst(Regex("- \\[[xX]]"), "- [ ]")
        scope.launch { g.db.notes().update(n.copy(body = lines.joinToString("\n"), updatedAt = System.currentTimeMillis())) }
    }

    Scaffold(
        containerColor = noteSurface(note?.color ?: 0).copy(alpha = 1f).let { if ((note?.color ?: 0) == 0) MaterialTheme.colorScheme.surface else it },
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ScreenTopBar("", onBack = { if (editing) scope.launch { save(); if (note == null) nav.back() } else nav.back() }) {
                val n = note
                if (n != null) {
                    IconButton(onClick = { scope.launch { g.db.notes().update(n.copy(pinned = !n.pinned)) } }) { Icon(Icons.Rounded.PushPin, if (n.pinned) "Unpin" else "Pin", tint = if (n.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { colorSheet = true }) { Icon(Icons.Rounded.Palette, "Colour") }
                }
                IconButton(onClick = { if (editing) scope.launch { save() } else editing = true }) { Icon(if (editing) Icons.Rounded.Visibility else Icons.Rounded.Edit, if (editing) "Done" else "Edit") }
                if (n != null) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Folder & tags…") }, onClick = { menu = false; organize = true })
                        DropdownMenuItem(text = { Text(if (n.locked) "Let AI update this note" else "Stop AI updates") }, onClick = { menu = false; scope.launch { g.db.notes().update(n.copy(locked = !n.locked)) } })
                        val open = n.body.lines().filter { it.trimStart().startsWith("- [ ]") }.map { it.trim().removePrefix("- [ ]").trim() }.filter { it.isNotEmpty() }
                        if (open.isNotEmpty()) DropdownMenuItem(text = { Text("Make ${open.size} task${if (open.size == 1) "" else "s"} from checklist") }, onClick = {
                            menu = false
                            scope.launch { open.forEach { g.tasks.addQuick(it) }; snackbar.showSnackbar("${open.size} tasks added to your list") }
                        })
                        DropdownMenuItem(text = { Text("Share") }, onClick = {
                            menu = false
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${n.title}\n\n${n.body}"), "Share note"))
                        })
                        DropdownMenuItem(text = { Text(if (n.archived) "Unarchive" else "Archive") }, leadingIcon = { Icon(Icons.Rounded.Archive, null) }, onClick = {
                            menu = false; scope.launch { g.db.notes().update(n.copy(archived = !n.archived)); if (!n.archived) nav.back() }
                        })
                        DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; scope.launch { g.db.notes().delete(n.id); nav.back() } })
                    }
                }
            }
        },
        bottomBar = {
            if (editing) Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).windowInsetsPadding(WindowInsets.ime).padding(horizontal = Sp.s, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = { body = format(body, "h") }) { Icon(Icons.Rounded.Title, "Heading") }
                IconButton(onClick = { body = format(body, "bullet") }) { Icon(Icons.Rounded.FormatListBulleted, "Bullet") }
                IconButton(onClick = { body = format(body, "check") }) { Icon(Icons.Rounded.CheckBox, "Checkbox") }
                IconButton(onClick = { body = format(body, "bold") }) { Icon(Icons.Rounded.FormatBold, "Bold") }
                IconButton(onClick = { body = format(body, "rule") }) { Icon(Icons.Rounded.HorizontalRule, "Divider") }
                IconButton(onClick = { body = format(body, "time") }) { Icon(Icons.Rounded.Schedule, "Time") }
            }
        },
    ) { pad ->
        val n = note
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(horizontal = Sp.gutter, vertical = Sp.s)) {
            if (editing) {
                item {
                    TextField(title, { title = it }, Modifier.fillMaxWidth(), placeholder = { Text("Title") }, textStyle = MaterialTheme.typography.headlineSmall,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                    TextField(body, { v ->
                        // Enter on a list line continues the list, like every good notes app.
                        val prev = body.text
                        body = if (v.text.length == prev.length + 1 && v.selection.min > 0 && v.text[v.selection.min - 1] == '\n') {
                            val before = v.text.substring(0, v.selection.min - 1)
                            val line = before.substringAfterLast('\n')
                            val cont = when { line.startsWith("- [ ] ") || line.startsWith("- [x] ") -> "- [ ] "; line.startsWith("- ") -> "- "; else -> "" }
                            if (cont.isNotEmpty() && line.trim() == cont.trim()) {
                                // Empty list item: end the list.
                                val nt = before.dropLast(line.length) + v.text.substring(v.selection.min)
                                TextFieldValue(nt, TextRange(before.length - line.length))
                            } else if (cont.isNotEmpty()) insert(v, cont) else v
                        } else v
                    }, Modifier.fillMaxWidth().heightIn(min = 320.dp), placeholder = { Text("Write… (## heading, - bullet, - [ ] checkbox)") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                    if (n != null && n.origin == "ai") Text("Lines you write are yours: the AI will never change or remove them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@LazyColumn
            }
            if (n == null) return@LazyColumn
            item {
                Text(n.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clickable { editing = true })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    if (n.origin == "ai" && !n.locked) { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(13.dp), tint = LocalSemantic.current.onDevice); Spacer(Modifier.width(4.dp)) }
                    Text(listOfNotNull("Edited ${Fmt.ago(n.updatedAt)}", if (n.locked) "AI updates off" else if (n.origin == "ai") "kept up to date by AI" else null, n.folder?.let { "📁 $it" }).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val t = strings(n.tags)
                if (t.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { t.forEach { app.echomind.ui.components.Pill("#$it") } }
                Spacer(Modifier.height(Sp.s))
            }
            item {
                val mine = strings(n.userLines).map { it.trim() }.toSet() + if (n.origin == "user") n.body.lines().map { it.trim() } else emptyList()
                Box(Modifier.clickable { editing = true }) { NoteBody(n.body, mine) { toggleLine(it) } }
            }
            if (revisions.isNotEmpty()) {
                item { SectionHeader("History · ${revisions.size} changes", action = if (history) "Hide" else "Show", onAction = { history = !history }) }
                if (history) items(revisions, key = { "r${it.id}" }) { r ->
                    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = (r.conversationId ?: 0) > 0) { nav.go(ConversationRoute(r.conversationId!!)) }.padding(vertical = 8.dp)) {
                        Text(r.reason, style = MaterialTheme.typography.bodyMedium)
                        Text("${Fmt.dayTime(r.createdAt)} · ${if (r.by == "user") "you" else "AI"} · v${r.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        val beforeSet = r.before.lines().map { it.trim() }.toSet()
                        val afterSet = r.after.lines().map { it.trim() }.toSet()
                        r.after.lines().map { it.trim() }.filter { it.isNotEmpty() && it !in beforeSet }.take(4).forEach { Text("+ $it", style = MaterialTheme.typography.bodySmall, color = LocalSemantic.current.positive) }
                        r.before.lines().map { it.trim() }.filter { it.isNotEmpty() && it !in afterSet }.take(4).forEach { Text("− $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    val n = note
    if (colorSheet && n != null) ModalBottomSheet(onDismissRequest = { colorSheet = false }) {
        Column(Modifier.padding(horizontal = Sp.xl).padding(bottom = Sp.xxl)) {
            Text("Colour", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Sp.m))
            ColorRow(n.color, includeNone = true) { c -> scope.launch { g.db.notes().update(n.copy(color = c)) }; colorSheet = false }
        }
    }
    if (organize && n != null) {
        var f by remember { mutableStateOf(n.folder.orEmpty()) }
        var t by remember { mutableStateOf(strings(n.tags).joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { organize = false },
            title = { Text("Organise") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    OutlinedTextField(f, { f = it }, label = { Text("Folder") }, singleLine = true)
                    OutlinedTextField(t, { t = it }, label = { Text("Tags (comma separated)") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = {
                organize = false
                scope.launch { g.db.notes().update(n.copy(folder = f.trim().ifEmpty { null }, tags = encode(t.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()))) }
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { organize = false }) { Text("Cancel") } },
        )
    }
}

