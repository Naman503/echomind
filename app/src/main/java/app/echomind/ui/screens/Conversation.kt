package app.echomind.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.core.Providers
import app.echomind.data.Utterance
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.NoteRoute
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.LargeTopBar
import app.echomind.ui.components.PrivacyChip
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import app.echomind.util.Js
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConversationScreen(id: Long, lineId: Long?) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val c by remember(id) { g.db.conversations().observe(id) }.collectAsStateWithLifecycle(null)
    val lines by remember(id) { g.db.utterances().observeConversation(id) }.collectAsStateWithLifecycle(emptyList())
    val tasks by remember(id) { g.db.tasks().observeForConversation(id) }.collectAsStateWithLifecycle(emptyList())
    val revisions by produceState(emptyList<Pair<Long, String>>(), id, c?.processedAt) {
        value = g.db.notes().revisionsFrom(id).mapNotNull { r -> g.db.notes().get(r.noteId)?.let { it.id to it.title } }.distinct()
    }
    var tab by rememberSaveable { mutableIntStateOf(if (lineId != null) 1 else 0) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<String?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var noAudio by remember { mutableStateOf(false) }
    val playing by g.playback.state.collectAsStateWithLifecycle()
    val settings by g.settings.flow.collectAsStateWithLifecycle()
    val conv = c ?: return

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        bottomBar = { if (playing.item != null) PlayerBar() },
        topBar = {
            LargeTopBar(
                title = if (conv.state == "done") conv.title ?: "Conversation" else if (conv.source == "call") "Call${conv.contact?.let { " with $it" } ?: ""}" else "Conversation",
                subtitle = "${Fmt.day(conv.startAt)} · ${Fmt.span(conv.startAt, conv.endAt)}${conv.contact?.let { " · $it" } ?: ""}",
                onBack = { nav.back() }, scroll = scroll,
            ) {
                if (conv.state == "done") app.echomind.ui.components.ListenButton("Read the summary") { g.briefing.conversation(conv) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Make notes again") }, onClick = {
                        menu = false
                        scope.launch { g.db.conversations().update(conv.copy(state = "ready", attempts = 0, ignored = false, error = null)); g.brain.processNow() }
                    })
                    DropdownMenuItem(text = { Text(if (conv.ignored) "This was a real conversation" else "This was TV / not me") }, onClick = {
                        menu = false
                        scope.launch { g.db.conversations().update(conv.copy(ignored = !conv.ignored, category = if (!conv.ignored) "media" else conv.category)) }
                    })
                    DropdownMenuItem(text = { Text("Delete conversation", color = MaterialTheme.colorScheme.error) }, onClick = {
                        menu = false
                        scope.launch { g.db.utterances().deleteForConversation(id); g.db.conversations().delete(id); nav.back() }
                    })
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Sp.gutter, vertical = Sp.s)) {
                listOf("Summary", "Transcript · ${lines.size}").forEachIndexed { i, label ->
                    SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            if (tab == 0) {
                LazyColumn(contentPadding = PaddingValues(horizontal = Sp.gutter, vertical = Sp.s), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    item {
                        if (conv.state != "done") {
                            EchoCard {
                                Text(if (conv.state == "open") "This conversation is still going." else "Notes haven't been made yet.", style = MaterialTheme.typography.titleSmall)
                                Text(g.ai.readiness(app.echomind.core.Feature.NOTES) ?: "They'll appear here shortly, or you can make them now.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (conv.state != "open") {
                                    Spacer(Modifier.height(Sp.s))
                                    Button(onClick = { scope.launch { g.db.conversations().update(conv.copy(state = "ready", attempts = 0)); g.brain.processNow() } }) { Text("Make notes now") }
                                }
                            }
                        } else {
                            conv.provider?.let { p -> PrivacyChip(Providers.isLocal(p), if (Providers.isLocal(p)) "Made on this phone" else "Made with ${Providers.get(p)?.label ?: p}") }
                            Spacer(Modifier.height(Sp.s))
                            Text(conv.summary.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    val points = decode(conv.keyPoints)
                    if (points.isNotEmpty()) {
                        item { SectionHeader("Key points") }
                        points.forEach { p -> item { Text("•  $p", style = MaterialTheme.typography.bodyLarge) } }
                    }
                    val people = decode(conv.people)
                    if (people.isNotEmpty()) item {
                        SectionHeader("People")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) { people.forEach { AssistChip(onClick = {}, label = { Text(it) }) } }
                    }
                    if (tasks.isNotEmpty()) {
                        item { SectionHeader("Tasks from this conversation") }
                        tasks.forEach { t ->
                            item(key = "t${t.id}") {
                                EchoCard {
                                    Text(t.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(listOfNotNull(
                                        when (t.status) { "suggested" -> "Waiting for your review"; "open" -> "On your list"; "done" -> "Done"; else -> "Dismissed" },
                                        t.remindAt?.let { "Reminder ${Fmt.dayTime(it)}" },
                                    ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (revisions.isNotEmpty()) {
                        item { SectionHeader("Notes updated") }
                        revisions.forEach { (nid, title) -> item(key = "n$nid") { EchoCard(onClick = { nav.go(NoteRoute(nid)) }) { Text(title, style = MaterialTheme.typography.bodyLarge) } } }
                    }
                    item { Spacer(Modifier.height(Sp.xl)) }
                }
            } else {
                val newestFirst = settings.transcriptNewestFirst
                Row(Modifier.fillMaxWidth().padding(horizontal = Sp.gutter), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(if (newestFirst) "Newest first" else "From the start", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { g.settings.update { it.copy(transcriptNewestFirst = !newestFirst) } } }) {
                        Icon(Icons.Rounded.SwapVert, null); Spacer(Modifier.width(4.dp)); Text(if (newestFirst) "Oldest first" else "Newest first")
                    }
                }
                Transcript(if (newestFirst) lines.asReversed() else lines, lineId, onPlay = { at -> scope.launch { if (!g.playback.playAt(at)) noAudio = true } },
                    onCorrect = { u, me -> scope.launch { correct(u, me) } }) { rename = it }
            }
        }
    }

    if (noAudio) AlertDialog(
        onDismissRequest = { noAudio = false },
        title = { Text("No recording for this moment") },
        text = { Text("Its audio was already deleted. Choose how long recordings are kept in Settings → Listening → Recordings.") },
        confirmButton = { TextButton(onClick = { noAudio = false }) { Text("OK") } },
    )

    rename?.let { from ->
        var name by remember(from) { mutableStateOf(if (from.startsWith("S") && from.length <= 3) "" else from) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Who is $from?") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val n = name.trim()
                    rename = null
                    scope.launch {
                        g.db.utterances().renameSpeaker(id, from, n); g.memory.link("person", n, null, conv.endAt)
                        // Their voice is remembered, so next time their lines are named by themselves.
                        if (g.settings.value.recognisePeople) g.voicePrints.learnFromConversation(id, conv.contact)
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } },
        )
    }
}

/** "This was me" / "not me": fixes the line and teaches the voice model. */
private suspend fun correct(u: Utterance, me: Boolean) {
    val g = App.graph
    g.db.utterances().update(u.copy(speaker = if (me) "me" else "S9"))
    g.db.vectors().forOwners("s", listOf(u.id)).firstOrNull()?.let { v ->
        g.voice.learn(app.echomind.stt.VoiceId.normalize(app.echomind.brain.Vectors.dequantize(v.vector, v.scale)), me)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Transcript(lines: List<Utterance>, focus: Long?, onPlay: (Long) -> Unit, onCorrect: (Utterance, Boolean) -> Unit, onSpeaker: (String) -> Unit) {
    var menuFor by remember { mutableStateOf<Long?>(null) }
    val state = rememberLazyListState()
    LaunchedEffect(focus, lines.size) {
        val i = lines.indexOfFirst { it.id == focus }
        if (i >= 0) state.animateScrollToItem((i - 2).coerceAtLeast(0))
    }
    if (lines.isEmpty()) { EmptyState("No transcript", "The words of this conversation were deleted."); return }
    val sem = LocalSemantic.current
    val palette = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, sem.onDevice, sem.warning, MaterialTheme.colorScheme.secondary)
    LazyColumn(state = state, contentPadding = PaddingValues(horizontal = Sp.gutter, vertical = Sp.s)) {
        itemsIndexed(lines, key = { _, u -> u.id }, contentType = { _, _ -> "line" }) { i, u ->
            val speaker = u.speaker ?: "?"
            val newSpeaker = i == 0 || lines[i - 1].speaker != u.speaker
            val highlight by animateFloatAsState(if (u.id == focus) 1f else 0f, tween(600), label = "hl")
            val color = when {
                speaker == "me" -> MaterialTheme.colorScheme.primary
                else -> palette[(speaker.hashCode() and 0x7fffffff) % palette.size]
            }
            Column(
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = { menuFor = u.id })
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f * highlight), MaterialTheme.shapes.small)
                    .padding(horizontal = Sp.s, vertical = if (newSpeaker) 6.dp else 2.dp),
            ) {
                if (newSpeaker) Row {
                    Text(if (speaker == "me") "You" else speaker, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(enabled = speaker != "me") { onSpeaker(speaker) })
                    Spacer(Modifier.width(Sp.s))
                    // Tapping the time plays the recording from that moment, while it's kept.
                    Row(Modifier.clickable { onPlay(u.startAt) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(Fmt.time(u.startAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Icon(Icons.Rounded.PlayArrow, "Play from here", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                }
                Text(u.text, style = MaterialTheme.typography.bodyLarge, color = if (u.source == "typed") MaterialTheme.colorScheme.tertiary else Color.Unspecified)
                DropdownMenu(menuFor == u.id, { menuFor = null }) {
                    if (speaker != "me") DropdownMenuItem(text = { Text("This was me") }, onClick = { menuFor = null; onCorrect(u, true) })
                    if (speaker == "me") DropdownMenuItem(text = { Text("That wasn't me") }, onClick = { menuFor = null; onCorrect(u, false) })
                    if (speaker != "me") DropdownMenuItem(text = { Text("Who is speaking? Name them") }, onClick = { menuFor = null; onSpeaker(speaker) })
                    DropdownMenuItem(text = { Text("Play from here") }, onClick = { menuFor = null; onPlay(u.startAt) })
                }
            }
        }
        item { Spacer(Modifier.height(Sp.xl)) }
    }
}

private fun decode(json: String?): List<String> = json?.let { runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()
