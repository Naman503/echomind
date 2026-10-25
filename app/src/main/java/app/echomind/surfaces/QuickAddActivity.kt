package app.echomind.surfaces

import android.os.Bundle
import android.service.quicksettings.TileService
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.echomind.App
import app.echomind.brain.QuickAdd
import app.echomind.brain.Recurrence
import app.echomind.ui.Fmt
import app.echomind.ui.components.Pill
import app.echomind.ui.theme.EchoTheme
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch

/**
 * A small floating box for capturing a task, reminder or note from anywhere
 * (Quick Settings tile, launcher shortcut, widget) without opening the app.
 * Understands "Call mom tomorrow 6pm #family !1 every week".
 */
class QuickAddActivity : ComponentActivity() {
    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = App.graph
        setContent {
            EchoTheme(theme = g.settings.value.theme) {
                var text by remember { mutableStateOf(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()) }
                val parsed = remember(text) { if (text.isBlank()) null else QuickAdd.parse(text) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                fun addTask() {
                    val t = text.trim()
                    if (t.isEmpty()) return
                    lifecycleScope.launch { g.tasks.addQuick(t, via = "quick_add"); finish() }
                }
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable { finish() }.imePadding(),
                    contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
                ) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(Sp.l).navigationBarsPadding().clickable(enabled = false) {}) {
                    Column(Modifier.padding(Sp.xl), verticalArrangement = Arrangement.spacedBy(Sp.m)) {
                        Text("Quick add", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus),
                            placeholder = { Text("Remind me to call mom tomorrow 6pm") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { addTask() }),
                        )
                        parsed?.let { p ->
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                p.due?.let { Pill((if (p.remind) "🔔 " else "") + Fmt.day(it.at) + if (it.hasTime) " ${Fmt.time(it.at)}" else "") }
                                p.recurrence?.let { Pill(Recurrence.label(it) ?: it) }
                                p.list?.let { Pill("# $it") }
                                if (p.priority < 4) Pill("P${p.priority}")
                                p.labels.forEach { Pill("@$it") }
                            }
                        }
                        Text("Tip: #list  !1–!4 priority  @label  “every Monday”", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Row {
                            OutlinedButton(enabled = text.isNotBlank(), onClick = {
                                val t = text.trim()
                                lifecycleScope.launch { g.notes.createByUser(t.lineSequence().first().take(60), t); finish() }
                            }) { Text("Save as note") }
                            Spacer(Modifier.weight(1f))
                            Button(enabled = text.isNotBlank(), onClick = { addTask() }) { Text(if (parsed?.remind == true) "Add reminder" else "Add task") }
                        }
                    }
                }
                }
            }
        }
    }
}

/** Quick Settings tile that opens the quick-add box. */
class QuickAddTile : TileService() {
    override fun onClick() {
        app.echomind.telemetry.Usage.count("tile_tap", mapOf("tile" to "quick_add"))
        val pi = PendingIntent.getActivity(this, 1, Intent(this, QuickAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        startActivityAndCollapse(pi)
    }
}

