package app.echomind.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.CheckIns
import app.echomind.capture.CaptureController
import app.echomind.core.CheckInSlot
import app.echomind.ui.CheckInRoute
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.Pill
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.SettingRow
import app.echomind.ui.components.SwitchRow
import app.echomind.ui.components.rememberVoiceInput
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import app.echomind.util.Js
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** A check-in: one question at a time, read aloud, answered by voice or typing; skip anything. */
@Composable
fun CheckInScreen(slotId: String?) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val slot = remember(slotId) { s.checkIns.firstOrNull { it.id == slotId } ?: CheckInSlot("now", 0, kind = defaultKind(), maxQuestions = 6) }
    val startedAt = remember { System.currentTimeMillis() }
    val questions = remember { mutableStateListOf<CheckIns.Q>() }
    var loading by remember { mutableStateOf(true) }
    var i by remember { mutableIntStateOf(0) }
    var heard by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var changes by remember { mutableStateOf<List<String>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var asked by remember { mutableIntStateOf(0) } // bumps to (re)ask the current question
    val wasListening = remember { g.capture.state.value.mode == CaptureController.Mode.LISTENING }

    fun finish() {
        if (saving || changes != null) return
        saving = true
        g.speaker.stop()
        scope.launch {
            changes = g.checkIns.finish(slot.kind, startedAt, questions.toList())
            saving = false
            // Anonymous usage: kind of check-in, how many were answered/skipped, length — never questions or answers.
            g.usage.event("check_in_done", mapOf("kind" to app.echomind.telemetry.UsageMath.checkInKind(slot.kind), "asked" to questions.size,
                "answered" to questions.count { it.answer != null }, "skipped" to questions.count { it.skipped }, "changes" to (changes?.size ?: 0),
                "took" to app.echomind.telemetry.UsageMath.sessionBucket(System.currentTimeMillis() - startedAt)))
            if (wasListening) g.capture.resume()
        }
    }
    fun answer(text: String) {
        val t = text.trim()
        if (i >= questions.size) return
        if (Regex("^(stop|bas|that's all|that is all|done for today|enough)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) { finish(); return }
        val skip = t.isEmpty() || g.checkIns.isSkip(t)
        questions[i] = questions[i].copy(answer = t.takeIf { !skip }, skipped = skip)
        heard = ""; typed = ""; typing = false
        if (i + 1 >= questions.size) finish() else { i++; asked++ }
    }
    val voice = rememberVoiceInput(onPartial = { heard = it }, onFinal = { answer(it) }, onNothing = { answer("") }, pauseMs = { g.settings.value.checkInPauseMs.toLong() }, surface = "check_in")

    LaunchedEffect(Unit) {
        // The microphone is needed for answers: listening pauses for the check-in and comes back after.
        if (wasListening) g.capture.pause(20)
        questions.addAll(g.checkIns.questions(slot.kind, slot.maxQuestions))
        loading = false
        asked++
    }
    DisposableEffect(Unit) { onDispose { g.speaker.stop(); voice.release(); if (wasListening && changes == null) g.capture.resume() } }

    // Ask the current question: read it aloud, wait for the voice to finish, then listen.
    LaunchedEffect(asked) {
        if (asked == 0 || loading || i >= questions.size || changes != null) return@LaunchedEffect
        val q = questions[i]
        if (s.checkInSpeak) {
            g.speaker.speak("Check-in", listOf(q.question))
            withTimeoutOrNull(3_000) { g.speaker.state.first { it.active } }
            withTimeoutOrNull(30_000) { g.speaker.state.first { !it.active } }
            delay(250)
        }
        if (s.checkInAutoListen && !typing) voice.start()
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.ime)) {
        ScreenTopBar("${slot.kind.replaceFirstChar { it.uppercase() }} check-in", onBack = { nav.back() }) {
            if (changes == null && !loading) TextButton(onClick = { finish() }) { Text("Finish") }
        }
        when {
            loading -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator(); Spacer(Modifier.height(Sp.m)); Text("Looking over your day…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            changes != null || saving -> LazyColumn(contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.s), state = app.echomind.ui.components.rememberKeptListState()) {
                item { Text(if (saving) "Saving…" else "Thanks — that's everything", style = MaterialTheme.typography.headlineSmall) }
                if (saving) item { LoadingIndicator() }
                changes?.let { c ->
                    if (c.isNotEmpty()) { item { SectionHeader("What changed") }; items(c) { Text("✓  $it", style = MaterialTheme.typography.bodyLarge) } }
                    item { SectionHeader("Your answers") }
                    items(questions.filter { it.answer != null }) { q ->
                        EchoCard { Text(q.question, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(q.answer.orEmpty(), style = MaterialTheme.typography.bodyLarge) }
                    }
                    item { Spacer(Modifier.height(Sp.m)); Button(onClick = { nav.back() }) { Text("Done") } }
                }
            }
            questions.isEmpty() -> EmptyState("Nothing to ask right now", "Questions come from your day: tasks, unnamed voices, gaps and your own questions.")
            else -> Column(Modifier.fillMaxSize().padding(Sp.gutter)) {
                LinearProgressIndicator(progress = { i.toFloat() / questions.size }, modifier = Modifier.fillMaxWidth())
                Text("Question ${i + 1} of ${questions.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = Sp.s))
                Spacer(Modifier.height(Sp.xl))
                val q = questions[i]
                AnimatedContent(q, label = "q") { cur ->
                    Column {
                        Text(cur.question, style = MaterialTheme.typography.headlineSmall)
                        if (cur.why.isNotBlank()) Text(cur.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Sp.s))
                    }
                }
                Spacer(Modifier.height(Sp.xl))
                if (voice.listening || heard.isNotBlank()) EchoCard(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)) {
                    Text(if (heard.isBlank()) "Listening… (say “skip” to move on)" else heard, style = MaterialTheme.typography.bodyLarge)
                }
                if (typing) OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), label = { Text("Your answer") }, minLines = 2,
                    trailingIcon = { IconButton(enabled = typed.isNotBlank(), onClick = { answer(typed) }) { Icon(Icons.AutoMirrored.Rounded.Send, "Answer") } })
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { voice.release(); asked++ }) { Icon(Icons.Rounded.Replay, "Ask again") }
                    FilledIconButton(onClick = { if (voice.listening) voice.stop() else { g.speaker.stop(); typing = false; voice.start() } }, modifier = Modifier.size(72.dp)) {
                        Icon(if (voice.listening) Icons.Rounded.Stop else Icons.Rounded.Mic, if (voice.listening) "Stop" else "Answer by voice", Modifier.size(32.dp))
                    }
                    IconButton(onClick = { voice.release(); answer("") }) { Icon(Icons.Rounded.SkipNext, "Skip") }
                }
                TextButton(onClick = { voice.release(); typing = !typing }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (typing) "Answer by voice instead" else "Type instead") }
            }
        }
    }
}

