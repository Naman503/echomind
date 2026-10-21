package app.echomind.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import kotlinx.coroutines.launch
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.Public
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.Ask
import app.echomind.brain.Citation
import app.echomind.core.Feature
import app.echomind.core.Providers
import app.echomind.data.ChatMessage
import app.echomind.ui.AskRoute
import app.echomind.ui.ChatsRoute
import app.echomind.ui.ConversationRoute
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.MemoryRoute
import app.echomind.ui.NoteRoute
import app.echomind.ui.TasksRoute
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.PrivacyChip
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(initialThread: Long?, initialQuestion: String?, startVoice: Boolean = false) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var threadId by rememberSaveable { mutableStateOf(initialThread) }
    var input by rememberSaveable { mutableStateOf("") }
    var draft by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf<String?>(null) }
    var pendingQuestion by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<Citation?>(null) }
    val messages by remember(threadId) { threadId?.let { g.db.chat().observeMessages(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val provider = s.provider(Feature.ASK)
    val list = rememberLazyListState()

    var useWeb by rememberSaveable { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun send(q: String, spoken: Boolean = false) {
        if (q.isBlank() || draft != null || searching != null) return
        input = ""
        pendingQuestion = q
        searching = "Searching your memory…"
        val speakIt = spoken || s.autoReadAnswers
        scope.launch {
            // Commands ("what's pending", "remind me…", "mark X done") are done directly.
            val handled = runCatching { g.assistant.handle(q, speak = speakIt) }.getOrNull()
            if (handled != null) {
                threadId = g.ask.record(threadId, q, handled.reply)
                searching = null; pendingQuestion = null
                return@launch
            }
            var answer = ""
            g.ask.answer(threadId, q, useWeb).collect { e ->
                when (e) {
                    is Ask.Event.Searching -> searching = e.text
                    is Ask.Event.Sources -> searching = if (e.citations.isEmpty()) "Nothing matched — answering honestly" else "Found ${e.citations.size} moments · thinking"
                    is Ask.Event.Delta -> { searching = null; draft = (draft ?: "") + e.text; answer += e.text }
                    is Ask.Event.Done -> {
                        threadId = e.threadId; draft = null; searching = null; pendingQuestion = null
                        if (speakIt && answer.isNotBlank()) g.speaker.speak("Answer", answer)
                    }
                    is Ask.Event.Failed -> {}
                }
            }
            draft = null; searching = null; pendingQuestion = null
        }
    }
    val voice = app.echomind.ui.components.rememberVoiceInput(onPartial = { input = it }, onFinal = { heard -> input = ""; send(heard, spoken = true) })
    LaunchedEffect(initialQuestion) { if (!initialQuestion.isNullOrBlank()) send(initialQuestion) }
    LaunchedEffect(startVoice) { if (startVoice) voice.start() }
    LaunchedEffect(messages.size, draft) { if (messages.isNotEmpty() || draft != null) list.animateScrollToItem(maxOf(0, messages.size + 1)) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.ime)) {
        ScreenTopBar(
            "Ask",
            onBack = if (threadId != null) ({ if (initialThread != null) nav.back() else threadId = null }) else null,
        ) {
            PrivacyChip(Providers.isLocal(provider), if (Providers.isLocal(provider)) "On this phone" else Providers.get(provider)?.label, Modifier.padding(end = Sp.s))
            IconButton(onClick = { g.scope.launch { g.settings.update { it.copy(autoReadAnswers = !it.autoReadAnswers) } } }) {
                Icon(if (s.autoReadAnswers) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, if (s.autoReadAnswers) "Stop reading answers aloud" else "Read answers aloud")
            }
            IconButton(onClick = { nav.go(ChatsRoute) }) { Icon(Icons.Rounded.History, "Past questions") }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.m)) {
            if (threadId == null && pendingQuestion == null) {
                item {
                    Text("Ask anything you've said or heard", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(Sp.xs))
                    Text("Answers come only from your own conversations, notes and memory — each with the moment it came from.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Sp.l))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Sp.s), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                        listOf("Brief me", "What's pending?", "Read my reminders", "Summarise yesterday", "What did I promise to do today?", "Remind me to drink water in 1 hour", "Who did I talk to today?")
                            .forEach { q -> AssistChip(onClick = { send(q) }, label = { Text(q) }) }
                    }
                }
            }
            items(messages, key = { it.id }) { m -> Bubble(m) { sheet = it } }
            pendingQuestion?.let { q -> if (messages.lastOrNull()?.text != q) item(key = "pq") { UserBubble(q) } }
            if (searching != null) item(key = "searching") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(36.dp))
                    Spacer(Modifier.width(Sp.s))
                    Text(searching!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            draft?.let { d -> item(key = "draft") { AssistantText(d) } }
        }
        Row(Modifier.fillMaxWidth().padding(Sp.gutter), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text(if (voice.listening) "Listening…" else if (useWeb) "Ask — memory and the web" else "Ask, or tap the mic: “what's pending?”") },
                shape = MaterialTheme.shapes.extraLarge, maxLines = 4,
            )
            if (s.webSearch != "off") IconButton(onClick = { useWeb = !useWeb }) {
                Icon(Icons.Rounded.Public, if (useWeb) "Stop searching the web" else "Also search the web", tint = if (useWeb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.width(Sp.s))
            if (input.isBlank()) FilledIconButton(onClick = { if (voice.listening) voice.stop() else voice.start() }) {
                Icon(if (voice.listening) Icons.Rounded.Stop else Icons.Rounded.Mic, if (voice.listening) "Stop" else "Speak")
            } else FilledIconButton(onClick = { send(input.trim()) }, enabled = draft == null && searching == null) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Ask")
            }
        }
    }

    sheet?.let { c ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.padding(horizontal = Sp.xl).padding(bottom = Sp.xxl)) {
                Text("[${c.n}] ${c.label.ifBlank { Fmt.dayTime(c.at) }}", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(Sp.s))
                Text(c.quote, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Sp.l))
                TextButton(onClick = {
                    sheet = null
                    when (c.kind) {
                        "line" -> c.conversationId?.let { nav.go(ConversationRoute(it, c.id)) }
                        "conversation" -> nav.go(ConversationRoute(c.id))
                        "note" -> nav.go(NoteRoute(c.id))
                        "fact" -> c.conversationId?.let { nav.go(ConversationRoute(it)) } ?: nav.go(MemoryRoute)
                        "task" -> nav.tab(TasksRoute)
                        "song" -> nav.go(app.echomind.ui.SongsRoute)
                        "web" -> c.url?.let { u -> runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                    }
                }) { Text(when (c.kind) { "line" -> "Open in transcript"; "web" -> "Open the page"; else -> "Open" }) }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 320.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Sp.l, vertical = Sp.m), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun AssistantText(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
private fun SpeakIcon(text: String) {
    IconButton(onClick = { App.graph.speaker.speak("Answer", text) }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Read aloud", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bubble(m: ChatMessage, onCite: (Citation) -> Unit) {
    if (m.role == "user") { UserBubble(m.text); return }
    val cites = remember(m.citations) { Ask.decode(m.citations) }
    Column {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { AssistantText(m.text) }
            SpeakIcon(m.text)
        }
        if (cites.isNotEmpty()) {
            Spacer(Modifier.height(Sp.s))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                cites.forEach { c ->
                    AssistChip(onClick = { onCite(c) }, label = {
                        Text("[${c.n}] ${c.label.substringBefore(" · ").ifBlank { Fmt.dayTime(c.at) }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    })
                }
            }
        }
        m.provider?.let { p ->
            Spacer(Modifier.height(4.dp))
            Text(if (Providers.isLocal(p)) "Answered on this phone" else "Answered with ${Providers.get(p)?.label}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun ChatsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val threads by remember { g.db.chat().observeThreads() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Past questions", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            if (threads.isEmpty()) item { EmptyState("No questions yet", "What you ask is kept here so you can come back to it.") }
            items(threads, key = { it.id }) { t ->
                EchoCard(onClick = { nav.go(AskRoute(t.id)) }) {
                    Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Fmt.ago(t.updatedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("Delete", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { scope.launch { g.db.chat().deleteMessages(t.id); g.db.chat().deleteThread(t.id) } }.padding(4.dp))
                    }
                }
            }
        }
    }
}

