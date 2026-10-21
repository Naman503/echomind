package app.echomind.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.models.ModelCatalog
import app.echomind.tts.SystemVoices
import app.echomind.tts.VoiceCatalog
import app.echomind.tts.VoiceSpec
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.VoicesRoute
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.Pill
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.SwitchRow
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch

private fun x(v: Float) = "%.2f".format(v).trimEnd('0').trimEnd('.') + "×"

/** Voices, reading controls, earphones vs speaker, and the assistant's proactive side. */
@Composable
fun ReadingScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    var test by remember { mutableStateOf<String?>(null) }
    fun update(change: (app.echomind.core.Settings) -> app.echomind.core.Settings) = g.scope.launch { g.settings.update(change) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Voice & reading", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            item {
                Text("EchoMind reads your brief, tasks, notes, summaries and answers aloud. Open-source voices run on this phone; nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionHeader("Your voices")
            }
            for ((lang, label) in listOf("en" to "English", "hi" to "Hindi")) item(key = "cur$lang") {
                val v = VoiceCatalog.get(if (lang == "hi") s.voiceHindi else s.voiceEnglish)
                val ready = v != null && (v.engine == "system" || g.models.isInstalled(v.pack))
                EchoCard(onClick = { nav.go(VoicesRoute(lang)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("$label voice", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(v?.let { "${it.label} · ${VoiceCatalog.engineName(it.engine)}" } ?: "Not chosen", style = MaterialTheme.typography.titleMedium)
                            Text(if (ready) "Tap to choose another of ${VoiceCatalog.all.count { it.reads(lang) }} voices" else "Not downloaded yet — tap to get it or pick another",
                                style = MaterialTheme.typography.bodySmall, color = if (ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        }
                        if (ready) IconButton(onClick = { g.speaker.preview(v!!.id) }) { Icon(Icons.Rounded.PlayArrow, "Play a sample") }
                    }
                }
            }
            item {
                SwitchRow("Use the phone's voices instead", "Android's text-to-speech for everything, even when open voices are downloaded", s.readEngine == "system") { on ->
                    update { it.copy(readEngine = if (on) "system" else "open") }
                }
            }
            item {
                SectionHeader("How it reads")
                var v by remember { mutableFloatStateOf(s.readSpeed) }
                Text("Speed ${x(v)}", style = MaterialTheme.typography.bodyMedium)
                Slider(v, { v = (it * 20).toInt() / 20f }, valueRange = 0.5f..6f, onValueChangeFinished = { g.speaker.setSpeed(v) })
                Text("Up to 1.5× the voice itself speaks faster; above that, speech is time-stretched without raising the pitch, so 3–6× stays clear.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var pause by remember { mutableFloatStateOf(s.readPauseMs.toFloat()) }
                Text("Pause between sentences: ${pause.toInt()} ms", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Sp.s))
                Slider(pause, { pause = it }, valueRange = 0f..800f, steps = 15, onValueChangeFinished = { update { it.copy(readPauseMs = pause.toInt()) } })
                var boost by remember { mutableFloatStateOf(s.readBoostDb.toFloat()) }
                Text(if (boost < 1) "Volume boost: off" else "Volume boost: +${boost.toInt()} dB", style = MaterialTheme.typography.bodyMedium)
                Slider(boost, { boost = it }, valueRange = 0f..12f, steps = 11, onValueChangeFinished = { update { it.copy(readBoostDb = boost.toInt()) } })
                var pitch by remember { mutableFloatStateOf(s.readPitch) }
                Text("Pitch of phone voices: ${x(pitch).removeSuffix("×")}", style = MaterialTheme.typography.bodyMedium)
                Slider(pitch, { pitch = (it * 20).toInt() / 20f }, valueRange = 0.5f..2f, onValueChangeFinished = { update { it.copy(readPitch = pitch) } })
                Text("Music and videos while reading", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Sp.s))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("auto" to "Auto", "duck" to "Lower", "pause" to "Pause").forEachIndexed { i, (k, l) ->
                        SegmentedButton(s.readFocus == k, { update { it.copy(readFocus = k) } }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                    }
                }
                Text(when (s.readFocus) { "duck" -> "Other audio gets quieter while EchoMind speaks."; "pause" -> "Other audio pauses while EchoMind speaks."; else -> "Short announcements lower other audio; longer reads pause it." },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                SectionHeader("Earphones and speaker")
                Text("When no earphones are connected", style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("ask" to "Ask first", "allow" to "Use speaker", "never" to "Don't read").forEachIndexed { i, (k, l) ->
                        SegmentedButton(s.speakerPolicy == k, { update { it.copy(speakerPolicy = k) } }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                    }
                }
                Text(when (s.speakerPolicy) {
                    "allow" -> "Reads on the phone speaker without asking."
                    "never" -> "Only reads in earphones; on the speaker nothing is said (the text still shows)."
                    else -> "A small question pops up first: play on the speaker, or not now."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("With earphones connected, reading always goes to them — also while EchoMind is listening through the earbuds' microphone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            item {
                SectionHeader("What it reads by itself")
                SwitchRow("Read answers aloud", "Ask's answers are spoken automatically (voice questions are always answered aloud)", s.autoReadAnswers) { v -> update { it.copy(autoReadAnswers = v) } }
                SwitchRow("Speak nudges in my earphones", "Only when earphones are connected, never on the speaker", s.speakNudges) { v -> update { it.copy(speakNudges = v) } }
            }
            item {
                SectionHeader("Assistant nudges")
                SwitchRow("Nudges", "The assistant tells you about hanging questions, things due soon and what's left today", s.nudges) { v -> update { it.copy(nudges = v) } }
                if (s.nudges) {
                    var n by remember { mutableFloatStateOf(s.nudgesPerDay.toFloat()) }
                    Text("At most ${n.toInt()} a day (urgent ones excepted), 90 minutes apart, never at night or during calls", style = MaterialTheme.typography.bodyMedium)
                    Slider(n, { n = it }, valueRange = 1f..10f, steps = 8, onValueChangeFinished = { update { it.copy(nudgesPerDay = n.toInt()) } })
                }
            }
            item {
                OutlinedButton(onClick = {
                    test = "Measuring…"
                    scope.launch {
                        val results = listOf(s.voiceEnglish, s.voiceHindi).distinct().mapNotNull { id -> VoiceCatalog.get(id)?.let { voice -> g.speaker.measure(id)?.let { voice.label to it } } }
                        test = if (results.isEmpty()) "Download an open voice first (phone voices are always smooth)."
                        else results.joinToString("\n") { (name, xx) ->
                            val smoothUpTo = xx * 1.5
                            "$name: ${"%.1f".format(xx)}× faster than real time — smooth up to about ${"%.1f".format(smoothUpTo.coerceAtMost(6.0))}×" +
                                if (smoothUpTo < s.readSpeed) " (below your ${x(s.readSpeed)} — a Piper or Kitten voice keeps up better)" else ""
                        }
                    }
                }) { Text("Test voice speed on this phone") }
                test?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/** Every voice EchoMind can use, to try, download and choose. */
@Composable
fun VoicesScreen(lang: String) {
    val g = App.graph
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val models by g.models.states.collectAsStateWithLifecycle()
    var language by rememberSaveable { mutableStateOf(lang) }
    var gender by rememberSaveable { mutableStateOf("") }
    var accent by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("all") }
    var onlyReady by rememberSaveable { mutableStateOf(false) }
    var loadingSystem by remember { mutableStateOf(false) }
    var systemCount by remember { mutableStateOf(VoiceCatalog.system.size) }
    LaunchedEffect(Unit) { if (VoiceCatalog.system.isEmpty()) { loadingSystem = true; systemCount = SystemVoices.load(ctx).size; loadingSystem = false } }
    val chosen = if (language == "hi") s.voiceHindi else s.voiceEnglish
    val accents = remember(language, systemCount) { VoiceCatalog.all.filter { it.reads(language) && it.engine != "system" }.map { it.accent }.distinct() }
    val list = remember(language, gender, accent, source, onlyReady, systemCount, models) {
        VoiceCatalog.all.filter { v ->
            v.reads(language) && (gender.isEmpty() || v.gender == gender) && (accent.isEmpty() || v.accent == accent) &&
                (source == "all" || (source == "open") == (v.engine != "system")) &&
                (!onlyReady || v.engine == "system" || g.models.isInstalled(v.pack))
        }.sortedWith(compareBy({ it.engine == "system" }, { -it.stars }, { it.engine }, { it.label }))
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Voices", onBack = { nav.back() })
        LazyRow(contentPadding = PaddingValues(horizontal = Sp.gutter), horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
            items(listOf("en" to "English", "hi" to "Hindi")) { (k, l) -> FilterChip(language == k, { language = k; accent = "" }, label = { Text(l) }) }
            items(listOf("" to "Anyone", "f" to "Female", "m" to "Male")) { (k, l) -> FilterChip(gender == k, { gender = k }, label = { Text(l) }) }
            item { FilterChip(onlyReady, { onlyReady = !onlyReady }, label = { Text("Ready to use") }) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Sp.gutter, vertical = Sp.xs), horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
            items(listOf("all" to "All", "open" to "Open-source", "system" to "Phone voices")) { (k, l) -> FilterChip(source == k, { source = k }, label = { Text(l) }) }
            items(accents) { a -> FilterChip(accent == a, { accent = if (accent == a) "" else a }, label = { Text(a) }) }
        }
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            item {
                Text("${list.size} voices. Stars are how natural they sound. Open voices are downloaded once per pack (one pack can hold many voices) and run offline.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (gender.isEmpty() && accent.isEmpty() && source == "all" && !onlyReady) item {
                SectionHeader("Best for you")
                val picks = if (language == "hi") listOf(
                    "supertonic-f1" to "Most natural, reads Hindi and English in one voice — great for Hinglish notes",
                    "kokoro-hf-alpha" to "Warm Hindi voice; same pack as the best English voices",
                    "piper-pratham" to "Small (21 MB) and very fast — good above 3× speed",
                ) else listOf(
                    "supertonic-f1" to "Most natural, and also reads Hindi — one voice for everything",
                    "kokoro-heart" to "The most natural English voice of all",
                    "piper-l2-svbi" to "Indian English, small and fast — good above 3× speed",
                )
                picks.mapNotNull { (id, why) -> VoiceCatalog.get(id)?.let { it to why } }.forEach { (v, why) ->
                    Text("${v.label} — $why", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
                Text("Your phone runs all of these smoothly; use “Test voice speed” in Voice & reading to check fast listening.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            items(list, key = { it.id }) { v -> VoiceRow(v, chosen == v.id, language) }
            item {
                SectionHeader("More voices from your phone")
                if (loadingSystem) Row(verticalAlignment = Alignment.CenterVertically) { LoadingIndicator(); Spacer(Modifier.width(Sp.s)); Text("Looking for the phone's voices…") }
                Text("Google's Speech Services include natural offline voices for English (India, US, UK) and Hindi — download more in Android's text-to-speech settings. " +
                    "Any text-to-speech app you install appears here too. Microsoft's natural voices only exist online (in Edge); apps that bring them to Android send your text to Microsoft and are marked Online.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.padding(top = Sp.s)) {
                    OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Phone voice settings") }
                    TextButton(onClick = { loadingSystem = true; scope.launch { systemCount = SystemVoices.load(ctx).size; loadingSystem = false } }) { Text("Refresh") }
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(v: VoiceSpec, selected: Boolean, language: String) {
    val g = App.graph
    val models by g.models.states.collectAsStateWithLifecycle()
    val pack = ModelCatalog.get(v.pack)
    val st = models[v.pack]
    val ready = v.engine == "system" || st?.installed == true
    EchoCard(color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow, padding = PaddingValues(Sp.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(v.label, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(Sp.s))
                    Text("★".repeat(v.stars) + "☆".repeat(5 - v.stars), style = MaterialTheme.typography.labelSmall, color = LocalSemantic.current.warning)
                }
                Text(listOfNotNull(VoiceCatalog.engineName(v.engine), v.accent, when (v.gender) { "f" -> "female"; "m" -> "male"; else -> null }, if (v.langs.size > 1) "English + Hindi" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (v.note.isNotBlank()) Text(v.note, style = MaterialTheme.typography.labelSmall, color = if (v.online) LocalSemantic.current.cloud else MaterialTheme.colorScheme.outline)
                Text(when {
                    v.engine == "system" -> "On your phone already"
                    st?.installed == true -> "✓ Downloaded"
                    else -> "Pack not downloaded · ${pack?.let { Fmt.bytes(it.bytes) } ?: ""} · ${VoiceCatalog.open.count { it.pack == v.pack }} voice${if (VoiceCatalog.open.count { it.pack == v.pack } == 1) "" else "s"} in it"
                }, style = MaterialTheme.typography.labelSmall, color = if (v.engine == "system" || st?.installed == true) LocalSemantic.current.positive else MaterialTheme.colorScheme.outline)
            }
            if (ready) IconButton(onClick = { g.speaker.preview(v.id) }) { Icon(Icons.Rounded.PlayArrow, "Play a sample") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            when {
                selected -> Pill("Your ${if (language == "hi") "Hindi" else "English"} voice")
                ready -> Button(onClick = { g.scope.launch { g.settings.update { if (language == "hi") it.copy(voiceHindi = v.id) else it.copy(voiceEnglish = v.id) } } }) {
                    Text("Use for ${if (language == "hi") "Hindi" else "English"}")
                }
                st?.downloading == true || st?.extracting == true -> TextButton(onClick = { g.models.cancel(v.pack) }) { Text("Cancel") }
                pack != null -> OutlinedButton(onClick = { g.models.download(v.pack) }) { Text("Get · ${Fmt.bytes(pack.bytes)}") }
            }
            // A voice that reads both languages can be set for the other one too.
            if (ready && v.langs.size > 1) {
                val other = if (language == "hi") "en" else "hi"
                val otherChosen = if (other == "hi") g.settings.value.voiceHindi == v.id else g.settings.value.voiceEnglish == v.id
                if (!otherChosen) TextButton(onClick = { g.scope.launch { g.settings.update { if (other == "hi") it.copy(voiceHindi = v.id) else it.copy(voiceEnglish = v.id) } } }) {
                    Text("Also for ${if (other == "hi") "Hindi" else "English"}")
                }
            }
            // A pack still read by your English or Hindi voice stays.
            val inUse = listOf(g.settings.value.voiceEnglish, g.settings.value.voiceHindi).any { VoiceCatalog.get(it)?.pack == v.pack }
            if (st?.installed == true && !inUse) TextButton(onClick = { g.models.delete(v.pack) }) { Text("Delete pack", color = MaterialTheme.colorScheme.error) }
        }
        if (st?.downloading == true || st?.extracting == true) {
            LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = Sp.s))
            Text(if (st.extracting) "Unpacking…" else downloadLine(st, pack?.bytes ?: 0), style = MaterialTheme.typography.labelSmall)
        }
    }
}
