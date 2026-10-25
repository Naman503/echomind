package app.echomind.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.data.AudioItem
import app.echomind.ui.ConversationRoute
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.Pill
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch

fun retentionLabel(days: Int) = when {
    days < 0 -> "forever"
    days == 0 -> "until transcribed"
    days == 1 -> "for 1 day"
    else -> "for $days days"
}

private val retentionChoices = listOf(0 to "Don't keep", 1 to "1 day", 3 to "3 days", 7 to "7 days", 30 to "30 days", 90 to "90 days", 365 to "1 year", -1 to "Forever")

/** Every recording still on the phone: listen back, keep forever, or delete — and how long they're kept. */
@Composable
fun RecordingsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val all by remember { g.db.audio().observeRecordings(2_000) }.collectAsStateWithLifecycle(emptyList())
    val play by g.playback.state.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("all") }
    var confirmAll by remember { mutableStateOf(false) }
    var shorten by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var menu by remember { mutableStateOf(false) }
    val used by produceState(0L, all.size) { value = g.recordings.bytesUsed() }
    val list = remember(all, filter) {
        when (filter) {
            "calls" -> all.filter { it.source == "call" }
            "forever" -> all.filter { it.keepUntil == Long.MAX_VALUE }
            "waiting" -> all.filter { it.state != "done" }
            else -> all
        }
    }
    LaunchedEffect(list) { g.playback.queue = list.sortedBy { it.startedAt } }

    Scaffold(
        topBar = {
            ScreenTopBar("Recordings", onBack = { nav.back() }) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Delete all kept recordings", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmAll = true })
                }
            }
        },
        bottomBar = { if (play.item != null) PlayerBar() },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(Sp.gutter, Sp.s, Sp.gutter, 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s), state = app.echomind.ui.components.rememberKeptListState()) {
            item {
                EchoCard(color = LocalSemantic.current.onDevice.copy(alpha = 0.08f)) {
                    Text("Kept ${retentionLabel(s.keepRecordingsDays)}", style = MaterialTheme.typography.titleMedium)
                    Text("${all.count { it.keptPath != null }} recordings · ${Fmt.bytes(used)} on this phone. Recordings never leave the phone; their words stay after the audio is deleted.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Sp.s))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                        items(retentionChoices) { (d, label) ->
                            FilterChip(s.keepRecordingsDays == d, {
                                // A shorter time deletes older recordings at once, so ask first.
                                val now = System.currentTimeMillis()
                                val goes = all.count { it.keptPath != null && it.keepUntil == null && d >= 0 && it.endedAt < now - d * 86_400_000L }
                                if (goes > 0) shorten = d to goes
                                else scope.launch { g.settings.update { it.copy(keepRecordingsDays = d) } }
                            }, label = { Text(label) })
                        }
                    }
                    Spacer(Modifier.height(Sp.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Quality", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
                        FilterChip(s.recordingQuality == "small", { scope.launch { g.settings.update { it.copy(recordingQuality = "small") } } }, label = { Text("Small · ~7 MB/h") })
                        Spacer(Modifier.width(Sp.s))
                        FilterChip(s.recordingQuality == "clear", { scope.launch { g.settings.update { it.copy(recordingQuality = "clear") } } }, label = { Text("Clear · ~14 MB/h") })
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    items(listOf("all" to "All", "calls" to "Calls", "forever" to "Kept forever", "waiting" to "Not transcribed yet")) { (k, l) ->
                        FilterChip(filter == k, { filter = k }, label = { Text(l) })
                    }
                }
            }
            if (list.isEmpty()) item {
                EmptyState("No recordings", if (s.keepRecordingsDays == 0) "Recordings are deleted as soon as their words are safe. Choose how long to keep them above." else "Recordings appear here once something has been heard.")
            }
            list.groupBy { Fmt.date(it.startedAt) }.forEach { (day, items) ->
                item(key = "d$day") { SectionHeader("${Fmt.day(day)} · ${Fmt.duration(items.sumOf { it.voicedMs.takeIf { v -> v > 0 } ?: it.durationMs })} of speech") }
                items(items, key = { it.id }) { a -> RecordingRow(a, s.keepRecordingsDays, play.item?.id == a.id && play.playing) }
            }
        }
    }
    shorten?.let { (d, n) ->
        AlertDialog(
            onDismissRequest = { shorten = null },
            title = { Text("Delete $n older recording${if (n == 1) "" else "s"}?") },
            text = { Text("Keeping recordings ${retentionLabel(d)} deletes $n that ${if (n == 1) "is" else "are"} older right away. Their transcripts stay. Mark any you want with “Keep forever” first.") },
            confirmButton = { TextButton(onClick = { shorten = null; scope.launch { g.settings.update { it.copy(keepRecordingsDays = d) }; g.recordings.sweep() } }) { Text("Delete and change", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { shorten = null }) { Text("Cancel") } },
        )
    }
    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false },
        title = { Text("Delete all kept recordings?") },
        text = { Text("The audio is deleted for good. Transcripts, notes and memories made from it stay.") },
        confirmButton = { TextButton(onClick = { confirmAll = false; g.playback.stop(); scope.launch { all.filter { it.keptPath != null }.forEach { g.recordings.delete(it) } } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
    )
}

@Composable
private fun RecordingRow(a: AudioItem, days: Int, playing: Boolean) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val info by produceState<Pair<Long?, String?>>(null to null, a.id, a.state) {
        val lines = g.db.utterances().forAudio(a.id)
        value = g.db.utterances().conversationOfAudio(a.id) to lines.joinToString(" ") { it.text }.take(160).ifBlank { null }
    }
    val playable = g.recordings.file(a) != null
    EchoCard(onClick = info.first?.let { { nav.go(ConversationRoute(it)) } }, padding = PaddingValues(start = Sp.s, top = Sp.s, bottom = Sp.s, end = 0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(enabled = playable, onClick = { if (g.playback.state.value.item?.id == a.id) g.playback.toggle() else g.playback.play(a) }) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play")
            }
            Spacer(Modifier.width(Sp.s))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (a.source == "call") Icons.Rounded.Call else Icons.Rounded.Mic, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text("${Fmt.span(a.startedAt, a.endedAt)}${a.contact?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelLarge)
                }
                info.second?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    when (a.state) {
                        "done" -> {}
                        "failed" -> Pill("Couldn't transcribe", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                        else -> Pill("Waiting to transcribe")
                    }
                    Text(
                        when {
                            a.keptPath == null -> Fmt.duration(a.durationMs)
                            a.keepUntil == Long.MAX_VALUE -> "${Fmt.duration(a.durationMs)} · kept forever"
                            days < 0 -> "${Fmt.duration(a.durationMs)} · kept forever"
                            else -> {
                                val left = ((a.endedAt + days * 86_400_000L - System.currentTimeMillis()) / 3_600_000L).coerceAtLeast(0)
                                "${Fmt.duration(a.durationMs)} · deleted in ${if (left < 24) "$left h" else "${left / 24} days"}"
                            }
                        },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                if (a.keptPath != null) DropdownMenuItem(text = { Text(if (a.keepUntil == Long.MAX_VALUE) "Delete with the others" else "Keep forever") }, onClick = {
                    menu = false; scope.launch { g.recordings.keepForever(a, a.keepUntil != Long.MAX_VALUE) }
                })
                info.first?.let { cid -> DropdownMenuItem(text = { Text("Open conversation") }, onClick = { menu = false; nav.go(ConversationRoute(cid)) }) }
                if (a.state == "failed") DropdownMenuItem(text = { Text("Try transcribing again") }, onClick = {
                    menu = false; scope.launch { g.db.audio().update(a.copy(state = "pending", attempts = 0, retryAt = 0, error = null)); g.brain.processNow() }
                })
                if (a.keptPath != null) DropdownMenuItem(text = { Text("Delete recording", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirm = true })
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete this recording?") },
        text = { Text("The audio from ${Fmt.span(a.startedAt, a.endedAt)} is deleted for good. Its transcript stays.") },
        confirmButton = { TextButton(onClick = { confirm = false; if (g.playback.state.value.item?.id == a.id) g.playback.stop(); scope.launch { g.recordings.delete(a) } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

/** Bottom player for recordings: position, ±10 s, speed. */
@Composable
fun PlayerBar() {
    val g = App.graph
    val p by g.playback.state.collectAsStateWithLifecycle()
    val item = p.item ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Sp.gutter, vertical = Sp.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${Fmt.day(item.startedAt)} · ${Fmt.time(item.startedAt + p.positionMs)}", style = MaterialTheme.typography.labelLarge)
                    Text("${Fmt.duration(p.positionMs).let { if (p.positionMs < 1000) "0s" else it }} of ${Fmt.duration(p.durationMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { g.playback.skip(-10_000) }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds") }
                FilledIconButton(onClick = { g.playback.toggle() }) { Icon(if (p.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (p.playing) "Pause" else "Play") }
                IconButton(onClick = { g.playback.skip(10_000) }) { Icon(Icons.Rounded.Forward10, "Forward 10 seconds") }
                TextButton(onClick = {
                    val speeds = listOf(1f, 1.25f, 1.5f, 2f, 3f)
                    g.playback.setSpeed(speeds[(speeds.indexOf(p.speed) + 1) % speeds.size])
                }) { Text("${p.speed.toString().removeSuffix(".0")}×") }
                IconButton(onClick = { g.playback.stop() }) { Icon(Icons.Rounded.Close, "Stop") }
            }
            if (p.durationMs > 0) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(value = dragging ?: (p.positionMs.toFloat() / p.durationMs), onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { g.playback.seek((it * p.durationMs).toLong()) }; dragging = null }, modifier = Modifier.height(24.dp))
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
