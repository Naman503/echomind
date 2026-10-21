package app.echomind.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.Briefing
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch

private fun speedLabel(x: Float) = if (x % 1f == 0f) "${x.toInt()}×" else "${"%.2f".format(x).trimEnd('0').trimEnd('.')}×"

/** Floating player shown above the tabs while something is being read aloud. */
@Composable
fun MiniPlayer() {
    val sp = App.graph.speaker
    val s by sp.state.collectAsStateWithLifecycle()
    var speedSheet by remember { mutableStateOf(false) }
    AnimatedVisibility(s.active, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 3.dp, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(horizontal = Sp.s, vertical = 4.dp)) {
            Column {
                if (s.items.size > 1) LinearProgressIndicator(progress = { (s.index + 1f) / s.items.size }, modifier = Modifier.fillMaxWidth().height(3.dp))
                Row(Modifier.padding(start = Sp.m, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.sentence.ifBlank { "Preparing the voice…" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { sp.previous() }) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                    FilledIconButton(onClick = { sp.toggle() }) { Icon(if (s.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (s.paused) "Play" else "Pause") }
                    IconButton(onClick = { sp.next() }) { Icon(Icons.Rounded.SkipNext, "Next") }
                    TextButton(onClick = { speedSheet = true }) { Text(speedLabel(s.speed), fontWeight = FontWeight.Bold) }
                    IconButton(onClick = { sp.stop() }) { Icon(Icons.Rounded.Close, "Stop") }
                }
            }
        }
    }
    if (speedSheet) SpeedSheet(s.speed, onPick = { sp.setSpeed(it) }) { speedSheet = false }
}

/** Speed: presets for quick taps, and a fine slider up to 6×. */
@Composable
fun SpeedSheet(current: Float, onPick: (Float) -> Unit, onDone: () -> Unit) {
    var v by remember { mutableFloatStateOf(current) }
    ModalBottomSheet(onDismissRequest = onDone) {
        Column(Modifier.padding(horizontal = Sp.xl).padding(bottom = Sp.xxl), verticalArrangement = Arrangement.spacedBy(Sp.m)) {
            Text("Reading speed · ${speedLabel(v)}", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                items(listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f)) { x ->
                    FilterChip(v == x, { v = x; onPick(x) }, label = { Text(speedLabel(x)) })
                }
            }
            Slider(v, { v = (it * 20).toInt() / 20f }, valueRange = 0.5f..6f, onValueChangeFinished = { onPick(v) })
            Text("Up to 1.5× the voice itself speaks faster; above that the audio is time-stretched without raising the pitch, so 3–6× stays clear.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A speaker button that reads a script built on tap. */
@Composable
fun ListenButton(contentDescription: String = "Read aloud", build: suspend () -> Briefing.Script) {
    val scope = rememberCoroutineScope()
    IconButton(onClick = { scope.launch { val s = build(); App.graph.speaker.speak(s.title, s.parts) } }) {
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription)
    }
}