private fun defaultKind(): String = java.time.LocalTime.now().hour.let { if (it < 11) "morning" else if (it < 17) "midday" else "evening" }

/** Check-ins: schedule, what to ask, voice behaviour, preview and history — every control in one place. */
@Composable
fun CheckInsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val history by remember { g.db.checkins().observeRecent(30) }.collectAsStateWithLifecycle(emptyList())
    var preview by remember { mutableStateOf<List<CheckIns.Q>?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var editTime by remember { mutableStateOf<CheckInSlot?>(null) }
    fun set(change: (app.echomind.core.Settings) -> app.echomind.core.Settings) = g.scope.launch { g.settings.update(change); g.checkIns.schedule() }
    fun setSlot(slot: CheckInSlot) = set { st -> st.copy(checkIns = st.checkIns.map { if (it.id == slot.id) slot else it }) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Check-ins", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s), state = app.echomind.ui.components.rememberKeptListState()) {
            item {
                Text("The assistant asks about your day — did you do what you said, who was that, what happened while nothing was recorded — and keeps your journal and tasks honest. Skip any question.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.padding(top = Sp.s)) {
                    Button(onClick = { nav.go(CheckInRoute()) }) { Text("Start one now") }
                    OutlinedButton(enabled = !previewing, onClick = { previewing = true; scope.launch { preview = g.checkIns.questions(defaultKind(), 10); previewing = false } }) {
                        Text(if (previewing) "Thinking…" else "Preview questions")
                    }
                }
            }
            preview?.let { p ->
                item { SectionHeader("It would ask now · ${p.size}") }
                items(p) { q -> EchoCard(padding = PaddingValues(Sp.m)) { Text(q.question, style = MaterialTheme.typography.bodyLarge); Text("${q.kind} · ${q.why}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) } }
            }
            item { SectionHeader("Schedule") }
            items(s.checkIns, key = { it.id }) { slot ->
                EchoCard(padding = PaddingValues(Sp.m)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(slot.kind.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                            Text("%d:%02d".format(slot.minute / 60, slot.minute % 60) + " · up to ${slot.maxQuestions} questions", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { editTime = slot }) { Text("Time") }
                        Switch(slot.enabled, { setSlot(slot.copy(enabled = it)) })
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items((1..7).toList()) { d ->
                            FilterChip(d in slot.days, { setSlot(slot.copy(days = if (d in slot.days) slot.days - d else slot.days + d)) },
                                label = { Text(java.time.DayOfWeek.of(d).getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault())) })
                        }
                    }
                    var n by remember(slot.id) { mutableFloatStateOf(slot.maxQuestions.toFloat()) }
                    Slider(n, { n = it }, valueRange = 2f..15f, steps = 12, onValueChangeFinished = { setSlot(slot.copy(maxQuestions = n.toInt())) })
                }
            }
            item {
                TextButton(onClick = { set { st -> st.copy(checkIns = st.checkIns + CheckInSlot("c${System.currentTimeMillis()}", 18 * 60, kind = "custom", maxQuestions = 5)) } }) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(4.dp)); Text("Add a check-in time")
                }
            }
            item {
                SectionHeader("What it asks about")
                val topics = listOf("tasks" to "Tasks you planned or promised", "people" to "Who unnamed voices were", "gaps" to "Times nothing was recorded",
                    "day" to "How the day went", "feelings" to "How you feel", "plans" to "Tomorrow's priority", "learning" to "What you learned", "custom" to "My own questions")
                topics.forEach { (k, l) -> SwitchRow(l, null, k in s.checkInTopics) { on -> set { st -> st.copy(checkInTopics = if (on) st.checkInTopics + k else st.checkInTopics - k) } } }
            }
            item {
                SectionHeader("My own questions")
                s.checkInCustom.forEachIndexed { idx, q ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("•  $q", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { set { st -> st.copy(checkInCustom = st.checkInCustom.filterIndexed { j, _ -> j != idx }) } }) { Icon(Icons.Rounded.Close, "Remove") }
                    }
                }
                var newQ by remember { mutableStateOf("") }
                OutlinedTextField(newQ, { newQ = it }, Modifier.fillMaxWidth(), label = { Text("Add a question (e.g. Did you exercise today?)") }, singleLine = true,
                    trailingIcon = { IconButton(enabled = newQ.isNotBlank(), onClick = { val q = newQ.trim(); newQ = ""; set { st -> st.copy(checkInCustom = st.checkInCustom + q) } }) { Icon(Icons.Rounded.Add, "Add") } })
            }
            item {
                SectionHeader("Voice")
                SwitchRow("Read questions aloud", "In your earphones, or asks before using the speaker", s.checkInSpeak) { v -> set { it.copy(checkInSpeak = v) } }
                SwitchRow("Listen for the answer by itself", "Starts the microphone after each question", s.checkInAutoListen) { v -> set { it.copy(checkInAutoListen = v) } }
                var pause by remember { mutableFloatStateOf(s.checkInPauseMs.toFloat()) }
                Text("An answer ends after ${"%.1f".format(pause / 1000)} s of silence", style = MaterialTheme.typography.bodyMedium)
                Slider(pause, { pause = it }, valueRange = 1000f..6000f, onValueChangeFinished = { set { it.copy(checkInPauseMs = pause.toInt()) } })
                var skip by remember { mutableStateOf(s.checkInSkipWords) }
                OutlinedTextField(skip, { skip = it; set { st -> st.copy(checkInSkipWords = it) } }, Modifier.fillMaxWidth(), label = { Text("Words that skip a question") })
                Text("Say “stop” or “bas” to end early. Silence also skips.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow("Update today's journal after the evening check-in", null, s.checkInJournal) { v -> set { it.copy(checkInJournal = v) } }
            }
            item {
                SectionHeader("Quick questions during the day")
                SwitchRow("Ask one thing at a time in a notification", "“Who were you talking with at 3:40?”, “Did you send the invoice?” — answer right in the notification", s.quickQuestions) { v -> set { it.copy(quickQuestions = v) } }
                if (s.quickQuestions) {
                    var q by remember { mutableFloatStateOf(s.quickQuestionsPerDay.toFloat()) }
                    Text("At most ${q.toInt()} a day, 2 hours apart, 10 AM – 9 PM", style = MaterialTheme.typography.bodyMedium)
                    Slider(q, { q = it }, valueRange = 1f..8f, steps = 6, onValueChangeFinished = { set { it.copy(quickQuestionsPerDay = q.toInt()) } })
                }
            }
            item { SectionHeader("History · ${history.size}") }
            if (history.isEmpty()) item { Text("No check-ins yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(history, key = { it.id }) { c ->
                val qa = runCatching { Js.json.decodeFromString(ListSerializer(CheckIns.Q.serializer()), c.qa) }.getOrDefault(emptyList())
                val ch = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), c.changes) }.getOrDefault(emptyList())
                var open by remember { mutableStateOf(false) }
                EchoCard(onClick = { open = !open }, padding = PaddingValues(Sp.m)) {
                    Text("${c.kind.replaceFirstChar { it.uppercase() }} · ${Fmt.dayTime(c.startedAt)}", style = MaterialTheme.typography.titleSmall)
                    Text("${qa.count { it.answer != null }} answered · ${qa.count { it.skipped }} skipped${if (ch.isNotEmpty()) " · ${ch.size} changes" else ""}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (open) {
                        qa.forEach { q -> Text("${q.question}\n→ ${if (q.skipped) "skipped" else q.answer ?: "—"}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                        ch.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall, color = LocalSemantic.current.positive) }
                    }
                }
            }
        }
    }
    editTime?.let { slot ->
        val st = rememberTimePickerState(slot.minute / 60, slot.minute % 60, is24Hour = false)
        androidx.compose.material3.AlertDialog(onDismissRequest = { editTime = null }, text = { TimePicker(st) },
            confirmButton = { TextButton(onClick = { setSlot(slot.copy(minute = st.hour * 60 + st.minute)); editTime = null }) { Text("OK") } },
            dismissButton = {
                Row {
                    if (slot.kind == "custom") TextButton(onClick = { set { s2 -> s2.copy(checkIns = s2.checkIns.filter { it.id != slot.id }) }; editTime = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { editTime = null }) { Text("Cancel") }
                }
            })
    }
}
