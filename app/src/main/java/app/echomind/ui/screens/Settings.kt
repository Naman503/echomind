package app.echomind.ui.screens

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.capture.AudioInputs
import app.echomind.core.Feature
import app.echomind.core.Privacy
import app.echomind.core.ProviderInfo
import app.echomind.core.Providers
import app.echomind.core.SessionMode
import app.echomind.core.Settings as AppSettings
import app.echomind.models.ModelCatalog
import app.echomind.models.ModelSpec
import app.echomind.ui.CallsRoute
import app.echomind.ui.DataRoute
import app.echomind.ui.Fmt
import app.echomind.ui.LedgerRoute
import app.echomind.ui.LocalNav
import app.echomind.ui.ModelsRoute
import app.echomind.ui.NotificationsRoute
import app.echomind.ui.PowerRoute
import app.echomind.ui.ProcessingRoute
import app.echomind.ui.ProvidersRoute
import app.echomind.ui.RecordingRoute
import app.echomind.ui.SessionsRoute
import app.echomind.ui.VoiceRoute
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.KeyValue
import app.echomind.ui.components.PrivacyChip
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.SettingRow
import app.echomind.ui.components.SwitchRow
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
private fun SettingsPage(title: String, content: LazyListScope.() -> Unit) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar(title, onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), content = content)
    }
}

private fun update(change: (AppSettings) -> AppSettings) {
    val g = App.graph
    g.scope.launch { g.settings.update(change); g.brain.poke() }
}

