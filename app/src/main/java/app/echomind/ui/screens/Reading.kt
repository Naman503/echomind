package app.echomind.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
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
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s), state = app.echomind.ui.components.rememberKeptListState()) {
            item {
                Text("EchoMind reads your brief, tasks, notes, summaries and answers aloud. Open-source voices run on this phone; nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionHeader("Your voices")
            }
            for ((lang, label) in listOf("en" to "English", "hi" to "Hindi")) item(key = "cur$lang") {
                val v = VoiceCatalog.get(if (lang == "hi") s.voiceHindi else s.voiceEnglish)
                val ready = VoiceCatalog.ready(v, g.models)
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
            if (VoiceCatalog.get(s.voiceEnglish)?.engine == "kokoro" || VoiceCatalog.get(s.voiceHindi)?.engine == "kokoro") item(key = "hq") { KokoroHq() }
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
                        // Your voices, then the best voice of every other downloaded family, to compare.
                        val others = VoiceCatalog.open.filter { VoiceCatalog.ready(it, g.models) }.groupBy { it.pack }.values.mapNotNull { vs -> vs.maxByOrNull { it.stars }?.id }
                        val results = (listOf(s.voiceEnglish, s.voiceHindi) + others).distinct().mapNotNull { id -> VoiceCatalog.get(id)?.let { voice -> g.speaker.measure(id)?.let { "${voice.label} (${VoiceCatalog.engineName(voice.engine)})" to it } } }
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

/** A shelf in the voice list; each voice sits on exactly one. */
internal enum class VoiceShelf(val title: String, val blurb: String) {
    NATURAL("Most natural", "Sound like a real person — best for long listening"),
    INDIAN("Indian accent", "English read the way it's spoken here"),
    HINDI("Hindi voices", "Read Devanagari and Hindi words naturally"),
    ACCENTS("British & other accents", "UK, Scottish and more"),
    LIGHT("Fast & light", "Small downloads that keep up at 4–6×"),
    PHONE("Phone voices", "Built into Android — no download, a little robotic");

    companion object {
        fun of(v: VoiceSpec, lang: String): VoiceShelf = when {
            v.engine == "system" -> PHONE
            v.stars >= 4 -> NATURAL
            lang == "hi" -> if (v.engine == "kitten" || v.engine == "piper") LIGHT else HINDI
            v.accent == "Indian" || v.accent == "South Asian" -> INDIAN
            v.accent != "US" && v.accent != "Multilingual" -> ACCENTS
            else -> LIGHT
        }
    }
}

/** Every voice EchoMind can use, on shelves by how they sound, to try, download and choose. */
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
    var query by rememberSaveable { mutableStateOf("") }
    var onlyReady by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(setOf(VoiceShelf.NATURAL.name, VoiceShelf.INDIAN.name, VoiceShelf.HINDI.name)) }
    var loadingSystem by remember { mutableStateOf(false) }
    var systemCount by remember { mutableStateOf(VoiceCatalog.system.size) }
    LaunchedEffect(Unit) { if (VoiceCatalog.system.isEmpty()) { loadingSystem = true; systemCount = SystemVoices.load(ctx).size; loadingSystem = false } }
    val chosen = if (language == "hi") s.voiceHindi else s.voiceEnglish
    val searching = query.isNotBlank()
    val shelves = remember(language, gender, query, onlyReady, systemCount, models) {
        val q = query.trim().lowercase()
        VoiceCatalog.all.filter { v ->
            v.reads(language) && (gender.isEmpty() || v.gender == gender) && (!onlyReady || VoiceCatalog.ready(v, g.models)) &&
                (q.isEmpty() || listOf(v.label, v.accent, VoiceCatalog.engineName(v.engine), if (v.gender == "f") "female" else if (v.gender == "m") "male" else "").any { q in it.lowercase() })
        }.sortedWith(compareBy({ it.id != "kokoro-heart" }, { -it.stars }, { it.engine }, { it.label })).groupBy { VoiceShelf.of(it, language) }.toSortedMap()
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Voices", onBack = { nav.back() })
        androidx.compose.material3.OutlinedTextField(
            query, { query = it }, singleLine = true, placeholder = { Text("Search voices, accents…") },
            leadingIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.Search, null) },
            trailingIcon = { if (searching) IconButton(onClick = { query = "" }) { Icon(androidx.compose.material.icons.Icons.Rounded.Close, "Clear") } },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = Sp.gutter),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = Sp.gutter, vertical = Sp.s), horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
            items(listOf("en" to "English", "hi" to "Hindi")) { (k, l) -> FilterChip(language == k, { language = k }, label = { Text(l) }) }
            item { androidx.compose.material3.VerticalDivider(Modifier.height(32.dp).padding(horizontal = 2.dp)) }
            items(listOf("f" to "Female", "m" to "Male")) { (k, l) -> FilterChip(gender == k, { gender = if (gender == k) "" else k }, label = { Text(l) }) }
            item { FilterChip(onlyReady, { onlyReady = !onlyReady }, label = { Text("Downloaded") }) }
        }
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp), state = app.echomind.ui.components.rememberKeptListState()) {
            VoiceCatalog.get(chosen)?.let { cur ->
                item(key = "current") {
                    Text("IN USE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    VoiceRow(cur, true, language)
                }
            }
            if (shelves.isEmpty()) item { Text("No voices match.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Sp.l)) }
            for ((shelf, voices) in shelves) {
                val expanded = searching || shelf.name in open
                item(key = "h-${shelf.name}") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                        .clickable { open = if (shelf.name in open) open - shelf.name else open + shelf.name }.padding(top = Sp.m, bottom = 2.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${shelf.title}  ·  ${voices.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(shelf.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!searching) Icon(if (expanded) androidx.compose.material.icons.Icons.Rounded.ExpandLess else androidx.compose.material.icons.Icons.Rounded.ExpandMore, if (expanded) "Collapse" else "Expand")
                    }
                }
                if (expanded) items(voices, key = { "${shelf.name}-${it.id}" }) { v -> VoiceRow(v, chosen == v.id, language) }
                if (expanded && shelf == VoiceShelf.PHONE) item(key = "phone-more") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                        OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Phone voice settings") }
                        TextButton(onClick = { loadingSystem = true; scope.launch { systemCount = SystemVoices.load(ctx).size; loadingSystem = false } }) { Text("Refresh") }
                    }
                }
            }
            if (loadingSystem) item(key = "loading") { Row(verticalAlignment = Alignment.CenterVertically) { LoadingIndicator(); Spacer(Modifier.width(Sp.s)); Text("Looking for the phone's voices…") } }
            item(key = "foot") {
                Text("One download holds a whole family (all 28 Kokoro voices come together). Everything runs offline.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = Sp.l))
            }
        }
    }
}

