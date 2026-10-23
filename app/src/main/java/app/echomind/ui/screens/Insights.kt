package app.echomind.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.Plans
import app.echomind.brain.Stats
import app.echomind.data.SoundRow
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.PlanRoute
import app.echomind.ui.TaskRoute
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.ListenButton
import app.echomind.ui.components.Pill
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.Tile
import app.echomind.ui.components.rememberResumeTick
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.LocalDate

private fun mins(m: Int) = if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
private fun secs(s: Int) = if (s < 60) "<1m" else mins(s / 60)

/** One day (or week) in numbers: talk share, hours, people. Opened from the dashboard. */
@Composable
fun DayNumbersScreen() {
    val g = App.graph
    val nav = LocalNav.current
    var dayStr by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var week by rememberSaveable { mutableStateOf(false) }
    val day = LocalDate.parse(dayStr)
    val tick = rememberResumeTick()
    val d by produceState<Stats.Day?>(null, dayStr, tick) { value = g.stats.day(day) }
    val w by produceState<List<Stats.Day>>(emptyList(), dayStr, week, tick) { if (week) value = g.stats.week(day) }
    val sem = LocalSemantic.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("A day in numbers", onBack = { nav.back() })
        Row(Modifier.fillMaxWidth().padding(horizontal = Sp.gutter), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { dayStr = day.minusDays(if (week) 7 else 1).toString() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Earlier") }
            Text(if (week) "Week to ${Fmt.day(day)}" else Fmt.dayLong(day), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(enabled = day < LocalDate.now(), onClick = { dayStr = minOf(LocalDate.now(), day.plusDays(if (week) 7 else 1)).toString() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Later") }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Sp.gutter)) {
            SegmentedButton(!week, { week = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Day") }
            SegmentedButton(week, { week = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Week") }
        }
        val s = d
        if (s == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }; return@Column }
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, top = Sp.s, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            if (week && w.isNotEmpty()) {
                item { WeekView(w) }
                return@LazyColumn
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Tile("talking", mins(s.talkMin), Modifier.weight(1f), hint = s.firstAt?.let { "${Fmt.time(it)}–${Fmt.time(s.lastAt!!)}" })
                    Tile("you spoke", "${s.meShare}%", Modifier.weight(1f), hint = mins(s.meMin))
                    Tile("conversations", "${s.conversations}", Modifier.weight(1f), hint = if (s.calls > 0) "${s.calls} calls" else null)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Tile("tasks done", "${s.tasksDone}", Modifier.weight(1f), hint = "${s.tasksMade} new")
                    Tile("productivity", s.productivity?.toString() ?: "–", Modifier.weight(1f), hint = if (s.productivity == null) "after the journal" else null)
                    Tile("listened", mins(s.listenedMin), Modifier.weight(1f), hint = if (s.gapMin > 0) "${mins(s.gapMin)} not heard" else null)
                }
            }
            item {
                SectionHeader("You and others")
                EchoCard {
                    ShareBar(s.meMin, s.othersMin, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.height(Sp.s))
                    Text("You ${mins(s.meMin)} · Others ${mins(s.othersMin)} · ${s.myWords} of ${s.words} words were yours", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.byPerson.isNotEmpty()) {
                        Spacer(Modifier.height(Sp.s))
                        val max = s.byPerson.maxOf { it.second }.coerceAtLeast(1)
                        s.byPerson.take(8).forEach { (who, sec) -> Bar(who, sec, max, MaterialTheme.colorScheme.tertiary, secs(sec)) }
                    }
                    if (s.byPerson.any { it.first == "Others" }) Text("“Others” are voices without a name yet — name them in a transcript and EchoMind recognises them next time.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = Sp.xs))
                }
            }
            item {
                SectionHeader("Through the day")
                EchoCard { Hourly(s.hourlyMe, s.hourlyOthers) }
            }
            item {
                SectionHeader("What you were doing")
                if (s.activities.isEmpty()) Text(if (g.settings.value.ambient) "Nothing noted yet for this day." else "Turn on “Notice surroundings” in Settings → Sounds to see time on the road, in the kitchen, at the computer and more.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else EchoCard {
                    val max = s.activities.maxOf { it.second }.coerceAtLeast(1)
                    s.activities.take(10).forEach { (a, m) -> Bar(a, m, max, sem.onDevice, mins(m)) }
                }
            }
            if (s.songs > 0) item { Text("♪ ${s.songs} song${if (s.songs == 1) "" else "s"} kept in Songs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun ShareBar(a: Int, b: Int, ca: Color, cb: Color) {
    val total = (a + b).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (a > 0) Box(Modifier.weight(a.toFloat() / total).fillMaxSize().background(ca))
        if (b > 0) Box(Modifier.weight(b.toFloat() / total).fillMaxSize().background(cb))
    }
}

@Composable
private fun Bar(label: String, value: Int, max: Int, color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(value.toFloat() / max).fillMaxSize().clip(RoundedCornerShape(5.dp)).background(color))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp).padding(start = Sp.s))
    }
}

/** Minutes of talk per hour: you (bottom) and others (top), 6 AM to midnight. */
@Composable
private fun Hourly(me: IntArray, others: IntArray) {
    val cMe = MaterialTheme.colorScheme.primary
    val cOther = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val hours = (6..23).toList()
    val max = hours.maxOf { me[it] + others[it] }.coerceAtLeast(10)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val bw = size.width / hours.size
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        hours.forEachIndexed { i, h ->
            val x = i * bw + bw * 0.15f
            val w = bw * 0.7f
            val hMe = size.height * me[h] / max
            val hOt = size.height * others[h] / max
            if (hMe > 0) drawRoundRect(cMe, Offset(x, size.height - hMe), Size(w, hMe), CornerRadius(3f))
            if (hOt > 0) drawRoundRect(cOther, Offset(x, size.height - hMe - hOt), Size(w, hOt), CornerRadius(3f))
        }
    }
    // 18 hourly bars in three equal parts: the labels sit where 6 AM, noon and 6 PM begin.
    Row(Modifier.fillMaxWidth()) {
        listOf("6 AM", "12 PM", "6 PM").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f)) }
        Text("12 AM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Sp.m), modifier = Modifier.padding(top = Sp.xs)) {
        Legend2(cMe, "You"); Legend2(cOther, "Others")
    }
}

@Composable
private fun Legend2(c: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(c)); Spacer(Modifier.width(4.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WeekView(days: List<Stats.Day>) {
    val sem = LocalSemantic.current
    val total = days.sumOf { it.talkMin }
    Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
            Tile("talking", mins(total), Modifier.weight(1f), hint = "${mins(total / 7)} a day")
            Tile("you spoke", "${days.sumOf { it.meMin } * 100 / days.sumOf { it.meMin + it.othersMin }.coerceAtLeast(1)}%", Modifier.weight(1f))
            Tile("tasks done", "${days.sumOf { it.tasksDone }}", Modifier.weight(1f), hint = "${days.sumOf { it.tasksMade }} new")
        }
        SectionHeader("Talk each day")
        EchoCard {
            val max = days.maxOf { it.talkMin }.coerceAtLeast(1)
            days.forEach { d -> Bar(Fmt.day(d.date), d.talkMin, max, MaterialTheme.colorScheme.primary, mins(d.talkMin)) }
        }
        SectionHeader("Productivity")
        EchoCard {
            val scored = days.filter { it.productivity != null }
            if (scored.isEmpty()) Text("Scores appear as each evening's journal is written, or when you rate a day in the calendar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else days.forEach { d -> Bar(Fmt.day(d.date), d.productivity ?: 0, 100, sem.positive, d.productivity?.toString() ?: "–") }
        }
        val people = days.flatMap { it.byPerson }.filter { it.first != "Others" }.groupBy { it.first }.mapValues { e -> e.value.sumOf { it.second } / 60 }.filterValues { it > 0 }.entries.sortedByDescending { it.value }
        if (people.isNotEmpty()) {
            SectionHeader("Who you talked with most")
            EchoCard {
                val max = people.first().value.coerceAtLeast(1)
                people.take(8).forEach { (who, m) -> Bar(who, m, max, MaterialTheme.colorScheme.tertiary, mins(m)) }
            }
        }
        val acts = days.flatMap { it.activities }.groupBy { it.first }.mapValues { e -> e.value.sumOf { it.second } }.entries.sortedByDescending { it.value }
        if (acts.isNotEmpty()) {
            SectionHeader("Time spent")
            EchoCard {
                val max = acts.first().value.coerceAtLeast(1)
                acts.take(8).forEach { (a, m) -> Bar(a, m, max, sem.onDevice, mins(m)) }
            }
        }
    }
}

/** Today, this week, this month — and what you might be missing. */
@Composable
fun PlanScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf("day") }
    var busy by remember { mutableStateOf(false) }
    val raw by remember(kind) { g.plans.observe(kind) }.collectAsStateWithLifecycle(null)
    val plan = g.plans.decode(raw)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Plan", onBack = { nav.back() }) {
            plan?.let { p -> ListenButton("Read the plan") { app.echomind.brain.Briefing.Script("Plan", listOf(p.focus, p.summary) + p.priorities.map { it.title + "." } + p.missing.map { "Might be missing: ${it.title}." }) } }
            IconButton(enabled = !busy, onClick = { busy = true; scope.launch { g.plans.make(kind); busy = false } }) { Icon(Icons.Rounded.Refresh, "Plan again") }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Sp.gutter)) {
            listOf("day" to "Today", "week" to "This week", "month" to "This month").forEachIndexed { i, (k, l) ->
                SegmentedButton(kind == k, { kind = k }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, top = Sp.s, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { LoadingIndicator(); Spacer(Modifier.width(Sp.s)); Text("Thinking through your tasks, calendar and memory…") } }
            if (plan == null && !busy) item {
                EmptyState("No plan yet", g.ai.readiness(app.echomind.core.Feature.JOURNAL) ?: "Plans are written each morning from your tasks, calendar, goals and recent days.") {
                    Button(enabled = g.ai.ready(app.echomind.core.Feature.JOURNAL), onClick = { busy = true; scope.launch { g.plans.make(kind); busy = false } }) { Text("Make the plan now") }
                }
            }
            plan?.let { p ->
                item {
                    EchoCard(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)) {
                        Text("Focus", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(p.focus.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium)
                        if (p.summary.isNotBlank()) Text(p.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Sp.xs))
                        Text("Made ${Fmt.ago(p.madeAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = Sp.xs))
                    }
                }
                if (p.priorities.isNotEmpty()) {
                    item { SectionHeader("Priorities") }
                    items(p.priorities) { it2 ->
                        EchoCard(onClick = it2.taskId?.let { id -> { nav.go(TaskRoute(id)) } }, padding = PaddingValues(Sp.m)) {
                            Text(it2.title, style = MaterialTheme.typography.bodyLarge)
                            if (it2.why.isNotBlank()) Text(it2.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (p.schedule.isNotEmpty()) {
                    item { SectionHeader("How it could go") }
                    items(p.schedule) { sl ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(sl.time, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(88.dp))
                            Text(sl.what, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (p.missing.isNotEmpty()) {
                    item { SectionHeader("You might be missing") }
                    items(p.missing) { m -> MissingRow(m) }
                }
                if (p.suggestions.isNotEmpty()) {
                    item { SectionHeader("Suggestions") }
                    items(p.suggestions) { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun MissingRow(m: Plans.Item) {
    val g = App.graph
    val scope = rememberCoroutineScope()
    var added by remember { mutableStateOf(false) }
    EchoCard(color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f), padding = PaddingValues(start = Sp.m, top = Sp.s, bottom = Sp.s, end = 0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.title, style = MaterialTheme.typography.bodyLarge)
                if (m.why.isNotBlank()) Text(m.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (added) Pill("Added") else IconButton(onClick = { added = true; scope.launch { g.tasks.addQuick(m.title, toToday = false) } }) { Icon(Icons.Rounded.Add, "Add as a task") }
        }
    }
}

/** Songs you sang or that played around you, each kept as its own clip, named with poster and links. */
@Composable
fun SongsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val st by g.settings.flow.collectAsStateWithLifecycle()
    val songs by remember { g.db.sounds().observeSongs() }.collectAsStateWithLifecycle(emptyList())
    val models by g.models.states.collectAsStateWithLifecycle()
    var playing by remember { mutableStateOf<String?>(null) } // "mine:<id>" or "preview:<id>"
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    val scanResult by g.sounds.scan.collectAsStateWithLifecycle()
    val scanning = scanResult?.endsWith("…") == true
    DisposableEffect(Unit) { onDispose { player.value?.release(); app.echomind.capture.Recorder.assistantSpeaking = false } }
    fun stop() { player.value?.release(); player.value = null; playing = null; app.echomind.capture.Recorder.assistantSpeaking = false }
    fun play(key: String, source: String) {
        if (playing == key) { stop(); return }
        stop()
        player.value = MediaPlayer().apply {
            setOnCompletionListener { stop() }
            setOnErrorListener { _, _, _ -> stop(); true }
            setDataSource(source)
            setOnPreparedListener { it.start() }
            prepareAsync()
        }
        app.echomind.capture.Recorder.assistantSpeaking = true
        playing = key
    }
    fun set(change: (app.echomind.core.Settings) -> app.echomind.core.Settings) = g.scope.launch { g.settings.update(change) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Songs", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            item {
                EchoCard {
                    app.echomind.ui.components.SwitchRow("Keep songs", "When you sing or music plays, that part is kept as its own clip with the words — never as notes or tasks", st.songs) { v -> set { it.copy(songs = v) } }
                    app.echomind.ui.components.SwitchRow("Name them by themselves", "Right after each song is transcribed", st.songsAutoIdentify) { v -> set { it.copy(songsAutoIdentify = v) } }
                    app.echomind.ui.components.SwitchRow("Look up online", "Poster, film, year and links. Sends only a few lyric words and the song title (Apple Music catalogue, LRCLIB lyrics). Off = the phone's AI guesses offline.", st.songsOnline) { v -> set { it.copy(songsOnline = v) } }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Sp.xs)) {
                        OutlinedButton(enabled = !scanning && models["sound-tagger"]?.installed == true, onClick = { g.sounds.startRescan() }) { Text(if (scanning) "Working…" else "Scan my kept recordings") }
                        Spacer(Modifier.width(Sp.s))
                        scanResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            if (models["sound-tagger"]?.installed != true) item {
                EchoCard {
                    Text("Needs the sound recognition model (29 MB)", style = MaterialTheme.typography.titleSmall)
                    val ms = models["sound-tagger"]
                    if (ms?.downloading == true || ms?.extracting == true) Text(if (ms.extracting) "Unpacking…" else "${(ms.progress * 100).toInt()}%")
                    else Button(onClick = { g.models.download("sound-tagger") }) { Text("Download") }
                }
            }
            if (songs.isEmpty()) item { EmptyState("No songs yet", "Sing something while EchoMind is listening — or scan the recordings it has kept.", icon = Icons.Rounded.MusicNote) }
            songs.groupBy { Fmt.date(it.startAt) }.forEach { (day, list) ->
                item(key = "d$day") { SectionHeader(Fmt.day(day)) }
                items(list, key = { it.id }) { song ->
                    SongCard(song, playing, onMine = { song.clipPath?.let { play("mine:${song.id}", it) } }, onPreview = { song.previewUrl?.let { play("preview:${song.id}", it) } })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongCard(s: SoundRow, playing: String?, onMine: () -> Unit, onPreview: () -> Unit) {
    val g = App.graph
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // Looking stops when the lookup has written its result (lookupAt changes).
    var looking by remember(s.lookupAt) { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    val art by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, s.artPath) {
        value = s.artPath?.let { p -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(p)?.asImageBitmap() } }
    }
    val links = remember(s.links) { runCatching { app.echomind.util.Js.json.decodeFromString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()), s.links) }.getOrDefault(emptyMap()) }
    fun open(url: String) = runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    EchoCard(padding = PaddingValues(Sp.m)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                art?.let { androidx.compose.foundation.Image(it, s.title, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                    ?: Icon(Icons.Rounded.MusicNote, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(Sp.m))
            Column(Modifier.weight(1f)) {
                Text(s.title ?: if (s.mine) "You singing" else if (s.label == "Singing") "Someone singing" else "Music playing", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                listOfNotNull(s.artist, s.album?.takeIf { it.isNotBlank() }, s.year?.toString()).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("${Fmt.span(s.startAt, s.endAt)} · ${Fmt.duration(s.endAt - s.startAt)}${if (s.mine) " · you sang" else ""}" + when (s.identifiedBy) {
                    "web" -> " · matched by lyrics"; "ai" -> " · AI's guess"; "you" -> " · named by you"; else -> "" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            IconButton(onClick = { scope.launch { g.db.sounds().update(s.copy(favourite = !s.favourite)) } }) {
                Icon(if (s.favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (s.favourite) "Unfavourite" else "Favourite", tint = if (s.favourite) LocalSemantic.current.recording else MaterialTheme.colorScheme.outline)
            }
        }
        Spacer(Modifier.height(Sp.s))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Sp.s), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (s.clipPath != null) androidx.compose.material3.FilledTonalButton(onClick = onMine) {
                Icon(if (playing == "mine:${s.id}") Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (s.mine) "My singing" else "Recording")
            }
            if (s.previewUrl != null) androidx.compose.material3.OutlinedButton(onClick = onPreview) {
                Icon(if (playing == "preview:${s.id}") Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Original (30 s)")
            }
            links.forEach { (name, url) -> androidx.compose.material3.AssistChip(onClick = { open(url) }, label = { Text(name) }) }
        }
        if (!s.lyrics.isNullOrBlank()) {
            Text(if (showLyrics) "“${s.lyrics}”" else "“${s.lyrics.take(90)}${if (s.lyrics.length > 90) "…" else ""}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Sp.xs).clickable { showLyrics = !showLyrics })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (looking) { LoadingIndicator(Modifier.size(28.dp)); Text("Recognising…", style = MaterialTheme.typography.labelMedium) }
            else if (!s.lyrics.isNullOrBlank()) TextButton(onClick = { looking = true; g.sounds.startIdentify(s) }) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (s.title == null) "Which song is this?" else "Look up again")
            }
            TextButton(onClick = { editing = true }) { Text("Edit name") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { scope.launch { g.sounds.delete(s) } }) { Icon(Icons.Rounded.Delete, "Delete song", tint = MaterialTheme.colorScheme.outline) }
        }
    }
    if (editing) {
        var title by remember { mutableStateOf(s.title.orEmpty()) }
        var artist by remember { mutableStateOf(s.artist.orEmpty()) }
        androidx.compose.material3.AlertDialog(onDismissRequest = { editing = false }, title = { Text("Which song?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    androidx.compose.material3.OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                    androidx.compose.material3.OutlinedTextField(artist, { artist = it }, label = { Text("Singer / film (optional)") }, singleLine = true)
                    Text("With “Look up online” on, the poster and links are fetched for this title.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { editing = false; scope.launch { g.sounds.rename(s, title.trim(), artist.trim()) } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
    }
}

/** The surroundings timeline for a day, for the calendar. */
@Composable
fun ActivityTimeline(day: LocalDate) {
    val g = App.graph
    val (from, to) = remember(day) { g.journal.bounds(day) }
    val sounds by remember(day) { g.db.sounds().observeRange(from, to) }.collectAsStateWithLifecycle(emptyList())
    val stretches = sounds.filter { it.kind == "ambient" && it.endAt - it.startAt >= 60_000 }
    if (stretches.isEmpty()) return
    Column {
        SectionHeader("What was going on")
        stretches.forEach { s ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(Fmt.span(s.startAt, s.endAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(140.dp))
                Text(s.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** A song in a day's list: who sang, when, a few words — opens Songs. */
@Composable
fun SongMoment(s: SoundRow) {
    val nav = LocalNav.current
    EchoCard(onClick = { nav.go(app.echomind.ui.SongsRoute) }, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f), padding = PaddingValues(Sp.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Sp.s))
            Column(Modifier.weight(1f)) {
                Text(s.title ?: if (s.mine) "You sang" else if (s.label == "Singing") "Someone sang" else "Music played", style = MaterialTheme.typography.titleSmall)
                Text("${Fmt.span(s.startAt, s.endAt)}${s.lyrics?.takeIf { it.isNotBlank() }?.let { " · “${it.take(60)}…”" } ?: ""}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun DaySongs(day: LocalDate) {
    val g = App.graph
    val (from, to) = remember(day) { g.journal.bounds(day) }
    val sounds by remember(day) { g.db.sounds().observeRange(from, to) }.collectAsStateWithLifecycle(emptyList())
    val songs = sounds.filter { it.kind == "song" }
    if (songs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
        SectionHeader("Songs · ${songs.size}")
        songs.forEach { SongMoment(it) }
    }
}

