package app.echomind.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.echomind.App
import app.echomind.brain.VoiceCheck
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.MetricChip
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.Slice
import app.echomind.ui.components.StackedBar
import app.echomind.ui.components.ChartLegend
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch

/**
 * How well EchoMind tells your voice apart, and the lines it's least sure about:
 * answer "me" or "not me" and each answer makes the next guess better.
 */
@Composable
fun VoiceCheckScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    var refresh by remember { mutableIntStateOf(0) }
    var refitting by remember { mutableStateOf(false) }
    val report by produceState<VoiceCheck.Report?>(null, refresh) { value = runCatching { g.voiceCheck.report() }.getOrNull() }
    val answered = remember { mutableStateListOf<Long>() }
    val play by g.playback.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (g.playback.state.value.clipId != null) g.playback.stop() } }
    androidx.compose.material3.Scaffold(snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Your voice", onBack = { nav.back() })
        val r = report
        if (r == null) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                androidx.compose.material3.CircularProgressIndicator()
                Text("Measuring the last 7 days…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Sp.s))
            }
            return@Column
        }
        LazyColumn(state = app.echomind.ui.components.rememberKeptListState(), contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(Sp.m)) {
            if (!g.voice.enrolled()) item {
                EmptyState("Your voice isn't set up", "Record a few lines of your voice in Settings → Your voice, so EchoMind can tell what you said from what others said.")
            }
            item {
                val st = r.stats
                val sem = LocalSemantic.current
                val total = (st.me + st.others + st.unknown).coerceAtLeast(1)
                EchoCard {
                    Text("Last 7 days", style = MaterialTheme.typography.titleSmall)
                    val sl = listOf(Slice(st.me.toFloat(), MaterialTheme.colorScheme.primary, "You"), Slice(st.others.toFloat(), MaterialTheme.colorScheme.tertiary, "Others"), Slice(st.unknown.toFloat(), MaterialTheme.colorScheme.outline, "Unsure"))
                    StackedBar(sl, Modifier.padding(vertical = Sp.s), height = 12.dp)
                    ChartLegend(sl) { "${(it.value * 100 / total).toInt()}%" }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.padding(top = Sp.s)) {
                    val sep = st.separation
                    MetricChip(Icons.Rounded.GraphicEq, sep?.let { "%.2f".format(it) } ?: "–", "separation", Modifier.weight(1f),
                        tint = when { sep == null -> MaterialTheme.colorScheme.outline; sep >= 0.35f -> sem.positive; sep >= 0.22f -> sem.warning; else -> MaterialTheme.colorScheme.error },
                        hint = when { sep == null -> "needs more talk"; sep >= 0.35f -> "clear"; sep >= 0.22f -> "fair"; else -> "weak" })
                    MetricChip(Icons.Rounded.Tune, "%.2f".format(st.threshold), "threshold", Modifier.weight(1f), hint = if (st.auto) "fitted to you" else "from set-up")
                    MetricChip(Icons.Rounded.School, "${st.samples}", "voice samples", Modifier.weight(1f), tint = MaterialTheme.colorScheme.tertiary, hint = "${st.corrections} \"not me\"")
                }
                Text("Separation is how far apart your voice and other voices score. The threshold is re-fitted every night from your recordings; each answer below adds a sample.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Sp.s))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("Was this you?", Modifier.weight(1f))
                    OutlinedButton(enabled = !refitting, onClick = { refitting = true; scope.launch { g.voiceCheck.calibrate(); refitting = false; refresh++ } }) { Text(if (refitting) "Re-fitting…" else "Re-fit now") }
                }
            }
            val todo = r.uncertain.filter { it.u.id !in answered }
            if (todo.isNotEmpty() && todo.none { it.playable }) item {
                EchoCard(color = MaterialTheme.colorScheme.tertiaryContainer, onClick = { nav.go(app.echomind.ui.RecordingsRoute) }) {
                    Text("Recordings of these lines weren't kept, so they can't be played. Keep recordings for a few days (Settings → Recordings) to listen back here.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            if (todo.isEmpty()) item {
                Text("Nothing uncertain right now. Lines it isn't sure about will appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(todo, key = { it.u.id }) { s ->
                val mine = play.clipId == s.u.id
                EchoCard(color = if (mine) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${Fmt.dayTime(s.u.startAt)} · labelled ${if (s.u.speaker == "me") "you" else s.u.speaker ?: "unknown"}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        if (s.playable) {
                            androidx.compose.material3.FilledTonalIconButton(onClick = {
                                if (mine) g.playback.stop()
                                else scope.launch {
                                    when (g.playback.playLine(s.u.id, s.u.startAt, s.u.endAt)) {
                                        app.echomind.capture.Playback.Clip.PLAYING -> {}
                                        app.echomind.capture.Playback.Clip.NOT_KEPT -> snackbar.showSnackbar("The recording for this line wasn't kept.")
                                        app.echomind.capture.Playback.Clip.FAILED -> snackbar.showSnackbar("Couldn't play this recording.")
                                    }
                                }
                            }) { Icon(if (mine) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (mine) "Stop" else "Play this line") }
                        } else Text("No recording", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (mine) {
                        val len = ((play.clipToMs ?: play.durationMs) - play.clipFromMs).coerceAtLeast(1)
                        androidx.compose.material3.LinearProgressIndicator(progress = { ((play.positionMs - play.clipFromMs).toFloat() / len).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        Text(if (play.playing) "Playing…" else "Starting…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(s.u.text, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.fillMaxWidth().padding(top = Sp.s)) {
                        Button(onClick = { if (mine) g.playback.stop(); answered.add(s.u.id); scope.launch { g.voiceCheck.answer(s.u, true) } }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.RecordVoiceOver, null); Text("  Me")
                        }
                        OutlinedButton(onClick = { if (mine) g.playback.stop(); answered.add(s.u.id); scope.launch { g.voiceCheck.answer(s.u, false) } }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Person, null); Text("  Not me")
                        }
                    }
                }
            }
            if (answered.isNotEmpty()) item {
                Text("${answered.size} answered — thanks. Each one is already teaching the model.", style = MaterialTheme.typography.bodySmall, color = LocalSemantic.current.positive)
            }
        }
    }
    }
}
