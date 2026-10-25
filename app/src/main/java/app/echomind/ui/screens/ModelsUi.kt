package app.echomind.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.core.Feature
import app.echomind.models.ModelCatalog
import app.echomind.models.ModelManager
import app.echomind.models.ModelSpec
import app.echomind.tts.VoiceCatalog
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.Pill
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What this phone can comfortably run. */
private data class Device(val name: String, val soc: String, val ramBytes: Long, val freeBytes: Long) {
    val ramGb get() = ramBytes / 1_000_000_000.0
}

/** Whether a model suits this phone: good, slow (works but heavy), or no. */
private enum class Fit { GOOD, SLOW, NO }

private fun fit(m: ModelSpec, d: Device): Pair<Fit, String> {
    if (m.bytes * 1.15 > d.freeBytes) return Fit.NO to "Not enough free space (${Fmt.bytes(m.bytes)} needed)"
    return when (m.kind) {
        // The model is memory-mapped: up to about a third of RAM runs smoothly next to other apps.
        ModelSpec.Kind.LLM, ModelSpec.Kind.SPEECH -> when {
            m.bytes < d.ramBytes * 0.36 -> Fit.GOOD to "Runs well on your phone"
            m.bytes < d.ramBytes * 0.55 -> Fit.SLOW to "Runs, but slowly and warmly with ${"%.0f".format(d.ramGb)} GB RAM"
            else -> Fit.NO to "Too big for ${"%.0f".format(d.ramGb)} GB RAM"
        }
        else -> Fit.GOOD to "Light — runs well on any phone"
    }
}

/** Which models are part of the setup that's actually in use. */
private fun inUse(m: ModelSpec, s: app.echomind.core.Settings): Boolean = when (m.kind) {
    ModelSpec.Kind.LLM -> s.localModel == m.id && Feature.entries.any { it.kind == Feature.Kind.LLM && s.provider(it) == "local" }
    ModelSpec.Kind.EMBEDDING -> s.semanticSearch
    ModelSpec.Kind.SPEECH -> s.sherpaModel == m.id && (s.provider(Feature.TRANSCRIBE) == "sherpa" || s.provider(Feature.CALLS) == "sherpa")
    ModelSpec.Kind.VAD -> true
    ModelSpec.Kind.SPEAKER -> s.labelSpeakers
    ModelSpec.Kind.SOUND -> s.songs || s.ambient
    ModelSpec.Kind.VOICE -> listOf(s.voiceEnglish, s.voiceHindi).any { VoiceCatalog.get(it)?.pack == m.id }
}

