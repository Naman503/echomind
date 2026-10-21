package app.echomind.surfaces

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import app.echomind.App
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.SwitchRow
import app.echomind.ui.theme.EchoTheme
import kotlinx.coroutines.launch

/** Choices for one widget: its look and what it shows. Opens when the widget is added, and from "Reconfigure". */
class WidgetConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Closing without saving keeps the widget with its defaults.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val kind = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.className?.substringAfterLast('.')?.removeSuffix("WidgetReceiver").orEmpty()
        val c = this
        setContent {
            EchoTheme {
                Surface(Modifier.fillMaxSize()) {
                    var style by remember { mutableStateOf(WidgetPrefs.get(c, id, "style", "auto")) }
                    var opacity by remember { mutableFloatStateOf(WidgetPrefs.int(c, id, "opacity", 70).toFloat()) }
                    LazyColumn(Modifier.windowInsetsPadding(WindowInsets.systemBars), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Text("Widget: " + when (kind) { "Listen" -> "Status & controls"; "Echo" -> "Today's tasks"; "Mic" -> "Mic button"; "Quick" -> "Quick actions"; else -> kind },
                                style = MaterialTheme.typography.headlineSmall)
                            SectionHeader("Look")
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf("auto" to "Wallpaper", "light" to "Light", "dark" to "Dark", "glass" to "See-through").forEachIndexed { i, (k, l) ->
                                    SegmentedButton(style == k, { style = k; WidgetPrefs.set(c, id, "style", k) }, SegmentedButtonDefaults.itemShape(i, 4)) { Text(l, maxLines = 1) }
                                }
                            }
                            if (style == "glass") {
                                Text("Background ${opacity.toInt()}% dark", style = MaterialTheme.typography.bodyMedium)
                                Slider(opacity, { opacity = it }, valueRange = 0f..100f, onValueChangeFinished = { WidgetPrefs.set(c, id, "opacity", opacity.toInt()) })
                            }
                        }
                        item {
                            when (kind) {
                                "Echo" -> Choice(c, id, "tasks", "Which tasks", listOf("today" to "Today + overdue", "overdue" to "Overdue only", "all" to "All open"), "today")
                                "Notes" -> {
                                    Choice(c, id, "notes", "Which notes", listOf("recent" to "Recent", "pinned" to "Pinned", "folder" to "One folder"), "recent")
                                    val folders = remember { mutableStateListOf<String>() }
                                    androidx.compose.runtime.LaunchedEffect(Unit) { folders.addAll(App.graph.db.notes().all().mapNotNull { it.folder }.distinct().sorted()) }
                                    var folder by remember { mutableStateOf(WidgetPrefs.get(c, id, "folder", "")) }
                                    if (folders.isNotEmpty()) {
                                        SectionHeader("Folder")
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { folders.forEach { f -> FilterChip(folder == f, { folder = f; WidgetPrefs.set(c, id, "folder", f); WidgetPrefs.set(c, id, "notes", "folder") }, label = { Text(f) }) } }
                                    }
                                    var preview by remember { mutableStateOf(WidgetPrefs.bool(c, id, "preview", true)) }
                                    SwitchRow("Show the first line", null, preview) { v -> preview = v; WidgetPrefs.set(c, id, "preview", v) }
                                }
                                "Day" -> {
                                    SectionHeader("Show")
                                    val show = remember { mutableStateListOf(*WidgetPrefs.get(c, id, "show", "focus,next,tasks,talk,health").split(',').toTypedArray()) }
                                    listOf("focus" to "Today's focus", "next" to "Next event", "tasks" to "Tasks left", "talk" to "Talk time", "productivity" to "Productivity", "health" to "Phone temperature").forEach { (k, l) ->
                                        SwitchRow(l, null, k in show) { v -> if (v) show.add(k) else show.remove(k); WidgetPrefs.set(c, id, "show", show.joinToString(",")) }
                                    }
                                }
                                "Listen" -> {
                                    var health by remember { mutableStateOf(WidgetPrefs.bool(c, id, "health", true)) }
                                    SwitchRow("Show temperature and battery", null, health) { v -> health = v; WidgetPrefs.set(c, id, "health", v) }
                                }
                                "Quick" -> {
                                    SectionHeader("Tiles (in order, up to 6)")
                                    val tiles = remember { mutableStateListOf(*WidgetPrefs.get(c, id, "tiles", QuickWidget.DEFAULT).split(',').toTypedArray()) }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        QuickWidget.ALL.forEach { t ->
                                            FilterChip(t.key in tiles, {
                                                if (t.key in tiles) tiles.remove(t.key) else if (tiles.size < 6) tiles.add(t.key)
                                                WidgetPrefs.set(c, id, "tiles", tiles.joinToString(","))
                                            }, label = { Text(t.label) })
                                        }
                                    }
                                    var labels by remember { mutableStateOf(WidgetPrefs.bool(c, id, "labels", true)) }
                                    SwitchRow("Show labels", null, labels) { v -> labels = v; WidgetPrefs.set(c, id, "labels", v) }
                                }
                                else -> Text("This widget has no other options.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            Button(onClick = {
                                lifecycleScope.launch {
                                    runCatching { GlanceAppWidgetManager(c).getGlanceIdBy(id) }.getOrNull()?.let { WidgetUpdater.updateAll(c) }
                                    finish()
                                }
                            }, modifier = Modifier.padding(top = 16.dp)) { Text("Done") }
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Choice(c: android.content.Context, id: Int, key: String, title: String, options: List<Pair<String, String>>, def: String) {
        var v by remember { mutableStateOf(WidgetPrefs.get(c, id, key, def)) }
        SectionHeader(title)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (k, l) -> SegmentedButton(v == k, { v = k; WidgetPrefs.set(c, id, key, k) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(l, maxLines = 1) } }
        }
    }
}