/** One voice, compact: name and stars, what it is, and one main action. Less-used actions live in ⋮. */
@Composable
private fun VoiceRow(v: VoiceSpec, selected: Boolean, language: String) {
    val g = App.graph
    val models by g.models.states.collectAsStateWithLifecycle()
    // Kokoro voices download the full-quality model (the Mac's), unless the compressed one is already here.
    val getPack = if (v.pack == "kokoro-multi" && models["kokoro-multi"]?.installed != true) VoiceCatalog.KOKORO_HQ else v.pack
    val pack = ModelCatalog.get(getPack)
    val st = models[getPack]
    val ready = VoiceCatalog.ready(v, g.models)
    val busy = st?.downloading == true || st?.extracting == true
    var menu by remember { mutableStateOf(false) }
    EchoCard(color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerLow, padding = PaddingValues(horizontal = Sp.m, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(v.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Text("★".repeat(v.stars), style = MaterialTheme.typography.labelSmall, color = LocalSemantic.current.warning)
                    if (v.id == "kokoro-heart") { Spacer(Modifier.width(6.dp)); Pill("Same as Mac") }
                }
                val meta = listOfNotNull(
                    if (v.engine == "system") v.accent else VoiceCatalog.engineName(v.engine),
                    v.accent.takeIf { v.engine != "system" && it != "Multilingual" },
                    when (v.gender) { "f" -> "female"; "m" -> "male"; else -> null },
                    if (v.langs.size > 1) "English + Hindi" else null,
                    if (v.online) "online" else null,
                ).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = if (v.online) LocalSemantic.current.cloud else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (ready) IconButton(onClick = { g.speaker.preview(v.id) }) { Icon(Icons.Rounded.PlayArrow, "Play a sample") }
            when {
                selected -> Icon(androidx.compose.material.icons.Icons.Rounded.CheckCircle, "In use", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = Sp.s))
                ready -> androidx.compose.material3.FilledTonalButton(onClick = { g.scope.launch { g.settings.update { if (language == "hi") it.copy(voiceHindi = v.id) else it.copy(voiceEnglish = v.id) } } },
                    contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Use") }
                busy -> TextButton(onClick = { g.models.cancel(getPack) }) { Text("Cancel") }
                pack != null -> OutlinedButton(onClick = { g.models.download(getPack) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Get · ${Fmt.bytes(pack.bytes)}") }
            }
            val other = if (language == "hi") "en" else "hi"
            val canOther = ready && v.langs.size > 1 && (if (other == "hi") g.settings.value.voiceHindi else g.settings.value.voiceEnglish) != v.id
            val inUse = listOf(g.settings.value.voiceEnglish, g.settings.value.voiceHindi).any { VoiceCatalog.get(it)?.pack == v.pack }
            val canDelete = v.engine != "system" && models[v.pack]?.installed == true && !inUse
            if (canOther || canDelete || v.note.isNotBlank()) androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menu = true }) { Icon(androidx.compose.material.icons.Icons.Rounded.MoreVert, "More") }
                androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                    if (canOther) androidx.compose.material3.DropdownMenuItem(text = { Text("Also use for ${if (other == "hi") "Hindi" else "English"}") }, onClick = {
                        menu = false; g.scope.launch { g.settings.update { if (other == "hi") it.copy(voiceHindi = v.id) else it.copy(voiceEnglish = v.id) } }
                    })
                    if (canDelete) androidx.compose.material3.DropdownMenuItem(text = { Text("Delete ${ModelCatalog.get(v.pack)?.label ?: "pack"}", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; g.models.delete(v.pack) })
                    if (v.note.isNotBlank()) androidx.compose.material3.DropdownMenuItem(text = { Text(v.note, style = MaterialTheme.typography.bodySmall) }, onClick = { menu = false }, enabled = false)
                }
            }
        }
        if (busy) {
            LinearProgressIndicator(progress = { st!!.progress }, modifier = Modifier.fillMaxWidth().padding(top = Sp.s))
            Text(if (st!!.extracting) "Unpacking…" else downloadLine(st, pack?.bytes ?: 0), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Offers the uncompressed Kokoro model (the Mac's), and picks between it and the compressed one by what this phone keeps up with. */
@Composable
private fun KokoroHq() {
    val g = App.graph
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val models by g.models.states.collectAsStateWithLifecycle()
    val hq = ModelCatalog.get(VoiceCatalog.KOKORO_HQ) ?: return
    val st = models[hq.id]
    val full = st?.installed == true
    val small = models["kokoro-multi"]?.installed == true
    var measuring by remember { mutableStateOf(false) }
    fun measure() { if (!measuring) { measuring = true; scope.launch { runCatching { g.speaker.measureKokoro() }; measuring = false } } }
    // Time both models once they're both here, so "auto" knows what keeps up.
    LaunchedEffect(full, small) { if (full && small && s.kokoroFullUpTo == 0f) measure() }
    EchoCard(color = if (full) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.tertiaryContainer) {
        Text("Kokoro quality", style = MaterialTheme.typography.titleSmall)
        if (!full) Text("The voices here use a compressed copy of Kokoro, which sounds slightly rough. The full model is the one the Mac app plays: clearer and smoother, but slower to generate. ${Fmt.bytes(hq.bytes)}, over Wi-Fi.",
            style = MaterialTheme.typography.bodySmall)
        if (full && small) {
            fun upTo(v: Float) = if (v == 0f) "not measured" else "smooth up to ${x(v)}"
            Text("Full quality (as on the Mac): ${if (measuring) "measuring…" else upTo(s.kokoroFullUpTo)}\nCompressed: ${if (measuring) "measuring…" else upTo(s.kokoroSmallUpTo)}" +
                if (!measuring && g.speaker.lastProviderTimes.isNotEmpty()) "\nEngines: " + g.speaker.lastProviderTimes.entries.joinToString(" · ") { (k, v) -> "$k ${x(v.first)}/${x(v.second)}" } + " (using ${s.kokoroProvider})" else "",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("auto" to "Auto", "full" to "Full", "compressed" to "Compressed").forEachIndexed { i, (k, l) ->
                    SegmentedButton(s.kokoroQuality == k, { g.scope.launch { g.settings.update { it.copy(kokoroQuality = k) } } }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                }
            }
            val usingFull = VoiceCatalog.fullFor(s)
            Text(
                when {
                    s.kokoroQuality == "auto" && usingFull && s.kokoroFullUpTo in 0.01f..s.readSpeed -> "Using full quality — on this phone it's also the faster one. Neither keeps up at your ${x(s.readSpeed)}, so there may be short pauses; Supertonic voices sound almost as natural and keep up better."
                    s.kokoroQuality == "auto" && usingFull -> "Using full quality — it keeps up at your ${x(s.readSpeed)}."
                    s.kokoroQuality == "auto" -> "Using compressed at your ${x(s.readSpeed)}: the full model can't generate that fast on this phone, so it would stutter. Slow down to ${x(s.kokoroFullUpTo)} or less for full quality."
                    usingFull && s.kokoroFullUpTo in 0.01f..s.readSpeed -> "Full quality may pause between sentences at ${x(s.readSpeed)}."
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        } else if (full) Text("Kokoro voices use the uncompressed model, the same one the Mac app plays.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.padding(top = Sp.s)) {
            when {
                st?.downloading == true || st?.extracting == true -> {
                    Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
                        LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                        Text(if (st.extracting) "Unpacking…" else downloadLine(st, hq.bytes), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { g.models.cancel(hq.id) }) { Text("Cancel") }
                }
                full -> {
                    if (small) TextButton(enabled = !measuring, onClick = { measure() }) { Text("Measure again") }
                    TextButton(onClick = { g.models.delete(hq.id) }) { Text("Remove full quality", color = MaterialTheme.colorScheme.error) }
                }
                else -> Button(onClick = { g.models.download(hq.id) }) { Text("Get · ${Fmt.bytes(hq.bytes)}") }
            }
        }
    }
}
