package app.echomind.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.Geo
import app.echomind.brain.Place
import app.echomind.brain.Trip
import app.echomind.core.Settings
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.SwitchRow
import app.echomind.ui.components.rememberResumeTick
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private fun kindIcon(kind: String): ImageVector = when (kind) { "home" -> Icons.Rounded.Home; "work" -> Icons.Rounded.Business; else -> Icons.Rounded.Place }
private fun meters(m: Int) = if (m < 1000) "$m m" else "%.1f km".format(m / 1000f)

/** Travel mode: EchoMind rests on trips, and can notice by itself that you've left. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TravelScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    fun set(change: (Settings) -> Settings) { scope.launch { g.settings.update(change) } }
    val hasCoarse = remember(tick) { g.travel.hasPermission(background = false) }
    val hasBackground = remember(tick) { g.travel.hasPermission(background = true) }
    var editing by remember { mutableStateOf<Place?>(null) }
    var planning by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val coarse = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Travel mode", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.m)) {
            // ── The switch ──
            item {
                EchoCard(color = if (s.travel) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Flight, null, tint = if (s.travel) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(Sp.l))
                        Column(Modifier.weight(1f)) {
                            Text(if (s.travel) "Travel mode is on" else "Travel mode is off", style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (s.travel) listOfNotNull(
                                    "Since ${Fmt.dayTime(s.travelSince)}",
                                    when (s.travelReason) { "auto" -> "turned on by itself"; "plan" -> "planned trip"; else -> null },
                                    s.travelWhere.takeIf { it.isNotBlank() },
                                ).joinToString(" · ")
                                else "For trips and long days out: EchoMind stops working in the background so your battery lasts.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Spacer(Modifier.height(Sp.m))
                    if (s.travel) Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                        Button(onClick = { scope.launch { g.travel.disable() } }) { Text("I'm back") }
                        if (s.travelReason == "auto") OutlinedButton(onClick = { scope.launch { g.travel.disable(suppressHours = 12) } }) { Text("Not travelling") }
                    } else Button(onClick = { scope.launch { g.travel.enable("manual") } }) { Icon(Icons.Rounded.Flight, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Turn on travel mode") }
                }
            }
            // ── What it does ──
            item {
                SectionHeader("While travelling")
                // One choice, from saving the most battery to keeping the most memories.
                val profile = if (s.travelKeepRecording && s.travelProfile == "rest") "record" else s.travelProfile
                listOf(
                    Triple("rest", "Rest completely", "Nothing runs: no mic, no location, no AI. Best for battery."),
                    Triple("places", "Only note where I go", "A rough location every 30 min for your trip map. No mic, no AI."),
                    Triple("record", "Record and note places, process later", "Mic and places on; nothing is transcribed until you're back."),
                ).forEach { (k, title, sub) ->
                    androidx.compose.material3.Surface(onClick = { set { it.copy(travelProfile = k, travelKeepRecording = false) } }, shape = MaterialTheme.shapes.medium,
                        color = if (profile == k) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(bottom = Sp.s)) {
                        Row(Modifier.padding(Sp.m), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(profile == k, onClick = { set { it.copy(travelProfile = k, travelKeepRecording = false) } })
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleSmall)
                                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                EchoCard {
                    Effect(profile == "record", if (profile == "record") "Listening stays on, nothing is transcribed" else "Listening stops")
                    Effect(profile != "rest", if (profile == "rest") "No location at all" else "Where you go is noted for the map (about every 30 min)")
                    Effect(s.travelProcessOnCharge, if (s.travelProcessOnCharge) "Transcription and AI only while charging" else "No transcription, AI or night review — even on the charger")
                    Effect(false, "No check-ins or nudges; the AI model is freed from memory")
                    Effect(true, "Reminders and alarms still ring")
                    Effect(true, "Everything stays readable: tasks, notes, journal, map, Ask")
                    Effect(true, "Back home, it catches up by itself")
                }
                SwitchRow("Catch up while charging", "On the charger (hotel, car) it may transcribe and run the AI; on battery it rests", s.travelProcessOnCharge) { v -> set { it.copy(travelProcessOnCharge = v) } }
                SwitchRow("Tell me when I'm home", "Uses only locations other apps already got — no extra battery", s.travelNoticeHome) { v -> set { it.copy(travelNoticeHome = v) } }
            }
            // ── Planned trips ──
            item {
                SectionHeader("Plan a trip", action = "Add", onAction = { planning = true })
                if (s.trips.isEmpty()) Text("Know the dates? Travel mode turns on when the trip starts and off when it ends — no location needed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.trips.sortedBy { it.from }.forEach { t ->
                    EchoCard(modifier = Modifier.padding(bottom = Sp.s)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.label.ifBlank { "Trip" }, style = MaterialTheme.typography.titleSmall)
                                Text("${LocalDate.parse(t.from).format(DateTimeFormatter.ofPattern("EEE d MMM"))} – ${LocalDate.parse(t.to).format(DateTimeFormatter.ofPattern("EEE d MMM"))}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { set { it.copy(trips = it.trips - t) } }) { Icon(Icons.Rounded.Delete, "Remove trip") }
                        }
                    }
                }
            }
            // ── Automatic ──
            item {
                SectionHeader("Turn on by itself")
                SwitchRow("When I'm away from my places", "Checks about every 30 minutes with Wi-Fi and mobile networks (never GPS), and stops checking once travel mode is on", s.travelAuto) { v ->
                    set { it.copy(travelAuto = v) }
                    if (v && !hasCoarse) coarse.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if ((s.travelAuto || s.travelLearn) && !hasBackground) EchoCard(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = Sp.s)) {
                    Text(if (!hasCoarse) "Location permission needed" else "Allow location “all the time”", style = MaterialTheme.typography.titleSmall)
                    Text(if (!hasCoarse) "Approximate location is enough." else "The check runs in the background, so Android needs “Allow all the time” (approximate is fine). Without it, nothing is checked.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        if (!hasCoarse) coarse.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        else runCatching { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }.onFailure {
                            ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                        }
                    }) { Text(if (!hasCoarse) "Allow location" else "Open permission") }
                }
                if (s.travelAuto) {
                    if (s.places.isEmpty()) Text("Add your home (and work) below first — without places there's nothing to be away from.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = Sp.l))
                    var away by remember(s.travelAwayKm) { mutableFloatStateOf(s.travelAwayKm.toFloat()) }
                    Text("Away means more than ${away.toInt()} km from all your places", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Sp.l, end = Sp.l, top = Sp.s))
                    Slider(away, { away = it }, valueRange = 10f..200f, steps = 18, onValueChangeFinished = { set { it.copy(travelAwayKm = away.toInt()) } }, modifier = Modifier.padding(horizontal = Sp.l))
                    var corridor by remember(s.travelCorridorKm) { mutableFloatStateOf(s.travelCorridorKm.toFloat()) }
                    Text("The way between your places counts within ${corridor.toInt()} km", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Sp.l))
                    Slider(corridor, { corridor = it }, valueRange = 1f..10f, steps = 8, onValueChangeFinished = { set { it.copy(travelCorridorKm = corridor.toInt()) } }, modifier = Modifier.padding(horizontal = Sp.l))
                    Text("It switches only after two readings at least 20 minutes apart (or when you're clearly moving fast), so one odd reading never does. " +
                        "If it gets it wrong, tap “Not travelling” and it stays off for 12 hours.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Sp.l))
                    g.travel.lastCheck()?.let { Text("Last check: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = Sp.l, vertical = Sp.s)) }
                }
            }
            // ── Places ──
            item {
                SectionHeader("Your places", action = "Add", onAction = { editing = Place(UUID.randomUUID().toString(), "", if (s.places.none { it.kind == "home" }) "home" else "work", 0.0, 0.0) })
                if (s.places.isEmpty()) Text("Home, work, your parents' place… as many as you like. Being at any of them, or on the way between two, is never travelling.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.places.forEach { p ->
                    EchoCard(onClick = { editing = p }, modifier = Modifier.padding(bottom = Sp.s)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(kindIcon(p.kind), null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(Sp.m))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleSmall)
                                Text("${p.kind.replaceFirstChar { it.uppercase() }} · within ${meters(p.radiusM)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                // Learned suggestions.
                val suggestions = remember(s.places, tick) { g.travel.suggestions() }
                suggestions.forEach { sg ->
                    EchoCard(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = Sp.s)) {
                        Text(if (sg.kind == "home") "This looks like home" else "This looks like work", style = MaterialTheme.typography.titleSmall)
                        Text(if (sg.kind == "home") "Your phone spends most nights here (${sg.samples} readings)." else "Your phone spends weekday working hours here (${sg.samples} readings).",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { editing = Place(UUID.randomUUID().toString(), if (sg.kind == "home") "Home" else "Work", sg.kind, sg.lat, sg.lon) }) { Text("Save this place") }
                    }
                }
                SwitchRow("Learn my usual places", "Keeps approximate readings for two weeks, on this phone only, to suggest home and work", s.travelLearn) { v -> set { it.copy(travelLearn = v) }; if (!v) g.travel.forgetSamples() }
            }
            // ── History ──
            val spans = g.travel.spans().takeLast(5).reversed()
            if (spans.isNotEmpty()) item {
                SectionHeader("Recent trips")
                spans.forEach { sp ->
                    TextButton(onClick = { nav.go(app.echomind.ui.MapRoute(java.time.Instant.ofEpochMilli(sp.from).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString())) }) { Text("Show on the map") }
                    Text("${Fmt.dayTime(sp.from)} → ${if (sp.to == 0L) "now" else Fmt.dayTime(sp.to)} · ${when (sp.reason) { "auto" -> "noticed by itself"; "plan" -> "planned"; else -> "turned on by you" }}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }

    editing?.let { p ->
        PlaceDialog(p, locating, message,
            onLocate = {
                locating = true; message = null
                if (!hasCoarse) { coarse.launch(Manifest.permission.ACCESS_COARSE_LOCATION); locating = false }
                else scope.launch {
                    val f = g.travel.locate()
                    locating = false
                    if (f == null) message = "Couldn't get a location. Is location turned on?"
                    else { editing = p.copy(lat = f.lat, lon = f.lon); message = "Got it (accurate to about ${meters(f.accuracyM.toInt())})." }
                }
            },
            onSave = { saved -> set { st -> st.copy(places = st.places.filterNot { it.id == saved.id } + saved) }; editing = null; message = null },
            onDelete = { set { st -> st.copy(places = st.places.filterNot { it.id == p.id }) }; editing = null; message = null },
            onDismiss = { editing = null; message = null },
            isNew = s.places.none { it.id == p.id },
        )
    }
    if (planning) {
        val state = rememberDateRangePickerState()
        var label by remember { mutableStateOf("") }
        DatePickerDialog(
            onDismissRequest = { planning = false },
            confirmButton = {
                TextButton(enabled = state.selectedStartDateMillis != null, onClick = {
                    val from = Instant.ofEpochMilli(state.selectedStartDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                    val to = state.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: from
                    set { it.copy(trips = it.trips + Trip(UUID.randomUUID().toString(), label.trim(), from.toString(), to.toString())) }
                    scope.launch { g.travel.checkPlans() }
                    planning = false
                }) { Text("Save trip") }
            },
            dismissButton = { TextButton(onClick = { planning = false }) { Text("Cancel") } },
        ) {
            OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth().padding(horizontal = Sp.l), singleLine = true, label = { Text("Name (optional) — e.g. Goa") })
            DateRangePicker(state, Modifier.height(440.dp), title = null, showModeToggle = false)
        }
    }
}

@Composable
private fun Effect(keeps: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Icon(if (keeps) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = if (keeps) LocalSemantic.current.positive else MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Sp.s))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PlaceDialog(p: Place, locating: Boolean, message: String?, onLocate: () -> Unit, onSave: (Place) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit, isNew: Boolean) {
    var name by remember(p.id) { mutableStateOf(p.name) }
    var kind by remember(p.id) { mutableStateOf(p.kind) }
    var radius by remember(p.id) { mutableIntStateOf(p.radiusM) }
    val located = p.lat != 0.0 || p.lon != 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add a place" else "Edit place") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, placeholder = { Text("Home, Office, Parents…") }, modifier = Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("home" to "Home", "work" to "Work", "other" to "Other").forEachIndexed { i, (k, l) ->
                        SegmentedButton(kind == k, { kind = k }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                    }
                }
                Text("Counts as there within ${meters(radius)}", style = MaterialTheme.typography.bodyMedium)
                Slider(radius.toFloat(), { radius = ((it / 100).toInt() * 100).coerceAtLeast(200) }, valueRange = 200f..5_000f)
                OutlinedButton(onClick = onLocate, enabled = !locating) {
                    Icon(Icons.Rounded.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (locating) "Finding you…" else if (located) "Use where I am now instead" else "I'm here now — use this location")
                }
                Text(message ?: if (located) "Location saved (%.4f, %.4f).".format(p.lat, p.lon) else "Stand at the place and tap the button above.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = located && name.isNotBlank(), onClick = { onSave(p.copy(name = name.trim(), kind = kind, radiusM = radius)) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
