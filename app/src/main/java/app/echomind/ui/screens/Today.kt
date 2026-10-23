package app.echomind.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AddTask
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.Brain
import app.echomind.brain.DayMetrics
import app.echomind.capture.CaptureController.Mode
import app.echomind.core.Feature
import app.echomind.data.Conversation
import app.echomind.ui.ConversationRoute
import app.echomind.ui.DayRoute
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.ModelsRoute
import app.echomind.ui.PowerRoute
import app.echomind.ui.ProcessingRoute
import app.echomind.ui.ProvidersRoute
import app.echomind.ui.SettingsRoute
import app.echomind.ui.TasksRoute
import app.echomind.ui.components.Dot
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.LevelMeter
import app.echomind.ui.components.Pill
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import app.echomind.util.Js
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun TodayScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val (from, to) = remember(today) { g.journal.bounds(today) }
    val convs by remember(today) { g.db.conversations().inRange(from - 86_400_000L * 2, Long.MAX_VALUE) }.collectAsStateWithLifecycle(emptyList())
    val upcoming by remember { g.db.tasks().observeUpcoming(System.currentTimeMillis(), 3) }.collectAsStateWithLifecycle(emptyList())
    val suggested by remember { g.db.tasks().suggestedCount() }.collectAsStateWithLifecycle(0)
    val day by remember { g.db.days().observeRecent(2) }.collectAsStateWithLifecycle(emptyList())

    val hour = LocalTime.now().hour
    val greet = when { hour < 5 -> "Good night"; hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }

    val ownEvents by remember { g.db.events().observeRange(System.currentTimeMillis() - 3_600_000L, System.currentTimeMillis() + 2 * 86_400_000L) }.collectAsStateWithLifecycle(emptyList())
    val nextEvents by produceState(emptyList<app.echomind.data.EventRow>(), ownEvents, s.showPhoneCalendar) {
        val now = System.currentTimeMillis()
        value = g.agenda.range(now - 30 * 60_000L, now + 36 * 3_600_000L).filter { it.status == "confirmed" && if (it.allDay) it.startAt >= from else (it.endAt ?: it.startAt) > now }.take(3)
    }
    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Sp.gutter, vertical = Sp.s),
        verticalArrangement = Arrangement.spacedBy(Sp.m),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Sp.s)) {
                Column(Modifier.weight(1f)) {
                    Text(Fmt.dayLong(today), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (s.userName.isNotBlank()) "$greet, ${s.userName.substringBefore(' ')}" else greet, style = MaterialTheme.typography.headlineMedium)
                }
                IconButton(onClick = { nav.go(SettingsRoute) }) { Icon(Icons.Rounded.Settings, "Settings") }
            }
        }
        // One column: a card with nothing to show adds no gap.
        item(key = "top") {
            Column(verticalArrangement = Arrangement.spacedBy(Sp.m)) {
                QuickActions(); ListeningCard(); app.echomind.ui.screens.StatusCard(); DayGlance(); DaySummaryCard(); SetupHealth(); WorkCard(); ForYou()
            }
        }
        if (suggested > 0) item(key = "review") {
            EchoCard(onClick = { nav.tab(TasksRoute) }, color = MaterialTheme.colorScheme.primaryContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(Sp.m))
                    Column(Modifier.weight(1f)) {
                        Text("$suggested ${if (suggested == 1) "thing" else "things"} to review", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Tasks and reminders found in your conversations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
        item(key = "todaytasks") { TodayTasks() }
        if (nextEvents.isNotEmpty()) {
            item(key = "ev_h") { SectionHeader("Next on your calendar", action = "Calendar", onAction = { nav.tab(app.echomind.ui.CalendarRoute) }) }
            items(nextEvents, key = { "ev${it.id}_${it.deviceId}_${it.startAt}" }) { e ->
                EchoCard(onClick = { if (e.id > 0) nav.go(app.echomind.ui.EventRoute(e.id)) else nav.tab(app.echomind.ui.CalendarRoute) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Event, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Sp.m))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (e.allDay) Fmt.day(e.startAt) else Fmt.dayTime(e.startAt) + (e.location?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (upcoming.isNotEmpty()) {
            item(key = "up_h") { SectionHeader("Coming up", action = "All reminders", onAction = { nav.go(app.echomind.ui.RemindersRoute) }) }
            items(upcoming, key = { "r${it.id}" }) { t ->
                EchoCard(onClick = { nav.tab(TasksRoute) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Alarm, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Sp.m))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(Fmt.dayTime(t.remindAt!!), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        val shown = convs.filter { it.state != "skipped" && !it.ignored }.take(8)
        item(key = "recent_h") { SectionHeader("Latest conversations", action = "Timeline", onAction = { nav.go(app.echomind.ui.TimelineRoute) }) }
        if (shown.isEmpty()) item(key = "recent_empty") {
            Text(
                if (s.captureWanted) "Conversations appear here a few minutes after they happen." else "Start listening and your day will appear here.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Sp.s),
            )
        }
        items(shown, key = { "c${it.id}" }, contentType = { "conv" }) { c -> ConversationRow(c) { nav.go(ConversationRoute(c.id)) } }
        val journal = day.firstOrNull { it.generatedAt != null }
        if (journal != null) {
            item(key = "journal") {
                SectionHeader("Journal")
                EchoCard(onClick = { nav.go(DayRoute(journal.day)) }) {
                    Text(Fmt.day(LocalDate.parse(journal.day)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(journal.headline ?: "Your day", style = MaterialTheme.typography.titleMedium)
                    journal.diary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val m = runCatching { Js.json.decodeFromString(DayMetrics.serializer(), journal.metrics) }.getOrNull()
                    if (m != null && m.conversations > 0) {
                        Spacer(Modifier.height(Sp.s))
                        Text("${m.conversations} conversations · ${m.talkMinutes} min talk · ${m.people} people", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(Sp.xl)) }
    }
}

/** What the assistant noticed on its own — the same nudges it may notify, shown calmly here. */
@Composable
private fun ForYou() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val since = remember { System.currentTimeMillis() - 24 * 3_600_000L }
    val list by remember { g.db.nudges().observeOpen(since) }.collectAsStateWithLifecycle(emptyList())
    if (list.isEmpty()) return
    SectionHeader("For you")
    Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
        list.take(3).forEach { n ->
            EchoCard(onClick = n.conversationId?.let { { nav.go(ConversationRoute(it)) } }, color = if (n.urgency >= 3) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Sp.s))
                    Text(n.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                }
                Text(n.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { g.speaker.speak(n.title, listOf(n.title + ".", n.body)) }) { Text("Listen") }
                    if (n.taskText != null) TextButton(onClick = { scope.launch { g.nudges.act(n.id) { g.tasks.addQuick(it) }; g.notifier.cancelNudge(n.id) } }) { Text("Add task") }
                    TextButton(onClick = { scope.launch { g.nudges.dismiss(n.id); g.notifier.cancelNudge(n.id) } }) { Text("Dismiss") }
                }
            }
        }
    }
}

/** One tap to capture: a task (natural language), a reminder, a note, or a question. */
@Composable
private fun QuickActions() {
    val nav = LocalNav.current
    var adding by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.fillMaxWidth()) {
        QuickButton(Icons.Rounded.AddTask, "Task", Modifier.weight(1f)) { adding = true }
        QuickButton(Icons.Rounded.RecordVoiceOver, "Check-in", Modifier.weight(1f)) { nav.go(app.echomind.ui.CheckInRoute()) }
        QuickButton(Icons.Rounded.EditNote, "Note", Modifier.weight(1f)) { nav.go(app.echomind.ui.NewNoteRoute()) }
        QuickButton(Icons.Rounded.QuestionAnswer, "Ask", Modifier.weight(1f)) { nav.tab(app.echomind.ui.AskRoute()) }
        val scope = rememberCoroutineScope()
        QuickButton(Icons.AutoMirrored.Rounded.VolumeUp, "Brief me", Modifier.weight(1f)) {
            scope.launch { val b = App.graph.briefing.morning(); App.graph.speaker.speak(b.title, b.parts) }
        }
    }
    if (adding) androidx.compose.material3.ModalBottomSheet(onDismissRequest = { adding = false }) {
        Column(Modifier.padding(bottom = Sp.xl)) {
            Text("New task", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Sp.xl))
            Text("Try: “Pay electricity bill Friday 5pm !1 #home”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Sp.xl, vertical = 4.dp))
            QuickAddBar { adding = false }
        }
    }
}

@Composable
private fun QuickButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.material3.Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier) {
        Column(Modifier.padding(vertical = Sp.m), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun ListeningCard() {
    val g = App.graph
    val sem = LocalSemantic.current
    val st by g.capture.state.collectAsStateWithLifecycle()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { visible = false }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { visible = true }

    val listening = st.mode == Mode.LISTENING
    val (title, detail, color) = when (st.mode) {
        Mode.LISTENING -> Triple("Listening", st.device.ifBlank { "Phone microphone" }, sem.recording)
        Mode.BLOCKED_CALL -> Triple("On a call", "Your call recording will be added afterwards", sem.warning)
        Mode.BLOCKED_APP -> Triple("Mic in use by another app", "Listening resumes when it's free", sem.warning)
        Mode.PAUSED -> Triple("Paused", if (s.pausedUntil == Long.MAX_VALUE) "Until you resume" else "Until ${Fmt.time(s.pausedUntil)}", MaterialTheme.colorScheme.outline)
        Mode.QUIET -> Triple("Quiet hours", "Not listening while you sleep", MaterialTheme.colorScheme.outline)
        Mode.PROCESSING -> Triple("Not listening", "Finishing what was recorded", MaterialTheme.colorScheme.outline)
        Mode.OFF -> Triple(if (s.captureWanted) "Not listening right now" else "Not listening", if (s.captureWanted) "Tap Resume to start again" else "Your day isn't being captured", MaterialTheme.colorScheme.outline)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) g.capture.start()
    }
    val ctx = LocalContext.current

    EchoCard(modifier = Modifier.animateContentSize(), color = if (listening) sem.recording.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color, 10)
            Spacer(Modifier.width(Sp.s))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (listening) Icons.Rounded.Mic else if (st.mode == Mode.BLOCKED_CALL) Icons.Rounded.Call else Icons.Rounded.MicOff, null, tint = color)
        }
        if (listening) {
            Spacer(Modifier.height(Sp.m))
            LevelMeter(Modifier.fillMaxWidth(), active = visible)
        }
        Spacer(Modifier.height(Sp.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), verticalAlignment = Alignment.CenterVertically) {
            when (st.mode) {
                Mode.LISTENING, Mode.BLOCKED_CALL, Mode.BLOCKED_APP -> {
                    Column {
                        FilledTonalButton(onClick = { menu = true }) { Icon(Icons.Rounded.Pause, null); Spacer(Modifier.width(6.dp)); Text("Pause") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            listOf(15L to "15 minutes", 60L to "1 hour", 180L to "3 hours", 0L to "Until I resume").forEach { (m, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; g.capture.pause(m) })
                            }
                        }
                    }
                    Column {
                        OutlinedButton(onClick = { forget = true }) { Text("Forget…") }
                        DropdownMenu(expanded = forget, onDismissRequest = { forget = false }) {
                            listOf(5, 15, 60).forEach { m ->
                                DropdownMenuItem(text = { Text("Forget the last $m minutes") }, onClick = { forget = false; scope.launch { g.capture.forget(m) } })
                            }
                        }
                    }
                }
                Mode.PAUSED -> Button(onClick = { g.capture.resume() }) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Resume") }
                else -> Button(onClick = {
                    if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) g.capture.start()
                    else micPermission.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                }) { Icon(Icons.Rounded.Mic, null); Spacer(Modifier.width(6.dp)); Text(if (s.captureWanted) "Resume listening" else "Start listening") }
            }
            if (st.mode != Mode.OFF && st.mode != Mode.PROCESSING) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { g.capture.stop() }) { Text("Stop") }
            }
        }
        if (listening) LiveLines()
    }
}

