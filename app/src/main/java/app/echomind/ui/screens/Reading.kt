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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.models.ModelCatalog
import app.echomind.models.ModelSpec
import app.echomind.tts.VoiceCatalog
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.SwitchRow
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch

/** Voices, reading speed and the assistant's proactive side. */
@Composable
fun ReadingScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val models by g.models.states.collectAsStateWithLifecycle()
    var test by remember { mutableStateOf<String?>(null) }
    fun update(change: (app.echomind.core.Settings) -> app.echomind.core.Settings) = g.scope.launch { g.settings.update(change) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Voice & assistant", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            item {
                Text("EchoMind can read your brief, tasks, notes, summaries and answers aloud — on this phone, with open-source voices.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionHeader("Voices")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(s.readEngine == "open", { update { it.copy(readEngine = "open") } }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Open voices") }
                    SegmentedButton(s.readEngine == "system", { update { it.copy(readEngine = "system") } }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Android voices") }
                }
                Text(if (s.readEngine == "open") "Kokoro (most natural) or Piper (lightest), run by EchoMind. Downloaded once." else "Your phone's built-in text-to-speech. No download; less natural.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ModelCatalog.all.filter { it.kind == ModelSpec.Kind.VOICE }.forEach { m ->
                item(key = m.id) {
                    val st = models[m.id]
                    EchoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.label, style = MaterialTheme.typography.titleSmall)
                                Text("${Fmt.bytes(m.bytes)} · ${m.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            when {
                                st?.installed == true -> TextButton(onClick = { g.models.delete(m.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                                st?.downloading == true || st?.extracting == true -> TextButton(onClick = { g.models.cancel(m.id) }) { Text("Cancel") }
                                else -> Button(onClick = { g.models.download(m.id) }) { Text("Get") }
                            }
                        }
                        if (st?.downloading == true || st?.extracting == true) {
                            LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = Sp.s))
                            Text(if (st.extracting) "Unpacking…" else downloadLine(st, m.bytes), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            for ((lang, label) in listOf("en" to "English voice", "hi" to "Hindi voice")) {
                item(key = "pick$lang") {
                    SectionHeader(label)
                    val voices = VoiceCatalog.all.filter { it.lang == lang }
                    val current = if (lang == "hi") s.voiceHindi else s.voiceEnglish
                    voices.forEach { v ->
                        val installed = g.models.isInstalled(v.pack)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(current == v.id, { update { if (lang == "hi") it.copy(voiceHindi = v.id) else it.copy(voiceEnglish = v.id) } }, enabled = installed)
                            Text(v.label + if (!installed) "  (download above)" else "", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                                color = if (installed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                            if (installed) TextButton(onClick = { g.speaker.preview(v.id) }) { Text("Play") }
                        }
                    }
                }
            }
            item {
                SectionHeader("Speed")
                var v by remember { mutableFloatStateOf(s.readSpeed) }
                Text("Default ${"%.2f".format(v).trimEnd('0').trimEnd('.')}×", style = MaterialTheme.typography.bodyMedium)
                Slider(v, { v = (it * 20).toInt() / 20f }, valueRange = 0.5f..6f, onValueChangeFinished = { g.speaker.setSpeed(v) })
                Text("Change it any time from the player. Above 1.5× speech is time-stretched without raising the pitch.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Sp.s))
                OutlinedButton(onClick = {
                    test = "Measuring…"
                    scope.launch {
                        val results = listOf(s.voiceEnglish, s.voiceHindi).mapNotNull { id -> VoiceCatalog.get(id)?.let { voice -> g.speaker.measure(id)?.let { voice.label to it } } }
                        test = if (results.isEmpty()) "Download a voice first."
                        else results.joinToString("\n") { (name, x) ->
                            val smoothUpTo = x * 1.5 // the model does up to 1.5×, the stretch is free
                            "$name: speaks ${"%.1f".format(x)}× faster than real time — smooth up to about ${"%.1f".format(smoothUpTo.coerceAtMost(6.0))}× listening" +
                                if (smoothUpTo < s.readSpeed) " (below your ${s.readSpeed}× — try a Piper voice for fast listening)" else ""
                        }
                    }
                }) { Text("Test voice speed on this phone") }
                test?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            item {
                SectionHeader("Assistant")
                SwitchRow("Read answers aloud", "Ask's answers are spoken automatically (voice questions are always answered aloud)", s.autoReadAnswers) { x -> update { it.copy(autoReadAnswers = x) } }
                SwitchRow("Nudges", "The assistant tells you about hanging questions, things due soon and what's left today", s.nudges) { x -> update { it.copy(nudges = x) } }
                if (s.nudges) {
                    var n by remember { mutableFloatStateOf(s.nudgesPerDay.toFloat()) }
                    Text("At most ${n.toInt()} a day (urgent ones excepted), 90 minutes apart, never at night or during calls", style = MaterialTheme.typography.bodyMedium)
                    Slider(n, { n = it }, valueRange = 1f..10f, steps = 8, onValueChangeFinished = { update { it.copy(nudgesPerDay = n.toInt()) } })
                    SwitchRow("Speak nudges in my earphones", "Only when headphones are connected, never on the speaker", s.speakNudges) { x -> update { it.copy(speakNudges = x) } }
                }
            }
        }
    }
}