private val purpose = mapOf(
    ModelSpec.Kind.LLM to "On-device AI: notes, memory, Ask, journal, plans",
    ModelSpec.Kind.EMBEDDING to "Search by meaning in Ask",
    ModelSpec.Kind.SPEECH to "Offline speech-to-text (instead of Google's)",
    ModelSpec.Kind.VAD to "Finds speech, so silence is never processed",
    ModelSpec.Kind.SPEAKER to "Tells your voice from others; names people by voice",
    ModelSpec.Kind.SOUND to "Songs and surroundings",
    ModelSpec.Kind.VOICE to "Reading aloud",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val states by g.models.states.collectAsStateWithLifecycle()
    val used by produceState(0L, states) { value = withContext(Dispatchers.IO) { g.models.usedBytes() } }
    val device = remember(states) {
        val mem = ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        Device("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE,
            mem.totalMem, StatFs(g.models.root.absolutePath).availableBytes)
    }
    var filter by rememberSaveable { mutableStateOf("all") }
    fun installed(id: String) = states[id]?.installed == true
    // The setup EchoMind needs on this phone, in order of importance.
    val llmPick = ModelCatalog.all.filter { it.kind == ModelSpec.Kind.LLM }.let { l -> l.firstOrNull { installed(it.id) } ?: l.firstOrNull { it.recommended && fit(it, device).first == Fit.GOOD } ?: l.first() }
    val voicePack = VoiceCatalog.get(s.voiceEnglish)?.pack?.takeIf { it.isNotEmpty() } ?: "supertonic-3"
    val essentials = listOfNotNull(
        llmPick.id to "AI on the phone",
        "silero-vad" to "Speech detection",
        "voice-id" to "Your voice",
        "embeddinggemma-2" to "Search by meaning",
        "sound-tagger" to "Songs & sounds",
        voicePack to "Reading voice",
    ).mapNotNull { (id, why) -> ModelCatalog.get(id)?.let { it to why } }
    val ready = essentials.count { installed(it.first.id) }
    val listed = ModelCatalog.all.filter { m ->
        val keepVoice = m.kind != ModelSpec.Kind.VOICE || installed(m.id) || m.id in setOf("supertonic-3", "kokoro-multi", voicePack)
        keepVoice && when (filter) { "installed" -> installed(m.id); "recommended" -> m.recommended || essentials.any { it.first.id == m.id }; else -> true }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("On-device models", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s), state = app.echomind.ui.components.rememberKeptListState()) {
            item {
                EchoCard(color = LocalSemantic.current.onDevice.copy(alpha = 0.08f)) {
                    Text("Your phone", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    Text("${device.soc} · ${"%.0f".format(device.ramGb)} GB RAM · ${Fmt.bytes(device.freeBytes)} free · Android ${Build.VERSION.RELEASE}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Models take ${Fmt.bytes(used)}. Everything here runs offline once downloaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionHeader("Recommended setup · $ready of ${essentials.size} ready")
                EchoCard {
                    essentials.forEach { (m, why) ->
                        val st = states[m.id]
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(if (st?.installed == true) "✓" else "○", color = if (st?.installed == true) LocalSemantic.current.positive else MaterialTheme.colorScheme.outline, modifier = Modifier.width(22.dp))
                            Column(Modifier.weight(1f)) {
                                Text(why, style = MaterialTheme.typography.bodyLarge)
                                Text("${m.label} · ${Fmt.bytes(m.bytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            when {
                                st?.installed == true -> {}
                                st?.downloading == true || st?.extracting == true -> Text(if (st.extracting) "Unpacking" else "${(st.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                                else -> TextButton(onClick = { g.models.download(m.id) }) { Text("Get") }
                            }
                        }
                    }
                    if (ready < essentials.size) OutlinedButton(onClick = { essentials.filter { !installed(it.first.id) }.forEach { g.models.download(it.first.id) } }, modifier = Modifier.padding(top = Sp.s)) {
                        Text("Get all missing · ${Fmt.bytes(essentials.filter { !installed(it.first.id) }.sumOf { it.first.bytes })}")
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    items(listOf("all" to "All", "installed" to "Downloaded", "recommended" to "Recommended")) { (k, l) -> FilterChip(filter == k, { filter = k }, label = { Text(l) }) }
                }
            }
            for (kind in ModelSpec.Kind.entries) {
                val group = listed.filter { it.kind == kind }
                if (group.isEmpty()) continue
                item(key = "h$kind") { SectionHeader(purpose[kind] ?: kind.name) }
                items(group, key = { it.id }) { m -> ModelCard(m, states[m.id] ?: ModelManager.State(), device, inUse(m, s)) }
                if (kind == ModelSpec.Kind.VOICE) item(key = "allvoices") { TextButton(onClick = { nav.go(app.echomind.ui.VoicesRoute()) }) { Text("All ${VoiceCatalog.open.size} open voices, with samples →") } }
            }
            item { AiEngine() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(m: ModelSpec, st: ModelManager.State, device: Device, using: Boolean) {
    val g = App.graph
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val (f, fitText) = fit(m, device)
    val sem = LocalSemantic.current
    EchoCard(padding = PaddingValues(Sp.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.label, style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    when {
                        st.installed -> Pill("Downloaded", sem.positive.copy(alpha = 0.15f), sem.positive)
                        st.downloading || st.extracting -> Pill(if (st.extracting) "Unpacking" else "Downloading", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        else -> Pill("Not downloaded", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (st.installed && using) Pill("In use", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    if (m.recommended) Pill("Recommended")
                    Pill(Fmt.bytes(m.bytes), MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(m.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fitText, style = MaterialTheme.typography.labelMedium, color = when (f) { Fit.GOOD -> sem.positive; Fit.SLOW -> sem.warning; Fit.NO -> MaterialTheme.colorScheme.error })
            }
            when {
                st.installed -> TextButton(onClick = { g.models.delete(m.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                st.downloading || st.extracting -> TextButton(onClick = { g.models.cancel(m.id) }) { Text("Cancel") }
                f == Fit.NO -> {}
                else -> Button(onClick = { g.models.download(m.id) }) { Text("Get") }
            }
        }
        if (st.downloading || st.extracting) {
            Spacer(Modifier.height(Sp.s))
            LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
            Text(if (st.extracting) "Unpacking…" else downloadLine(st, m.bytes), style = MaterialTheme.typography.labelSmall)
        }
        st.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        if (st.installed && m.kind == ModelSpec.Kind.LLM) Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(s.localModel == m.id, { g.scope.launch { g.settings.update { it.copy(localModel = m.id) } } })
            Text("Use for on-device AI", style = MaterialTheme.typography.bodyMedium)
        }
        if (st.installed && m.kind == ModelSpec.Kind.SPEECH) Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(s.sherpaModel == m.id, { g.scope.launch { g.settings.update { it.copy(sherpaModel = m.id) } } })
            Text("Use for the offline speech engine", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AiEngine() {
    val g = App.graph
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var bench by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    Column {
        SectionHeader("On-device AI engine")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("cpu" to "CPU", "gpu" to "GPU").forEachIndexed { i, (k, l) ->
                SegmentedButton(s.localBackend == k || (k == "cpu" && s.localBackend == "auto"), { g.scope.launch { g.settings.update { it.copy(localBackend = k) } } }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
            }
        }
        Text("CPU is the safe default. GPU can be faster on some phones; if notes fail, switch back.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(enabled = !running && g.localLlm.installedModel() != null, onClick = {
            running = true; bench = "Loading the model…"
            scope.launch {
                bench = runCatching { g.localLlm.benchmark() }.fold(
                    { b -> "${b.backend}: loaded in ${"%.1f".format(b.loadMs / 1000.0)} s · first word after ${"%.1f".format(b.firstTokenMs / 1000.0)} s · ${"%.1f".format(b.chunksPerSec)} words/s" },
                    { e -> "Test failed: ${e.message?.take(160)}" + if (s.localBackend == "gpu") " — GPU may not be supported here; switch to CPU." else "" },
                )
                running = false
            }
        }) { Text(if (running) "Testing…" else "Test AI speed on this phone") }
        bench?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