/** The last few lines transcribed — proof that it is really working. */
@Composable
private fun LiveLines() {
    val g = App.graph
    val lines by remember { g.db.utterances().latest(3) }.collectAsStateWithLifecycle(emptyList())
    val recent = lines.filter { System.currentTimeMillis() - it.startAt < 30 * 60_000L }.reversed()
    if (recent.isEmpty()) return
    Spacer(Modifier.height(Sp.m))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        recent.forEach { u ->
            Text(
                "${Fmt.time(u.startAt)}  ${u.text}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** What is being worked on in the background, and how long it will take. */
@Composable
private fun WorkCard() {
    val g = App.graph
    val nav = LocalNav.current
    val brain by g.brain.state.collectAsStateWithLifecycle()
    val t by g.transcriber.status.collectAsStateWithLifecycle()
    val gov by g.governor.state.collectAsStateWithLifecycle()
    val pending by remember { g.db.audio().pendingCount() }.collectAsStateWithLifecycle(0)
    val pendingMs by remember { g.db.audio().pendingMs() }.collectAsStateWithLifecycle(0L)
    val ready by remember { g.db.conversations().readyCount() }.collectAsStateWithLifecycle(0)
    if (pending == 0 && ready == 0 && brain.phase == Brain.Phase.IDLE) return
    val eta = (pendingMs / t.speed.coerceAtLeast(0.2)).toLong()
    EchoCard(onClick = { nav.go(ProcessingRoute) }) {
        Text(
            when (brain.phase) {
                Brain.Phase.TRANSCRIBING -> "Transcribing${if (t.pieces > 0) " · piece ${t.piece} of ${t.pieces}" else ""}"
                Brain.Phase.THINKING -> "Making notes · ${brain.detail}"
                Brain.Phase.JOURNAL, Brain.Phase.REVIEW -> brain.detail
                else -> if (!gov.allowed) "Paused for now" else "Catching up"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        val parts = buildList {
            if (pending > 0) add("${Fmt.duration(pendingMs)} of speech to transcribe${if (eta > 60_000) " · ~${Fmt.duration(eta)}" else ""}")
            if (ready > 0) add("$ready conversation${if (ready == 1) "" else "s"} waiting for notes")
        }
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!gov.allowed && gov.reason.isNotBlank()) Text(gov.reason, style = MaterialTheme.typography.bodySmall, color = LocalSemantic.current.warning)
        brain.aiBlocked?.let { if (ready > 0) Text(it, style = MaterialTheme.typography.bodySmall, color = LocalSemantic.current.warning) }
        if (brain.phase != Brain.Phase.IDLE) {
            Spacer(Modifier.height(Sp.s))
            if (brain.phase == Brain.Phase.TRANSCRIBING && t.pieces > 0) LinearWavyProgressIndicator(progress = { t.piece.toFloat() / t.pieces }, modifier = Modifier.fillMaxWidth())
            else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Everything that stops EchoMind from working fully, each with a one-tap fix. Disappears when all is well. */
@Composable
private fun SetupHealth() {
    val g = App.graph
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val keys by g.secrets.present.collectAsStateWithLifecycle()
    val models by g.models.states.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    val resumeTick = app.echomind.ui.components.rememberResumeTick()
    var missingLangs by remember { mutableStateOf<List<String>>(emptyList()) }
    var offlineless by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(s.speechLanguages, tick, resumeTick) {
        if (s.provider(Feature.TRANSCRIBE) == "android" && g.androidSpeech.available()) {
            val sup = g.androidSpeech.support(s.speechLanguages)
            val known = sup.installed + sup.pending + sup.supported
            val notInstalled = if (sup.available && known.isNotEmpty()) s.speechLanguages.filter { l -> sup.installed.none { it.equals(l, true) } && sup.pending.none { it.equals(l, true) } } else emptyList()
            // Google doesn't offer every language offline; don't offer a download that can't happen.
            offlineless = notInstalled.filter { l -> sup.supported.isNotEmpty() && sup.supported.none { it.equals(l, true) } }
            missingLangs = notInstalled - offlineless.toSet()
        }
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    @Suppress("UNUSED_VARIABLE") val t = tick + resumeTick + keys.size + models.size
    val issues = buildList {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            add(Triple("Microphone permission needed", "EchoMind can't hear anything without it.", "Allow") to { perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) })
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            add(Triple("Notifications are off", "Reminders and the listening status need them.", "Allow") to { perms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) })
        if (!ignoringBattery(ctx))
            add(Triple("Battery optimisation is on", "Android may stop EchoMind in the background.", "Fix") to { requestBattery(ctx) })
        if (g.ai.readiness(Feature.NOTES) != null)
            add(Triple("Notes need an AI model", g.ai.readiness(Feature.NOTES)!!, "Set up") to { nav.go(if (s.provider(Feature.NOTES) == "local") ModelsRoute else ProvidersRoute) })
        if (offlineless.isNotEmpty())
            add(Triple("No offline Google pack for ${offlineless.joinToString { java.util.Locale.forLanguageTag(it).displayLanguage }}",
                "Lines in it will be missed. Get the Omnilingual model (all Indian languages) or use Sarvam for speech.", "Options") to { nav.go(app.echomind.ui.RecordingRoute) })
        if (missingLangs.isNotEmpty())
            add(Triple("Speech packs missing: ${missingLangs.joinToString()}", "Download Google's offline speech packs for these languages.", "Download") to { g.speechPacks.download(missingLangs); nav.go(app.echomind.ui.RecordingRoute) })
        if (!ctx.getSharedPreferences("setup", Context.MODE_PRIVATE).getBoolean("vivoDone", false) && android.os.Build.MANUFACTURER.lowercase() in setOf("vivo", "iqoo"))
            add(Triple("Keep EchoMind running on vivo", "Two settings stop Funtouch from closing it in the background.", "Show me") to { nav.go(PowerRoute) })
    }
    if (issues.isEmpty()) return
    EchoCard(color = LocalSemantic.current.warning.copy(alpha = 0.08f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, tint = LocalSemantic.current.warning, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Sp.s))
            Text("Finish setting up", style = MaterialTheme.typography.titleSmall)
        }
        issues.forEach { (text, fix) ->
            Row(Modifier.fillMaxWidth().padding(top = Sp.m), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text.first, style = MaterialTheme.typography.bodyMedium)
                    Text(text.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = fix) { Text(text.third) }
            }
        }
    }
}

fun ignoringBattery(ctx: Context) = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

@android.annotation.SuppressLint("BatteryLife")
fun requestBattery(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
        .onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

@Composable
fun ConversationRow(c: Conversation, onClick: () -> Unit) {
    val sem = LocalSemantic.current
    EchoCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${Fmt.day(c.startAt).let { if (it == "Today") "" else "$it · " }}${Fmt.span(c.startAt, c.endAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (c.source == "call") Pill(c.contact?.let { "Call · $it" } ?: "Call")
        }
        Spacer(Modifier.height(2.dp))
        when (c.state) {
            "done" -> {
                Text(c.title ?: "Conversation", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                c.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            "open" -> {
                Row(verticalAlignment = Alignment.CenterVertically) { Dot(sem.recording, 7); Spacer(Modifier.width(6.dp)); Text("Happening now · ${c.words} words so far", style = MaterialTheme.typography.titleSmall) }
            }
            "failed" -> Text("Couldn't make notes — ${c.error?.take(80) ?: "tap to retry"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            else -> Text("${c.words} words · notes coming soon", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Overdue and today's tasks, tickable from home; plus a one-line add. */
@Composable
private fun TodayTasks() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val open by remember { g.db.tasks().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    val start = remember { java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val end = start + 86_400_000L
    val due = open.filter { (it.dueAt != null && it.dueAt < end) || (it.dueAt == null && it.createdAt >= start) }
        .sortedWith(compareBy({ it.dueAt == null }, { it.priority }, { it.dueAt }))
    var adding by remember { mutableStateOf("") }
    Column {
        SectionHeader("Today's tasks${if (due.isNotEmpty()) " · ${due.size}" else ""}", action = "All tasks", onAction = { nav.tab(TasksRoute) })
        EchoCard(padding = PaddingValues(start = 4.dp, end = Sp.m, top = 4.dp, bottom = Sp.s)) {
            if (due.isEmpty()) Text("Nothing due today.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Sp.m))
            due.take(7).forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { nav.go(app.echomind.ui.TaskRoute(t.id)) }, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { g.tasks.complete(t) } }) { Icon(Icons.Rounded.RadioButtonUnchecked, "Mark done", tint = MaterialTheme.colorScheme.outline) }
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val overdue = t.dueAt != null && t.dueAt < start
                        val at = t.remindAt ?: t.dueAt
                        val timed = at != null && java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalTime().let { !(it.hour == 9 && it.minute == 0) || t.remindAt != null }
                        if (overdue || timed) Text(if (overdue) "Overdue · ${Fmt.day(t.dueAt!!)}" else Fmt.time(at!!), style = MaterialTheme.typography.labelSmall,
                            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        t.aiNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (t.doneSuggestedAt != null) app.echomind.ui.theme.LocalSemantic.current.positive else MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (t.doneSuggestedAt != null) androidx.compose.material3.TextButton(onClick = { scope.launch { g.tasks.complete(t) } }) { Text("Done") }
                }
            }
            if (due.size > 7) Text("+${due.size - 7} more", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { nav.tab(TasksRoute) }.padding(start = Sp.m, top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Sp.m, top = 4.dp)) {
                androidx.compose.material3.OutlinedTextField(adding, { adding = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add for today…") },
                    shape = MaterialTheme.shapes.extraLarge)
                IconButton(enabled = adding.isNotBlank(), onClick = { val t = adding.trim(); adding = ""; scope.launch { g.tasks.addQuick(t, toToday = true) } }) {
                    Icon(Icons.Rounded.Add, "Add task")
                }
            }
        }
    }
}

/** Today's focus and numbers, opening the plan and the dashboard. */
/** The day in words: the latest journal and night review, one tap from the full review and all insights. */
@Composable
private fun DaySummaryCard() {
    val g = App.graph
    val nav = LocalNav.current
    val today = java.time.LocalDate.now()
    val days by remember { g.db.days().observeRecent(3) }.collectAsStateWithLifecycle(emptyList())
    val reviews by remember { g.db.analyses().observeDays(today.minusDays(2).toString(), today.toString()) }.collectAsStateWithLifecycle(emptyList())
    val progress by g.dayReview.progress.collectAsStateWithLifecycle()
    val entry = days.firstOrNull { !it.headline.isNullOrBlank() || !it.diary.isNullOrBlank() }
    val day = entry?.day ?: reviews.lastOrNull()?.ref ?: today.minusDays(1).toString()
    val review = reviews.firstOrNull { it.ref == day }?.let { g.dayReview.decode(it.json) }
    EchoCard(onClick = { nav.go(app.echomind.ui.DayReviewRoute(day)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (entry != null) Fmt.day(java.time.LocalDate.parse(day)) + " · your day" else "Your day review", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Text("All insights", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(CircleShape).clickable { nav.go(app.echomind.ui.InsightsRoute) }.padding(horizontal = Sp.s, vertical = 2.dp))
        }
        Text(entry?.headline?.takeIf { it.isNotBlank() } ?: "Tonight EchoMind reads your whole day again and writes it up",
            style = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 20.sp, lineHeight = 26.sp))
        entry?.diary?.takeIf { it.isNotBlank() }?.let { Text(it, style = app.echomind.ui.components.DiaryStyle.copy(fontSize = 15.sp, lineHeight = 23.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
        if (review != null) Text(
            listOfNotNull(
                "${review.people.count { it.named }} people",
                "${review.topics.size} topics",
                review.coachPoints.takeIf { it > 0 }?.let { "$it coaching points" },
                review.lessons.firstOrNull()?.title?.let { "Lesson: $it" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        progress?.let { p ->
            Text("Night review: ${p.label}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            androidx.compose.material3.LinearProgressIndicator(progress = { p.done.toFloat() / p.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

@Composable
private fun DayGlance() {
    val g = App.graph
    val nav = LocalNav.current
    val tick = app.echomind.ui.components.rememberResumeTick()
    val st by produceState<app.echomind.brain.Stats.Day?>(null, tick) { value = g.stats.day(java.time.LocalDate.now()) }
    val planRaw by remember { g.plans.observe("day") }.collectAsStateWithLifecycle(null)
    val plan = g.plans.decode(planRaw)
    Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
        EchoCard(onClick = { nav.go(app.echomind.ui.PlanRoute) }, modifier = Modifier.weight(1.4f), padding = PaddingValues(Sp.m)) {
            Text("Today's focus", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(plan?.focus?.ifBlank { null } ?: "Tap to plan your day", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            plan?.missing?.size?.takeIf { it > 0 }?.let { Text("$it thing${if (it == 1) "" else "s"} you might be missing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
        }
        EchoCard(onClick = { nav.go(app.echomind.ui.DashboardRoute) }, modifier = Modifier.weight(1f), padding = PaddingValues(Sp.m)) {
            Text("Your day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            val s = st
            Text(if (s == null || s.talkMin == 0) "No talk yet" else "${s.talkMin} min talk", style = MaterialTheme.typography.bodyMedium)
            if (s != null && s.talkMin > 0) Text("You ${s.meShare}% · ${s.conversations} conv.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s != null) Text("${s.tasksDone} tasks done", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

