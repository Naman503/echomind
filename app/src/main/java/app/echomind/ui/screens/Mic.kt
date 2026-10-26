package app.echomind.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.capture.MicTest
import app.echomind.capture.Recorder
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.RankBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Microphone mode, room boost, and a test that finds which mode hears the room best. */
@Composable
fun MicSection() {
    val g = App.graph
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    fun set(change: (app.echomind.core.Settings) -> app.echomind.core.Settings) = scope.launch { g.settings.update(change); g.capture.restartMic() }
    var testing by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var results by remember { mutableStateOf<List<MicTest.Result>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val modes = MicTest.modes.filter { it.key != "raw" || MicTest.rawSupported(ctx) }

    Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
        SectionHeader("Microphone")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, m -> SegmentedButton(s.micMode == m.key, { set { it.copy(micMode = m.key) } }, SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(m.label) } }
        }
        Text(MicTest.modes.first { it.key == s.micMode }.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Room boost", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Sp.s))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("off" to "Off", "auto" to "Auto", "strong" to "Strong").forEachIndexed { i, (k, l) ->
                SegmentedButton(s.roomGain == k, { set { it.copy(roomGain = k) } }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
            }
        }
        Text(when (s.roomGain) {
            "off" -> "Recorded as the microphone hears it."
            "strong" -> "Lifts quiet voices up to 30 dB: for a big room or a phone in a pocket. The room's own noise is kept down."
            else -> "Lifts distant and quiet voices up to 18 dB to the level speech recognition expects; loud voices are never clipped."
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        EchoCard(modifier = Modifier.padding(top = Sp.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Mic, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Sp.s))
                Text("Find the best microphone", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            Text("Put the phone where it usually sits. Ask someone to talk from across the room — or talk yourself from a few steps away — for about ${modes.size * 7} seconds. EchoMind records a little in each mode and picks the one that hears voices best.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            val t = testing
            if (t != null) {
                Text("Listening in ${MicTest.label(t)} mode… keep talking", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Sp.s))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            } else {
                Button(onClick = {
                    results = null; failed = false
                    scope.launch {
                        // The test needs the microphone to itself: listening pauses for it and comes back after.
                        val wasListening = Recorder.isRunning()
                        if (wasListening) { g.capture.pause(2); delay(1_500) }
                        val r = runCatching {
                            val list = modes.mapIndexed { i, _ -> i }.let { MicTest.run(ctx) { m, p -> testing = m.key; val idx = modes.indexOfFirst { it.key == m.key }; progress = (idx + p) / modes.size } }
                            list
                        }.getOrNull()
                        testing = null
                        if (wasListening) g.capture.resume()
                        results = r; failed = r.isNullOrEmpty()
                    }
                }, modifier = Modifier.padding(top = Sp.s)) { Text(if (results == null) "Start the test" else "Test again") }
            }
            if (failed) Text("The microphone couldn't be opened for the test. Try again in a moment.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            results?.takeIf { it.isNotEmpty() }?.let { rs ->
                val best = MicTest.best(rs)
                Spacer(Modifier.height(Sp.s))
                if (best == null) {
                    Text("No voices were heard clearly, so no mode can be picked by voice. Try again with someone talking during the whole test.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    // Even without voices, a big difference in how loud each mode records says a lot about distant speech.
                    MicTest.louder(rs, s.micMode)?.let { (r, gap) ->
                        Text("${MicTest.label(r.mode)} mode records the room about ${gap.toInt()} dB louder than ${MicTest.label(s.micMode)} mode on this phone, so distant voices reach it much more strongly.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        OutlinedButton(onClick = { set { it.copy(micMode = r.mode) } }, modifier = Modifier.padding(top = 4.dp)) { Text("Use ${MicTest.label(r.mode)} mode") }
                    }
                }
                val max = rs.maxOf { it.clarity }.coerceAtLeast(1f)
                rs.forEach { r ->
                    RankBar(MicTest.label(r.mode) + if (r == best) "  ✓ best" else "", r.clarity, max, "clarity ${r.clarity.toInt()} dB · voices ${r.loudDb.toInt()} dB",
                        color = if (r == best) LocalSemantic.current.positive else MaterialTheme.colorScheme.primary)
                }
                Text("Clarity: how far voices stand out from the room. Voices: how loud they arrive (closer to 0 is louder).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                if (best != null && best.mode != s.micMode) Button(onClick = { set { it.copy(micMode = best.mode) } }, modifier = Modifier.padding(top = Sp.s)) {
                    Icon(Icons.Rounded.CheckCircle, null); Spacer(Modifier.width(6.dp)); Text("Use ${MicTest.label(best.mode)} mode")
                }
                else if (best != null) Text("You're already using the best mode.", style = MaterialTheme.typography.bodySmall, color = LocalSemantic.current.positive, modifier = Modifier.padding(top = 4.dp))
                // Quiet voices even in the best mode: suggest a stronger boost.
                if (best != null && best.loudDb < -40f && s.roomGain != "strong") OutlinedButton(onClick = { set { it.copy(roomGain = "strong") } }) { Text("Voices are quiet here — use strong room boost") }
            }
        }
    }
}
