package app.echomind.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.KeyValue
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.rememberResumeTick
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** For the one developer using this: what's running, the phone, every AI call, data, logs, and every job on demand. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeveloperScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val capture by g.capture.state.collectAsStateWithLifecycle()
    val brain by g.brain.state.collectAsStateWithLifecycle()
    val tr by g.transcriber.status.collectAsStateWithLifecycle()
    val gov by g.governor.state.collectAsStateWithLifecycle()
    val running by g.ai.running.collectAsStateWithLifecycle()
    val calls by g.ai.calls.collectAsStateWithLifecycle()
    val pending by remember { g.db.audio().pendingCount() }.collectAsStateWithLifecycle(0)
    val failed by remember { g.db.audio().failed() }.collectAsStateWithLifecycle(emptyList())
    val ready by remember { g.db.conversations().readyCount() }.collectAsStateWithLifecycle(0)
    var job by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    val tick = rememberResumeTick()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(3_000); now = System.currentTimeMillis() } }
    val counts by produceState(emptyMap<String, Int>(), tick, job) {
        value = withContext(Dispatchers.IO) {
            val d = g.db.openHelper.readableDatabase
            listOf("utterances", "conversations", "facts", "entities", "relations", "notes", "tasks", "events", "sounds", "voiceprints", "checkins", "vectors", "audio", "egress")
                .associateWith { t -> runCatching { d.query("SELECT COUNT(*) FROM $t").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } }.getOrDefault(-1) }
        }
    }
    val storage by produceState(emptyList<Pair<String, Long>>(), tick, job) {
        value = withContext(Dispatchers.IO) {
            fun size(f: File?): Long = f?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
            listOf("Models" to size(g.models.root), "Kept recordings" to size(g.recordings.dir), "Songs" to size(g.sounds.songsDir),
                "Waiting audio" to size(g.capture.windowsDir), "Database" to size(ctx.getDatabasePath("echomind.db").parentFile))
        }
    }
    val exportDb = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching { g.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close() }
            ctx.contentResolver.openOutputStream(uri)?.use { out -> ctx.getDatabasePath("echomind.db").inputStream().use { it.copyTo(out) } }
        }
    }
    fun run(name: String, block: suspend () -> Any?) {
        if (job != null) return
        job = name
        scope.launch { runCatching { block() }; job = null }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Developer", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s), state = app.echomind.ui.components.rememberKeptListState()) {
            item {
                SectionHeader("Pipeline")
                EchoCard {
                    KeyValue("Listening", "${capture.mode.name.lowercase()} · ${capture.device}")
                    KeyValue("Brain", brain.phase.name.lowercase() + brain.detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
                    brain.waitingReason?.let { KeyValue("Waiting for", it) }
                    brain.aiBlocked?.let { KeyValue("AI blocked", it) }
                    if (tr.active) KeyValue("Transcribing", "${tr.engine} · part ${tr.piece}/${tr.pieces} · ${"%.1f".format(tr.speed)}× real time")
                    tr.lastError?.let { KeyValue("Last speech error", it) }
                    running?.let { KeyValue("AI now", "${it.purpose} · ${it.provider} · ${(now - it.since) / 1000}s") }
                    KeyValue("Queue", "$pending recordings · $ready conversations to read · ${failed.size} failed")
                    KeyValue("Last run", if (brain.lastRunAt > 0) Fmt.ago(brain.lastRunAt) else "—")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    AssistChip(onClick = { g.capture.processNow(); g.brain.processNow() }, label = { Text("Process now") })
                    AssistChip(onClick = { scope.launch { g.db.audio().retryFailed(); g.brain.processNow() } }, label = { Text("Retry failed audio") })
                    AssistChip(onClick = { nav.go(app.echomind.ui.ProcessingRoute) }, label = { Text("Queue details") })
                }
            }
            item {
                SectionHeader("Phone")
                val p = remember(now) { g.governor.read() }
                val mem = remember(now) { ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) } }
                val free = remember(tick) { StatFs(ctx.filesDir.absolutePath).availableBytes }
                EchoCard {
                    KeyValue("Battery", "${"%.1f".format(p.tempC)}°C · ${p.percent}%${if (p.charging) " · charging" else ""}")
                    KeyValue("Thermal", "status ${p.thermal} · headroom ${p.headroom?.let { "%.2f".format(it) } ?: "—"}")
                    KeyValue("Work allowed", if (gov.allowed) "yes · up to ${g.settings.value.utilization}% of the time" else "no · ${gov.reason}")
                    KeyValue("Memory", "${Fmt.bytes(mem.availMem)} free of ${Fmt.bytes(mem.totalMem)}${if (mem.lowMemory) " · LOW" else ""}")
                    KeyValue("Storage", "${Fmt.bytes(free)} free")
                    KeyValue("Device", "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SOC_MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    storage.forEach { (k, v) -> KeyValue(k, Fmt.bytes(v)) }
                }
            }
            item {
                SectionHeader("Run a job now")
                if (job != null) Text("Running: $job…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    AssistChip(onClick = { run("Journal") { g.journal.write(LocalDate.now(), force = true) } }, label = { Text("Journal today") })
                    AssistChip(onClick = { run("Memory review") { g.journal.nightly(LocalDate.now()) } }, label = { Text("Nightly pass") })
                    AssistChip(onClick = { run("Day plan") { g.plans.make("day") } }, label = { Text("Day plan") })
                    AssistChip(onClick = { run("Week plan") { g.plans.make("week") } }, label = { Text("Week plan") })
                    AssistChip(onClick = { run("Month plan") { g.plans.make("month") } }, label = { Text("Month plan") })
                    AssistChip(onClick = { run("Organise") { g.organizer.tidyNew() } }, label = { Text("File notes") })
                    AssistChip(onClick = { run("People pages") { g.people.refreshStories(5) } }, label = { Text("People pages") })
                    AssistChip(onClick = { run("Quick question") { g.db.memory().putKv(app.echomind.data.Kv("quickq:last", "0", 0)); g.checkIns.quick() } }, label = { Text("Ask a quick question") })
                    AssistChip(onClick = { run("Nudges") { g.nudges.check() } }, label = { Text("Check nudges") })
                    AssistChip(onClick = { run("Songs") { g.sounds.identifyPending() } }, label = { Text("Name songs") })
                    AssistChip(onClick = { run("Recordings sweep") { g.recordings.sweep() } }, label = { Text("Sweep recordings") })
                    AssistChip(onClick = { run("Call import") { g.brain.importCalls() } }, label = { Text("Import calls") })
                    AssistChip(onClick = { run("Calendar") { g.agenda.checkUpcoming() } }, label = { Text("Event heads-ups") })
                    AssistChip(onClick = { g.checkIns.schedule(); g.reminders.scheduleDaily(g.settings.value.morningBriefAt, g.settings.value.eveningRecapAt) }, label = { Text("Re-arm alarms") })
                }
            }
            item { SectionHeader("AI calls · last ${calls.size}") }
            if (calls.isEmpty()) item { Text("No AI calls since the app started.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(calls.take(40)) { c ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(Fmt.time(c.at), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp), color = MaterialTheme.colorScheme.outline)
                    Column(Modifier.weight(1f)) {
                        Text(c.purpose, style = MaterialTheme.typography.bodySmall)
                        Text("${c.provider} · think ${c.think.name.lowercase()} · ${"%.1f".format(c.ms / 1000.0)}s · ${c.inChars / 1000}k chars in${c.error?.let { " · $it" } ?: ""}",
                            style = MaterialTheme.typography.labelSmall, color = if (c.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                SectionHeader("Data")
                EchoCard { counts.forEach { (k, v) -> KeyValue(k, "$v") } }
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    OutlinedButton(onClick = { exportDb.launch("echomind-${LocalDate.now()}.db") }) { Text("Export database") }
                    OutlinedButton(onClick = { nav.go(app.echomind.ui.LedgerRoute) }) { Text("Data ledger") }
                }
            }
            item {
                SectionHeader("Log", action = "Refresh", onAction = {
                    scope.launch(Dispatchers.IO) {
                        log = runCatching {
                            Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "300", "--pid=${android.os.Process.myPid()}")).inputStream.bufferedReader().readLines()
                                .filter { "Echo" in it || " E " in it || " W " in it }.takeLast(120).reversed()
                        }.getOrDefault(listOf("Couldn't read the log."))
                    }
                })
                if (log.isEmpty()) Text("Tap Refresh to read this app's recent log lines (warnings, errors and EchoMind's own).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(log) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp, color = if (" E " in it) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