@Composable
fun SettingsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    SettingsPage("Settings") {
        item {
            EchoCard(onClick = { nav.go(ProvidersRoute) }, color = if (s.privacy == Privacy.LOCAL_ONLY) LocalSemantic.current.onDevice.copy(alpha = 0.1f) else LocalSemantic.current.cloudContainer) {
                Text(if (s.privacy == Privacy.LOCAL_ONLY) "Local only" else "Cloud allowed", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (s.privacy == Privacy.LOCAL_ONLY) "Nothing personal ever leaves this phone." else "Features you set to a cloud service send text or audio to it. Everything else stays here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Sp.s))
        }
        item { SettingRow("Privacy & AI providers", "Who does transcription, notes, memory, Ask", Icons.Rounded.Shield, onClick = { nav.go(ProvidersRoute) }) }
        item { SettingRow("On-device models", "Download speech, AI and search models", Icons.Rounded.Download, onClick = { nav.go(ModelsRoute) }) }
        item { SettingRow("Listening", "Microphone, languages, quiet hours, audio", Icons.Rounded.Mic, onClick = { nav.go(RecordingRoute) }) }
        item { SettingRow("Voice & assistant", "Read aloud, voices, speed, nudges", Icons.Rounded.RecordVoiceOver, onClick = { nav.go(app.echomind.ui.ReadingRoute) }) }
        item { SettingRow("Your voice", "So notes know what you said", Icons.Rounded.RecordVoiceOver, onClick = { nav.go(VoiceRoute) }) }
        item { SettingRow("Phone calls", if (s.importCalls) "Importing call recordings" else "Off", Icons.Rounded.Call, onClick = { nav.go(CallsRoute) }) }
        item { SettingRow("AI sessions", "When the AI reads your day: ${s.session.mode.name.lowercase()}", Icons.Rounded.AutoAwesome, onClick = { nav.go(SessionsRoute) }) }
        item { SettingRow("Battery, heat & background", "How hard the phone may work · vivo setup", Icons.Rounded.BatteryChargingFull, onClick = { nav.go(PowerRoute) }) }
        item { SettingRow("Notifications", "Morning brief, evening recap, suggestions", Icons.Rounded.Notifications, onClick = { nav.go(NotificationsRoute) }) }
        item { SettingRow("Processing", "Queue, progress and what's running", Icons.Rounded.Timer, onClick = { nav.go(ProcessingRoute) }) }
        item { SettingRow("Data ledger", "Everything that left the phone", Icons.Rounded.Shield, onClick = { nav.go(LedgerRoute) }) }
        item { SettingRow("Your data", "Storage, export, delete", Icons.Rounded.Storage, onClick = { nav.go(DataRoute) }) }
        item {
            SectionHeader("Look")
            SettingRow("Theme", null, Icons.Rounded.Palette, trailing = {
                SingleChoiceSegmentedButtonRow {
                    listOf("system" to "Auto", "light" to "Light", "dark" to "Dark").forEachIndexed { i, (k, l) ->
                        SegmentedButton(s.theme == k, { update { it.copy(theme = k) } }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                    }
                }
            })
            SwitchRow("Wallpaper colours", "Use your wallpaper's colours (privacy colours never change)", s.dynamicColor) { v -> update { it.copy(dynamicColor = v) } }
        }
        item {
            SectionHeader("You")
            var name by remember { mutableStateOf(s.userName) }
            OutlinedTextField(name, { name = it; update { st -> st.copy(userName = it) } }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The privacy switch, a provider per feature with who can see the data, and the keys. */
@Composable
fun ProvidersScreen() {
    val g = App.graph
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val keys by g.secrets.present.collectAsStateWithLifecycle()
    var consent by remember { mutableStateOf<Pair<Feature, ProviderInfo>?>(null) }
    SettingsPage("Privacy & providers") {
        item {
            SectionHeader("Privacy mode")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(s.privacy == Privacy.LOCAL_ONLY, { update { it.copy(privacy = Privacy.LOCAL_ONLY) } }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Local only") }
                SegmentedButton(s.privacy == Privacy.CLOUD_ALLOWED, { update { it.copy(privacy = Privacy.CLOUD_ALLOWED) } }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Cloud allowed") }
            }
            Text(
                if (s.privacy == Privacy.LOCAL_ONLY) "Everything runs on this phone. Cloud choices below are ignored until you allow cloud."
                else "Each feature uses what you choose below. Whenever data is sent, an amber banner shows and the Data ledger records it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Sp.s),
            )
        }
        for (f in Feature.entries) {
            item(key = f.id) {
                SectionHeader(f.label)
                val current = s.providers[f.id] ?: f.defaultProvider
                Providers.forKind(f.kind).forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        RadioButton(selected = current == p.id, enabled = p.local || s.privacy == Privacy.CLOUD_ALLOWED, onClick = {
                            if (!p.local && f.id !in s.cloudConsent) consent = f to p
                            else update { it.copy(providers = it.providers + (f.id to p.id)) }
                        })
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.label, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.width(Sp.s))
                                PrivacyChip(p.local, if (p.local) "Private" else p.who)
                            }
                            Text(p.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (p.keyName != null && p.keyName !in keys && current == p.id) Text("Needs an API key ↓", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        item {
            SectionHeader("API keys")
            Text("Stored encrypted with a key that never leaves this phone's secure hardware. Never logged, never backed up.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val keyed = Providers.all.mapNotNull { it.keyName }.distinct()
        items(keyed, key = { "k$it" }) { k -> KeyRow(k, k in keys) }
        item {
            SectionHeader("Cloud models")
            var gm by remember { mutableStateOf(s.geminiModel) }
            OutlinedTextField(gm, { gm = it; update { st -> st.copy(geminiModel = it.trim()) } }, label = { Text("Gemini model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            var am by remember { mutableStateOf(s.anthropicModel) }
            OutlinedTextField(am, { am = it; update { st -> st.copy(anthropicModel = it.trim()) } }, label = { Text("Claude model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            var ob by remember { mutableStateOf(s.openaiBaseUrl) }
            OutlinedTextField(ob, { ob = it; update { st -> st.copy(openaiBaseUrl = it.trim()) } }, label = { Text("OpenAI-compatible base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("e.g. https://api.openai.com/v1 · https://api.groq.com/openai/v1 · https://openrouter.ai/api/v1 · http://<your-pc>:11434/v1 (Ollama)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var om by remember { mutableStateOf(s.openaiModel) }
            OutlinedTextField(om, { om = it; update { st -> st.copy(openaiModel = it.trim()) } }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            SectionHeader("Notes language")
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                listOf("English", "Hindi", "Hinglish").forEach { l -> FilterChip(s.notesLanguage == l, { update { it.copy(notesLanguage = l) } }, label = { Text(l) }) }
            }
            Text("People can speak any mix; notes, tasks and the journal are written in this language.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    consent?.let { (f, p) ->
        AlertDialog(
            onDismissRequest = { consent = null },
            title = { Text("Send ${f.label.lowercase()} to ${p.who}?") },
            text = { Text("${f.sends} will be sent to ${p.label} each time this feature runs. An amber banner shows while it happens and every request is listed in the Data ledger. You can switch back to on-phone processing at any time.") },
            confirmButton = { TextButton(onClick = { update { it.copy(providers = it.providers + (f.id to p.id), cloudConsent = it.cloudConsent + f.id) }; consent = null }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { consent = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeyRow(name: String, present: Boolean) {
    val g = App.graph
    var value by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        OutlinedTextField(
            value, { value = it }, modifier = Modifier.weight(1f), singleLine = true,
            label = { Text(name.replaceFirstChar { it.uppercase() }) },
            placeholder = { Text(if (present) "Saved — paste to replace" else "Paste key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.width(Sp.s))
        if (value.isNotBlank()) TextButton(onClick = { g.secrets.set(name, value); value = ""; g.brain.poke() }) { Text("Save") }
        else if (present) TextButton(onClick = { g.secrets.set(name, null) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun ModelsScreen() {
    val g = App.graph
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val states by g.models.states.collectAsStateWithLifecycle()
    val used by produceState(0L, states) { value = withContext(Dispatchers.IO) { g.models.usedBytes() } }
    SettingsPage("On-device models") {
        item {
            Text("Downloaded once, then everything runs on this phone with no internet. Storage used: ${Fmt.bytes(used)}.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Sp.s))
        }
        val groups = listOf(
            ModelSpec.Kind.LLM to "AI for notes, memory, Ask and journal",
            ModelSpec.Kind.EMBEDDING to "Search by meaning",
            ModelSpec.Kind.SPEECH to "Speech-to-text (alternative to Google's)",
            ModelSpec.Kind.VAD to "Speech detection",
            ModelSpec.Kind.SPEAKER to "Voice ID",
        )
        for ((kind, title) in groups) {
            item(key = kind.name) { SectionHeader(title) }
            items(ModelCatalog.all.filter { it.kind == kind }, key = { it.id }) { m ->
                val st = states[m.id] ?: app.echomind.models.ModelManager.State()
                EchoCard(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.label, style = MaterialTheme.typography.titleSmall)
                                if (m.recommended) { Spacer(Modifier.width(6.dp)); app.echomind.ui.components.Pill("Recommended") }
                            }
                            Text("${Fmt.bytes(m.bytes)} · ${m.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when {
                            st.installed -> TextButton(onClick = { g.models.delete(m.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                            st.downloading || st.extracting -> TextButton(onClick = { g.models.cancel(m.id) }) { Text("Cancel") }
                            else -> Button(onClick = { g.models.download(m.id) }) { Text("Get") }
                        }
                    }
                    if (st.downloading || st.extracting) {
                        Spacer(Modifier.height(Sp.s))
                        LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                        Text(if (st.extracting) "Unpacking…" else downloadLine(st, m.bytes), style = MaterialTheme.typography.labelSmall)
                    }
                    st.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                    if (st.installed && kind == ModelSpec.Kind.LLM) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(s.localModel == m.id, { update { it.copy(localModel = m.id) } })
                            Text("Use for on-device AI", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (st.installed && kind == ModelSpec.Kind.SPEECH) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(s.sherpaModel == m.id, { update { it.copy(sherpaModel = m.id) } })
                            Text("Use for the open-model speech engine", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item {
            SectionHeader("On-device AI engine")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("cpu" to "CPU", "gpu" to "GPU").forEachIndexed { i, (k, l) ->
                    SegmentedButton(s.localBackend == k || (k == "cpu" && s.localBackend == "auto"), { update { it.copy(localBackend = k) } }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                }
            }
            Text("CPU is the safe default. GPU can be faster on some phones; if notes fail, switch back.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var bench by remember { mutableStateOf<String?>(null) }
            var running by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            OutlinedButton(enabled = !running && g.localLlm.installedModel() != null, onClick = {
                running = true; bench = "Loading the model…"
                scope.launch {
                    bench = runCatching { g.localLlm.benchmark() }.fold(
                        { b -> "${b.backend}: loaded in ${"%.1f".format(b.loadMs / 1000.0)} s · first word after ${"%.1f".format(b.firstTokenMs / 1000.0)} s · ${"%.1f".format(b.chunksPerSec)} words/s" },
                        { e -> "Test failed: ${e.message?.take(160)}" + if (s.localBackend == "gpu") " — GPU may not be supported here; switch to CPU." else "" },
                    )
                    running = false
                }
            }) { Text(if (running) "Testing…" else "Test speed") }
            bench?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordingScreen() {
    val g = App.graph
    val ctx = LocalContext.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val inputs = remember { AudioInputs.list(ctx) }
    var support by remember { mutableStateOf<app.echomind.stt.AndroidSpeech.Support?>(null) }
    LaunchedEffect(s.speechLanguages) { support = g.androidSpeech.support(s.speechLanguages) }
    SettingsPage("Listening") {
        item {
            SectionHeader("Microphone")
            inputs.forEach { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.inputDeviceId == i.id, { update { it.copy(inputDeviceId = i.id) } })
                    Column {
                        Text(i.label, style = MaterialTheme.typography.bodyLarge)
                        if (i.kind == "bluetooth") Text("Uses the headset's call microphone; music on the buds drops to call quality while listening.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Changes apply the next time listening starts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        item {
            SectionHeader("Languages you speak")
            val all = listOf("en-IN" to "English (India)", "hi-IN" to "Hindi", "en-US" to "English (US)", "mr-IN" to "Marathi", "bn-IN" to "Bengali", "ta-IN" to "Tamil", "te-IN" to "Telugu", "gu-IN" to "Gujarati", "kn-IN" to "Kannada", "pa-IN" to "Punjabi")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                all.forEach { (code, label) ->
                    val on = code in s.speechLanguages
                    FilterChip(on, {
                        update { st -> st.copy(speechLanguages = if (on) (st.speechLanguages - code).ifEmpty { listOf("en-IN") } else st.speechLanguages + code) }
                    }, label = { Text(label) })
                }
            }
            support?.let { sup ->
                if (!sup.available) Text("Google's on-device recogniser isn't available on this phone — use an open model or a cloud provider.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else {
                    val offlineless = s.speechLanguages.filter { l -> sup.supported.isNotEmpty() && (sup.installed + sup.pending + sup.supported).none { it.equals(l, true) } }
                    val missing = s.speechLanguages.filter { l -> sup.installed.none { it.equals(l, true) } } - offlineless.toSet()
                    if (offlineless.isNotEmpty()) Text("Google has no offline pack for ${offlineless.joinToString { java.util.Locale.forLanguageTag(it).displayLanguage }}. For it, download Omnilingual (On-device models) and choose “Whisper / Parakeet on-device”, or pick Sarvam in Privacy & providers.",
                        style = MaterialTheme.typography.bodySmall, color = LocalSemantic.current.warning)
                    Text(if (missing.isEmpty()) "All offline speech packs installed." else "Not installed yet: ${missing.joinToString()}${if (sup.pending.isNotEmpty()) " (downloading)" else ""}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val packStatus by g.speechPacks.status.collectAsStateWithLifecycle()
                    val packFailed by g.speechPacks.failed.collectAsStateWithLifecycle()
                    if (missing.isNotEmpty()) TextButton(onClick = { g.speechPacks.download(missing) }) { Text("Download speech packs") }
                    packStatus.forEach { (lang, st) -> Text("$lang · $st", style = MaterialTheme.typography.bodySmall) }
                    if (packFailed || missing.isNotEmpty()) TextButton(onClick = { g.speechPacks.openGoogleSettings() }) { Text("Open Google's speech languages") }
                    LaunchedEffect(packStatus) { support = g.androidSpeech.support(s.speechLanguages) }
                }
            }
        }
        item {
            SectionHeader("Recognition quality")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("accurate" to "Most accurate", "balanced" to "Balanced", "fast" to "Fastest").forEachIndexed { i, (k, l) ->
                    SegmentedButton(s.speechSpeed == k, { update { it.copy(speechSpeed = k) } }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                }
            }
            Text(when (s.speechSpeed) {
                "accurate" -> "Audio is fed to Google's recogniser at normal speed — closest to voice typing. Takes as long as the speech itself."
                "fast" -> "3× speed: catches up quickly but Google's offline model misses more words."
                else -> "1.5× speed: nearly full accuracy, catches up twice as fast as real time."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Sp.s))
            SwitchRow("Use only my first language", "More accurate when you mostly speak one language (Hindi mode still understands Hinglish). Off = switch automatically.", s.languageMode == "single") { v ->
                update { it.copy(languageMode = if (v) "single" else "switch") }
            }
            SwitchRow("Boost quiet speech", "Gain control for a phone on the table or in a pocket", s.boostQuietSpeech) { v -> update { it.copy(boostQuietSpeech = v) } }
            Text("Still not as good as voice typing? Voice typing uses Google's online model when you have internet. You can choose “Google online (like Gboard)” for speech in Privacy & providers — the audio then goes to Google. Speaking to the phone from further away also lowers accuracy.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionHeader("Recording")
            var win by remember { mutableFloatStateOf(s.windowMinutes.toFloat()) }
            Text("Recording chunk: ${win.toInt()} min (shorter = notes sooner)", style = MaterialTheme.typography.bodyMedium)
            Slider(win, { win = it }, valueRange = 1f..5f, steps = 3, onValueChangeFinished = { update { it.copy(windowMinutes = win.toInt()) } })
            var keep by remember { mutableFloatStateOf(s.keepAudioHours.toFloat()) }
            Text(if (keep.toInt() == 0) "Audio: deleted as soon as it's transcribed" else "Audio: kept ${keep.toInt()} h after transcription", style = MaterialTheme.typography.bodyMedium)
            Slider(keep, { keep = it }, valueRange = 0f..48f, steps = 7, onValueChangeFinished = { update { it.copy(keepAudioHours = keep.toInt()) } })
        }
        item {
            SectionHeader("Quiet hours")
            SwitchRow("Don't listen at night", if (s.quietFrom >= 0) "From ${s.quietFrom / 60}:00 to ${s.quietTo / 60}:00" else "Off", s.quietFrom >= 0) { on ->
                update { it.copy(quietFrom = if (on) 23 * 60 else -1, quietTo = if (on) 7 * 60 else -1) }
            }
            if (s.quietFrom >= 0) {
                var a by remember { mutableFloatStateOf((s.quietFrom / 60).toFloat()) }
                var b by remember { mutableFloatStateOf((s.quietTo / 60).toFloat()) }
                Text("Starts at ${a.toInt()}:00", style = MaterialTheme.typography.bodyMedium)
                Slider(a, { a = it }, valueRange = 0f..23f, steps = 22, onValueChangeFinished = { update { it.copy(quietFrom = a.toInt() * 60) } })
                Text("Ends at ${b.toInt()}:00", style = MaterialTheme.typography.bodyMedium)
                Slider(b, { b = it }, valueRange = 0f..23f, steps = 22, onValueChangeFinished = { update { it.copy(quietTo = b.toInt() * 60) } })
            }
        }
    }
}

@Composable
fun CallsScreen() {
    val g = App.graph
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var last by remember { mutableStateOf<String?>(null) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (it) update { st -> st.copy(importCalls = true, callsSince = if (st.callsSince == 0L) System.currentTimeMillis() else st.callsSince) } }
    SettingsPage("Phone calls") {
        item {
            Text("During a call Android gives the microphone only to the call, so EchoMind can't hear it. Turn on your phone's own call recording, and EchoMind adds each call afterwards — transcribed and turned into notes like any conversation. The original recording is never changed or deleted.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Sp.s))
            SwitchRow("Add my call recordings", if (!granted) "Needs access to audio files" else null, s.importCalls) { on ->
                if (on && !granted) perm.launch(Manifest.permission.READ_MEDIA_AUDIO)
                else update { it.copy(importCalls = on, callsSince = if (on && it.callsSince == 0L) System.currentTimeMillis() else it.callsSince) }
            }
        }
        if (s.importCalls) item {
            SectionHeader("Which calls")
            Text("Importing calls recorded since ${Fmt.dayTime(s.callsSince)}.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                listOf(1 to "Last day", 7 to "Last week", 30 to "Last month").forEach { (d, l) ->
                    OutlinedButton(onClick = { update { it.copy(callsSince = System.currentTimeMillis() - d * 86_400_000L) } }) { Text(l) }
                }
            }
            Text("Older calls take longer to process; they run when the phone is cool or charging.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(Sp.s))
            Button(onClick = { scope.launch { val n = g.brain.importCalls(); last = if (n == 0) "No new call recordings found." else "$n call${if (n == 1) "" else "s"} added to the queue." } }) { Text("Check for new calls now") }
            last?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(Sp.s))
            Text("Calls use the speech engine set for “Call recordings” in Privacy & providers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionHeader("Turn on call recording (vivo)")
            Text("Phone app → ⋮ → Settings → Call recording → Auto-record calls → All calls.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Open the Phone app") }
        }
    }
}

@Composable
fun SessionsScreen() {
    val g = App.graph
    val s by g.settings.flow.collectAsStateWithLifecycle()
    SettingsPage("AI sessions") {
        item {
            Text("Choose when the AI reads what was said. Transcription always runs; this only decides when notes, tasks and memory are made.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Sp.s))
        }
        val modes = listOf(
            SessionMode.LIVE to ("After each conversation" to "Notes a few minutes after a conversation ends. Most up to date."),
            SessionMode.INTERVAL to ("Every so often" to "Batches the day's conversations at a fixed interval."),
            SessionMode.WORDS to ("After enough talking" to "Waits until enough has been said, so each run has more context."),
            SessionMode.CHARGING to ("Only while charging" to "Coolest for the phone; notes arrive when you plug in."),
            SessionMode.MANUAL to ("Only when I ask" to "Nothing runs until you tap Process now."),
        )
        items(modes, key = { it.first.name }) { (mode, text) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                RadioButton(s.session.mode == mode, { update { it.copy(session = it.session.copy(mode = mode)) } })
                Column { Text(text.first, style = MaterialTheme.typography.bodyLarge); Text(text.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item {
            if (s.session.mode == SessionMode.INTERVAL) {
                var v by remember { mutableFloatStateOf(s.session.intervalMinutes.toFloat()) }
                Text("Every ${Fmt.duration(v.toLong() * 60_000)}", style = MaterialTheme.typography.bodyMedium)
                Slider(v, { v = it }, valueRange = 15f..360f, steps = 22, onValueChangeFinished = { update { it.copy(session = it.session.copy(intervalMinutes = v.toInt())) } })
            }
            if (s.session.mode == SessionMode.WORDS) {
                var v by remember { mutableFloatStateOf(s.session.words.toFloat()) }
                Text("After about ${v.toInt()} words", style = MaterialTheme.typography.bodyMedium)
                Slider(v, { v = it }, valueRange = 300f..6000f, steps = 18, onValueChangeFinished = { update { it.copy(session = it.session.copy(words = v.toInt())) } })
            }
            SectionHeader("Conversations")
            var gap by remember { mutableFloatStateOf(s.session.gapMinutes.toFloat()) }
            Text("A conversation ends after ${gap.toInt()} min of silence", style = MaterialTheme.typography.bodyMedium)
            Slider(gap, { gap = it }, valueRange = 1f..15f, steps = 13, onValueChangeFinished = { update { it.copy(session = it.session.copy(gapMinutes = gap.toInt())) } })
            var max by remember { mutableFloatStateOf(s.session.maxConversationMinutes.toFloat()) }
            Text("Split conversations longer than ${max.toInt()} min", style = MaterialTheme.typography.bodyMedium)
            Slider(max, { max = it }, valueRange = 15f..120f, steps = 6, onValueChangeFinished = { update { it.copy(session = it.session.copy(maxConversationMinutes = max.toInt())) } })
            SectionHeader("Tasks")
            SwitchRow("Auto-accept clear reminders", "Your own commitments with a time go straight to your list; everything else waits for review.", s.autoAcceptConfident) { v -> update { it.copy(autoAcceptConfident = v) } }
            SwitchRow("Search by meaning", "Uses the on-device search model when installed", s.semanticSearch) { v -> update { it.copy(semanticSearch = v) } }
            Spacer(Modifier.height(Sp.m))
            Button(onClick = { g.capture.processNow(); g.brain.processNow() }) { Text("Process now") }
        }
    }
}

/** How hard the phone may work, and the vivo / Funtouch settings that keep EchoMind alive. */
@Composable
fun PowerScreen() {
    val g = App.graph
    val ctx = LocalContext.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val gov by g.governor.state.collectAsStateWithLifecycle()
    var tick by remember { mutableStateOf(0) }
    val resumeTick = app.echomind.ui.components.rememberResumeTick()
    LaunchedEffect(resumeTick) { g.governor.refresh() }
    SettingsPage("Battery, heat & background") {
        item {
            SectionHeader("Right now")
            EchoCard {
                KeyValue("Battery", "${gov.power.percent}%${if (gov.power.charging) " · charging" else ""}")
                KeyValue("Battery temperature", "${"%.1f".format(gov.power.tempC)} °C")
                KeyValue("Thermal status", listOf("Normal", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown").getOrElse(gov.power.thermal) { "?" })
                gov.power.headroom?.let { KeyValue("Heat headroom", "${(it * 100).toInt()}% of limit") }
                KeyValue("Work allowed", if (gov.allowed) "Yes" else gov.reason)
            }
        }
        item {
            SectionHeader("Work limits")
            var u by remember { mutableFloatStateOf(s.utilization.toFloat()) }
            Text("Use up to ${u.toInt()}% of the time for transcription and AI (on battery)", style = MaterialTheme.typography.bodyMedium)
            Slider(u, { u = it }, valueRange = 10f..100f, steps = 8, onValueChangeFinished = { update { it.copy(utilization = u.toInt()) } })
            var t by remember { mutableFloatStateOf(s.maxBatteryTempC.toFloat()) }
            Text("Pause work above ${t.toInt()} °C battery temperature", style = MaterialTheme.typography.bodyMedium)
            Slider(t, { t = it }, valueRange = 34f..45f, steps = 10, onValueChangeFinished = { update { it.copy(maxBatteryTempC = t.toInt()) } })
            var b by remember { mutableFloatStateOf(s.minBatteryPct.toFloat()) }
            Text("Only listen (no processing) below ${b.toInt()}% battery", style = MaterialTheme.typography.bodyMedium)
            Slider(b, { b = it }, valueRange = 5f..50f, steps = 8, onValueChangeFinished = { update { it.copy(minBatteryPct = b.toInt()) } })
            SwitchRow("Process only while charging", "Listening still runs all day", s.onlyWhileCharging) { v -> update { it.copy(onlyWhileCharging = v) } }
        }
        item {
            SectionHeader("Keep running in the background")
            @Suppress("UNUSED_VARIABLE") val t2 = tick + resumeTick
            SettingRow("Battery optimisation", if (ignoringBattery(ctx)) "Off for EchoMind ✓" else "On — Android may stop EchoMind", onClick = { requestBattery(ctx) })
            Text("On vivo / iQOO (Funtouch OS) also:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Sp.m))
            Step("1. High background power consumption", "Settings → Battery → Background power consumption management → EchoMind → Allow") {
                openFirst(ctx, listOf(
                    ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity"),
                    ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
                ))
            }
            Step("2. Autostart", "Settings → Apps → Autostart (or i Manager → App manager → Autostart) → EchoMind on") {
                openFirst(ctx, listOf(
                    ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                    ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                ))
            }
            Step("3. Lock in recent apps", "Open recents, pull EchoMind's card down (or ⋮ → Lock) so cleaning doesn't close it.", null)
            Spacer(Modifier.height(Sp.s))
            Button(onClick = { ctx.getSharedPreferences("setup", Context.MODE_PRIVATE).edit().putBoolean("vivoDone", true).apply(); tick++ }) { Text("I've done these") }
        }
    }
}

@Composable
private fun Step(title: String, body: String, open: (() -> Unit)?) {
    Column(Modifier.padding(vertical = Sp.s)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (open != null) TextButton(onClick = open) { Text("Open settings") }
    }
}

private fun openFirst(ctx: Context, components: List<ComponentName>) {
    for (c in components) {
        val i = Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(i); true }.getOrDefault(false)) return
    }
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun NotificationsScreen() {
    val g = App.graph
    val ctx = LocalContext.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    SettingsPage("Notifications") {
        item {
            SwitchRow("Morning brief", "At ${s.morningBriefAt / 60}:${"%02d".format(s.morningBriefAt % 60)} — today's reminders and loose ends", s.notifyMorning) { v -> update { it.copy(notifyMorning = v) } }
            var m by remember { mutableFloatStateOf(s.morningBriefAt / 60f) }
            Slider(m, { m = it }, valueRange = 5f..11f, steps = 5, onValueChangeFinished = {
                update { it.copy(morningBriefAt = m.toInt() * 60) }; g.reminders.scheduleDaily(m.toInt() * 60, s.eveningRecapAt)
            })
            SwitchRow("Evening recap & journal", "At ${s.eveningRecapAt / 60}:${"%02d".format(s.eveningRecapAt % 60)}", s.notifyEvening) { v -> update { it.copy(notifyEvening = v) } }
            var e by remember { mutableFloatStateOf(s.eveningRecapAt / 60f) }
            Slider(e, { e = it }, valueRange = 18f..23f, steps = 4, onValueChangeFinished = {
                update { it.copy(eveningRecapAt = e.toInt() * 60) }; g.reminders.scheduleDaily(s.morningBriefAt, e.toInt() * 60)
            })
            SwitchRow("Suggestions to review", "At most every 2 hours, never at night", s.notifySuggestions) { v -> update { it.copy(notifySuggestions = v) } }
            SettingRow("Sounds and channels", "Fine-tune each kind in Android settings", onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            })
        }
    }
}

@Composable
fun LedgerScreen() {
    val g = App.graph
    val list by remember { g.db.egress().observeRecent() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    SettingsPage("Data ledger") {
        item {
            Text("Every time anything left this phone: when, for what, to whom and how much. Recording audio itself never leaves unless a cloud speech provider is chosen.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Sp.s))
        }
        if (list.isEmpty()) item { EmptyState("Nothing has left this phone", "Everything so far was processed on the device.") }
        items(list, key = { it.id }) { e ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("${e.feature} → ${e.provider}${if (!e.ok) " (failed)" else ""}", style = MaterialTheme.typography.bodyLarge)
                Text("${Fmt.dayTime(e.at)} · ${Fmt.bytes(e.bytes)} · ${e.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (list.isNotEmpty()) item { TextButton(onClick = { scope.launch { g.db.egress().clear() } }) { Text("Clear ledger") } }
    }
}

@Composable
fun DataScreen() {
    val g = App.graph
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines by remember { g.db.utterances().count() }.collectAsStateWithLifecycle(0)
    val vectors by remember { g.db.vectors().count() }.collectAsStateWithLifecycle(0)
    var confirm by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    SettingsPage("Your data") {
        item {
            EchoCard {
                KeyValue("Transcript lines", "$lines")
                KeyValue("Search vectors", "$vectors")
                KeyValue("Database", Fmt.bytes(ctx.getDatabasePath("echomind.db").length()))
                KeyValue("Models", Fmt.bytes(g.models.usedBytes()))
            }
            Spacer(Modifier.height(Sp.m))
            OutlinedButton(enabled = !exporting, onClick = {
                exporting = true
                scope.launch {
                    val file = withContext(Dispatchers.IO) { Export.toJson(ctx) }
                    exporting = false
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export EchoMind"))
                }
            }) { Text(if (exporting) "Preparing…" else "Export everything (JSON)") }
            Spacer(Modifier.height(Sp.l))
            Button(onClick = { confirm = true }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete all my data") }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete everything?") },
        text = { Text("Transcripts, conversations, notes, memory, tasks and journal are permanently deleted. Downloaded models and settings are kept.") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            scope.launch(Dispatchers.IO) {
                g.capture.stop()
                g.db.clearAllTables()
                File(ctx.filesDir, "audio").listFiles()?.forEach { it.delete() }
                File(ctx.filesDir, "calls").listFiles()?.forEach { it.delete() }
            }
        }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

/** Plain JSON export of everything the user owns. */
object Export {
    suspend fun toJson(ctx: Context): File {
        val g = App.graph
        val json = kotlinx.serialization.json.buildJsonObject {
            put("exportedAt", kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()))
            put("conversations", kotlinx.serialization.json.JsonArray(g.db.conversations().inRangeOnce(0, Long.MAX_VALUE).map { c ->
                kotlinx.serialization.json.buildJsonObject {
                    put("startAt", kotlinx.serialization.json.JsonPrimitive(c.startAt)); put("endAt", kotlinx.serialization.json.JsonPrimitive(c.endAt))
                    put("title", kotlinx.serialization.json.JsonPrimitive(c.title)); put("summary", kotlinx.serialization.json.JsonPrimitive(c.summary))
                    put("lines", kotlinx.serialization.json.JsonArray(g.db.utterances().forConversation(c.id).map { u ->
                        kotlinx.serialization.json.buildJsonObject {
                            put("at", kotlinx.serialization.json.JsonPrimitive(u.startAt)); put("speaker", kotlinx.serialization.json.JsonPrimitive(u.speaker)); put("text", kotlinx.serialization.json.JsonPrimitive(u.text))
                        }
                    }))
                }
            }))
            put("notes", kotlinx.serialization.json.JsonArray(g.db.notes().all().map { n -> kotlinx.serialization.json.buildJsonObject { put("title", kotlinx.serialization.json.JsonPrimitive(n.title)); put("body", kotlinx.serialization.json.JsonPrimitive(n.body)) } }))
            put("memory", kotlinx.serialization.json.JsonArray(g.db.memory().activeFacts().map { f -> kotlinx.serialization.json.buildJsonObject { put("subject", kotlinx.serialization.json.JsonPrimitive(f.subject)); put("statement", kotlinx.serialization.json.JsonPrimitive(f.statement)) } }))
            put("tasks", kotlinx.serialization.json.JsonArray(g.db.tasks().live().map { t -> kotlinx.serialization.json.buildJsonObject { put("title", kotlinx.serialization.json.JsonPrimitive(t.title)); put("status", kotlinx.serialization.json.JsonPrimitive(t.status)) } }))
        }
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        return File(dir, "echomind-export.json").apply { writeText(json.toString()) }
    }
}

@Composable
fun ProcessingScreen() {
    val g = App.graph
    val scope = rememberCoroutineScope()
    val brain by g.brain.state.collectAsStateWithLifecycle()
    val t by g.transcriber.status.collectAsStateWithLifecycle()
    val gov by g.governor.state.collectAsStateWithLifecycle()
    val ai by g.ai.running.collectAsStateWithLifecycle()
    val pending by remember { g.db.audio().pendingCount() }.collectAsStateWithLifecycle(0)
    val pendingMs by remember { g.db.audio().pendingMs() }.collectAsStateWithLifecycle(0L)
    val ready by remember { g.db.conversations().readyCount() }.collectAsStateWithLifecycle(0)
    val failed by remember { g.db.audio().failed() }.collectAsStateWithLifecycle(emptyList())
    val s by g.settings.flow.collectAsStateWithLifecycle()
    SettingsPage("Processing") {
        item {
            EchoCard {
                Text(when (brain.phase) {
                    app.echomind.brain.Brain.Phase.TRANSCRIBING -> "Transcribing"
                    app.echomind.brain.Brain.Phase.THINKING -> "Making notes"
                    app.echomind.brain.Brain.Phase.JOURNAL -> "Writing the journal"
                    app.echomind.brain.Brain.Phase.WAITING -> "Waiting"
                    else -> "Idle"
                }, style = MaterialTheme.typography.titleMedium)
                if (brain.detail.isNotBlank()) Text(brain.detail, style = MaterialTheme.typography.bodyMedium)
                ai?.let { Text("${it.purpose} · ${Providers.get(it.provider)?.label ?: it.provider} · ${(System.currentTimeMillis() - it.since) / 1000}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (t.active) Text("Piece ${t.piece} of ${t.pieces} · ${t.engine}${t.contact?.let { " · call with $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!gov.allowed) Text(gov.reason, style = MaterialTheme.typography.bodySmall, color = LocalSemantic.current.warning)
                brain.waitingReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            SectionHeader("Queue")
            KeyValue("Recordings waiting", "$pending (${Fmt.duration(pendingMs)})")
            KeyValue("Estimated time", if (pendingMs == 0L) "—" else "~${Fmt.duration((pendingMs / t.speed.coerceAtLeast(0.2)).toLong())} of work")
            KeyValue("Conversations waiting for notes", "$ready")
            KeyValue("Speech engine", Providers.get(s.provider(Feature.TRANSCRIBE))?.label ?: "")
            KeyValue("Notes AI", Providers.get(s.provider(Feature.NOTES))?.label ?: "")
            KeyValue("AI session mode", s.session.mode.name.lowercase())
            brain.aiBlocked?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(Sp.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                Button(onClick = { g.capture.processNow(); g.brain.processNow() }) { Text("Process now") }
                if (failed.isNotEmpty()) OutlinedButton(onClick = { scope.launch { g.db.audio().retryFailed(); g.brain.poke() } }) { Text("Retry ${failed.size} failed") }
            }
        }
        if (failed.isNotEmpty()) {
            item { SectionHeader("Couldn't transcribe") }
            items(failed, key = { it.id }) { a ->
                Text("${Fmt.dayTime(a.startedAt)} · ${a.source} · ${a.error ?: "error"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Suppress("unused") private suspend fun ensureLoaded() = App.graph.db.audio().pendingCount().first()

/** "42% · 8.1 MB/s · about 3 min left" */
fun downloadLine(st: app.echomind.models.ModelManager.State, bytes: Long): String {
    val pct = (st.progress * 100).toInt()
    if (st.error != null && st.downloading) return "$pct% · ${st.error}"
    if (st.speed <= 0) return "$pct% · connecting…"
    val left = ((1 - st.progress) * bytes / st.speed).toLong() * 1000
    return "$pct% · ${Fmt.bytes(st.speed)}/s · about ${Fmt.duration(left.coerceAtLeast(1_000))} left"
}
