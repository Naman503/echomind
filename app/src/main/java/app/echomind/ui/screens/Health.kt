package app.echomind.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.Brain
import app.echomind.capture.CaptureController
import app.echomind.core.Settings
import app.echomind.ui.HealthRoute
import app.echomind.ui.LocalNav
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.SwitchRow
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import app.echomind.work.Governor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A stretch of a slider's range and what it means. */
data class Zone(val to: Float, val level: Level, val label: String, val detail: String)
enum class Level { SAFE, OK, CAUTION, DANGER }

@Composable
fun levelColor(l: Level): Color = when (l) {
    Level.SAFE -> LocalSemantic.current.positive
    Level.OK -> LocalSemantic.current.onDevice
    Level.CAUTION -> LocalSemantic.current.warning
    Level.DANGER -> MaterialTheme.colorScheme.error
}

/**
 * A slider that shows what each part of its range means: coloured zones under
 * the track, the zone you're in with an explanation, the recommended value and
 * (optionally) where the phone is right now.
 */
@Composable
fun ZonedSlider(
    title: String, value: Float, range: ClosedFloatingPointRange<Float>, zones: List<Zone>, format: (Float) -> String,
    recommended: Float? = null, now: Float? = null, nowLabel: String = "now", step: Float = 1f, onChange: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    val zone = zones.firstOrNull { v <= it.to } ?: zones.last()
    val c = levelColor(zone.level)
    Column(Modifier.padding(vertical = Sp.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(v), style = MaterialTheme.typography.titleMedium, color = c)
        }
        Slider(v, { x -> v = (Math.round(x / step) * step).coerceIn(range.start, range.endInclusive) }, valueRange = range, onValueChangeFinished = { onChange(v) })
        // The zones as a coloured bar, with the recommended value and the live reading marked.
        val colors = zones.map { levelColor(it.level) }
        val marker = MaterialTheme.colorScheme.onSurface
        val live = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(14.dp).padding(horizontal = 10.dp)) {
            val span = range.endInclusive - range.start
            var from = range.start
            zones.forEachIndexed { i, z ->
                val x0 = size.width * (from - range.start) / span
                val x1 = size.width * (minOf(z.to, range.endInclusive) - range.start) / span
                drawRect(colors[i].copy(alpha = 0.7f), Offset(x0, 4f), androidx.compose.ui.geometry.Size(maxOf(0f, x1 - x0), 6f))
                from = z.to
            }
            recommended?.let { r -> val x = size.width * (r - range.start) / span; drawLine(marker, Offset(x, 0f), Offset(x, size.height), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))) }
            now?.takeIf { it in range }?.let { n -> val x = size.width * (n - range.start) / span; drawCircle(live, 7f, Offset(x, 7f)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            Text(format(range.start), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
            if (now != null) Text("● $nowLabel ${format(now)}", style = MaterialTheme.typography.labelSmall, color = live, modifier = Modifier.weight(1f))
            if (recommended != null) Text("┆ suggested ${format(recommended)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1.3f))
            Text(format(range.endInclusive), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(Sp.s))
            Column {
                Text(zone.label, style = MaterialTheme.typography.labelLarge, color = c)
                Text(zone.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Battery temperature zones, from Li-ion guidance and how Android behaves when hot. */
val TEMP_ZONES = listOf(
    Zone(35f, Level.SAFE, "Cool", "Ideal. The battery doesn't age from heat."),
    Zone(40f, Level.OK, "Warm — normal under work", "Normal while the phone works; recommended limit for all-day processing."),
    Zone(45f, Level.CAUTION, "Hot", "Battery ages noticeably faster; the phone feels warm in a pocket; charging slows down."),
    Zone(50f, Level.DANGER, "Very hot", "Android starts slowing the phone, charging may stop, and the battery wears quickly. Use only for short bursts."),
    Zone(60f, Level.DANGER, "Danger", "Risk of lasting battery damage. Phones shut apps down or switch off around here — never keep working at this heat."),
)

private fun thermalZones() = listOf(
    Zone(1f, Level.SAFE, "Pause as soon as Android feels warmth", "Most careful: work stops at Android's first heat signal."),
    Zone(2f, Level.OK, "Pause when Android says warm (recommended)", "Work stops before Android has to slow the phone down."),
    Zone(3f, Level.CAUTION, "Pause only when hot", "Android may already be slowing the phone; other apps can stutter."),
    Zone(4f, Level.DANGER, "Pause only when very hot", "Android is throttling hard; the phone is uncomfortable to hold. Close to its emergency level, where work always stops."),
)

private fun headroomZones() = listOf(
    Zone(0.6f, Level.SAFE, "Early", "Stops long before any slowdown — very cool, but processing pauses often."),
    Zone(0.9f, Level.OK, "Balanced (recommended around 85%)", "Pauses just before Android would need to slow the phone."),
    Zone(1.0f, Level.CAUTION, "Late", "Works right up to the point where Android starts throttling."),
    Zone(1.3f, Level.DANGER, "Ignored", "Android's forecast is not used: only battery temperature and heat level protect the phone."),
)

private fun batteryZones() = listOf(
    Zone(9f, Level.DANGER, "Very low", "Processing can drain the last of the battery; the phone may switch off mid-day."),
    Zone(19f, Level.CAUTION, "Low", "Works until the battery is nearly empty; little left for calls and maps."),
    Zone(35f, Level.OK, "Balanced (recommended 20%)", "Keeps a reserve for the rest of the day."),
    Zone(80f, Level.SAFE, "Careful", "Only processes with plenty of battery; more waits until you charge."),
)

private fun useZones() = listOf(
    Zone(30f, Level.SAFE, "Light", "Barely warms the phone; catching up takes longer."),
    Zone(65f, Level.OK, "Balanced (recommended 60%)", "Steady progress with rests in between."),
    Zone(90f, Level.CAUTION, "Heavy", "Phone gets warm during long catch-ups; more battery use."),
    Zone(100f, Level.DANGER, "Non-stop", "No rests at all: fastest, but hottest and hardest on the battery."),
)

private fun threadZones() = listOf(
    Zone(2f, Level.SAFE, "Gentle", "Leaves most of the processor free for you."),
    Zone(4f, Level.OK, "Balanced", "Faster; fine for this phone, especially while charging."),
    Zone(6f, Level.CAUTION, "Heavy", "Much faster AI, but the phone heats up and other apps may stutter."),
    Zone(8f, Level.DANGER, "All cores", "Uses every core; gets hot quickly. Only for short runs on the charger."),
)

private fun breakZones() = listOf(
    Zone(0f, Level.DANGER, "Never rest", "No breaks: heat builds up over long catch-ups."),
    Zone(15f, Level.SAFE, "Often", "Frequent breaks keep the phone cool."),
    Zone(30f, Level.OK, "Balanced (recommended 20 min)", "A short break after a long stretch."),
    Zone(90f, Level.CAUTION, "Rarely", "Long stretches of work before resting; the phone can get warm."),
)

/** The phone's health: temperature, what's running or paused, every limit you control. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HealthScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val gov by g.governor.state.collectAsStateWithLifecycle()
    val history by g.governor.history.collectAsStateWithLifecycle()
    var p by remember { mutableStateOf(g.governor.read()) }
    var override by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { p = g.governor.read(); g.governor.refresh(); delay(5_000) } }
    fun set(change: (Settings) -> Settings) = g.scope.launch { g.settings.update(change); g.governor.refresh(); g.brain.poke() }
    val tempZone = TEMP_ZONES.firstOrNull { p.tempC <= it.to } ?: TEMP_ZONES.last()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Phone health", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            item { StatusCard(big = true) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Gauge("battery", "${"%.1f".format(p.tempC)}°C", tempZone.label, levelColor(tempZone.level), Modifier.weight(1f))
                    Gauge("Android heat", Governor.thermalName(p.thermal), "level ${p.thermal} of 6", levelColor(if (p.thermal <= 0) Level.SAFE else if (p.thermal == 1) Level.OK else if (p.thermal == 2) Level.CAUTION else Level.DANGER), Modifier.weight(1f))
                    Gauge("heat forecast", p.headroom?.let { "${(it * 100).toInt()}%" } ?: "—", "100% = slowing down", levelColor(p.headroom.let { hr -> when { hr == null || hr < 0.6f -> Level.SAFE; hr < 0.85f -> Level.OK; hr < 1f -> Level.CAUTION; else -> Level.DANGER } }), Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Gauge("battery", "${p.percent}%", if (p.charging) "charging" else if (p.saver) "battery saver on" else "on battery", levelColor(if (p.percent < 10) Level.DANGER else if (p.percent < 20) Level.CAUTION else Level.SAFE), Modifier.weight(1f))
                    Gauge("CPU threads", "${g.governor.threads()}", if (p.charging) "charging setting" else "battery setting", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    Gauge("pacing", "${if (p.charging) maxOf(s.chargingUtilization, s.utilization) else s.utilization}%", "of the time working", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                }
            }
            if (history.size >= 3) item {
                SectionHeader("Battery temperature · last ${(history.last().at - history.first().at) / 60_000} min")
                EchoCard { TempChart(history, s.maxBatteryTempC.toFloat()) }
            }
            item {
                SectionHeader("Controls")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    if (s.processingPausedUntil > System.currentTimeMillis()) Button(onClick = { set { it.copy(processingPausedUntil = 0) } }) { Text("Resume processing") }
                    else {
                        AssistChip(onClick = { set { it.copy(processingPausedUntil = System.currentTimeMillis() + 30 * 60_000L) } }, label = { Text("Pause processing 30 min") })
                        AssistChip(onClick = { set { it.copy(processingPausedUntil = System.currentTimeMillis() + 2 * 3_600_000L) } }, label = { Text("2 hours") })
                        AssistChip(onClick = { set { it.copy(processingPausedUntil = Long.MAX_VALUE) } }, label = { Text("Until I resume") })
                    }
                    AssistChip(onClick = { g.capture.processNow(); g.brain.processNow() }, label = { Text("Process now") })
                    if (System.currentTimeMillis() < g.governor.ignoreLimitsUntil) AssistChip(onClick = { g.governor.ignoreLimitsUntil = 0; g.governor.refresh() }, label = { Text("Respect limits again") })
                    else AssistChip(onClick = { override = true }, label = { Text("Ignore limits for 15 min") })
                }
                Text("Listening never stops for these limits — only transcription and AI wait.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                SectionHeader("Heat limits")
                ZonedSlider("Pause when the battery reaches", s.maxBatteryTempC.toFloat(), 25f..55f, TEMP_ZONES, { "${it.toInt()}°C" }, recommended = 40f, now = p.tempC) { v -> set { it.copy(maxBatteryTempC = v.toInt()) } }
                ZonedSlider("Resume once it's cooler by", s.coolDownC.toFloat(), 0f..8f, listOf(
                    Zone(0f, Level.CAUTION, "No margin", "Work switches on and off right at the limit."),
                    Zone(3f, Level.OK, "Balanced (recommended 2°C)", "Waits until the phone has really cooled before resuming."),
                    Zone(8f, Level.SAFE, "Long cool-down", "Waits for a big drop: cooler phone, longer pauses."),
                ), { "${it.toInt()}°C" }, recommended = 2f) { v -> set { it.copy(coolDownC = v.toInt()) } }
                ZonedSlider("Android's heat level that pauses work", s.thermalPauseAt.toFloat(), 1f..4f, thermalZones(), { Governor.thermalName(it.toInt()) }, recommended = 2f, now = p.thermal.toFloat().coerceIn(1f, 4f).takeIf { p.thermal > 0 }) { v -> set { it.copy(thermalPauseAt = v.toInt()) } }
                ZonedSlider("Pause at Android's heat forecast", s.headroomLimit, 0.5f..1.3f, headroomZones(), { if (it > 1.2f) "ignored" else "${(it * 100).toInt()}%" }, recommended = 0.85f, now = p.headroom, step = 0.05f) { v -> set { it.copy(headroomLimit = v) } }
            }
            item {
                SectionHeader("Battery")
                ZonedSlider("Stop processing below", s.minBatteryPct.toFloat(), 0f..80f, batteryZones(), { "${it.toInt()}%" }, recommended = 20f, now = p.percent.toFloat()) { v -> set { it.copy(minBatteryPct = v.toInt()) } }
                SwitchRow("Process only while charging", "Listening still runs all day", s.onlyWhileCharging) { v -> set { it.copy(onlyWhileCharging = v) } }
                SwitchRow("Pause while Battery Saver is on", null, s.pauseOnBatterySaver) { v -> set { it.copy(pauseOnBatterySaver = v) } }
            }
            item {
                SectionHeader("How hard it works")
                ZonedSlider("Working time on battery", s.utilization.toFloat(), 10f..100f, useZones(), { "${it.toInt()}%" }, recommended = 60f, step = 5f) { v -> set { it.copy(utilization = v.toInt()) } }
                ZonedSlider("Working time while charging", s.chargingUtilization.toFloat(), 10f..100f, useZones(), { "${it.toInt()}%" }, recommended = 80f, step = 5f) { v -> set { it.copy(chargingUtilization = v.toInt()) } }
                ZonedSlider("CPU threads on battery", s.threadsBattery.toFloat(), 1f..8f, threadZones(), { "${it.toInt()}" }, recommended = 2f) { v -> set { it.copy(threadsBattery = v.toInt()) } }
                ZonedSlider("CPU threads while charging", s.threadsCharging.toFloat(), 1f..8f, threadZones(), { "${it.toInt()}" }, recommended = 4f) { v -> set { it.copy(threadsCharging = v.toInt()) } }
                ZonedSlider("Take a break after", s.breakAfterMin.toFloat(), 0f..90f, breakZones(), { if (it < 1) "never" else "${it.toInt()} min" }, recommended = 20f, step = 5f) { v -> set { it.copy(breakAfterMin = v.toInt()) } }
                if (s.breakAfterMin > 0) ZonedSlider("Break length", s.breakMin.toFloat(), 1f..15f, listOf(
                    Zone(2f, Level.CAUTION, "Short", "Barely lets heat escape."),
                    Zone(7f, Level.OK, "Balanced (recommended 5 min)", "Enough for the phone to cool a little."),
                    Zone(15f, Level.SAFE, "Long", "Cooler phone, slower catch-up."),
                ), { "${it.toInt()} min" }, recommended = 5f) { v -> set { it.copy(breakMin = v.toInt()) } }
                TextButton(onClick = {
                    set { it.copy(maxBatteryTempC = 40, coolDownC = 2, thermalPauseAt = 2, headroomLimit = 0.85f, minBatteryPct = 20, utilization = 60, chargingUtilization = 80, threadsBattery = 2, threadsCharging = 4, breakAfterMin = 20, breakMin = 5, pauseOnBatterySaver = false) }
                }) { Text("Reset all to recommended") }
            }
        }
    }
    if (override) AlertDialog(
        onDismissRequest = { override = false },
        title = { Text("Ignore the limits for 15 minutes?") },
        text = { Text("Transcription and AI run even if the phone is hot or the battery is low. Android's own emergency heat level still stops work. Use it to catch up quickly — ideally on the charger, out of your pocket.") },
        confirmButton = { TextButton(onClick = { override = false; g.governor.ignoreLimitsUntil = System.currentTimeMillis() + 15 * 60_000L; g.governor.refresh(); g.capture.processNow(); g.brain.processNow() }) { Text("Ignore for 15 min", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { override = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Gauge(label: String, value: String, hint: String, color: Color, modifier: Modifier) {
    EchoCard(modifier = modifier, padding = PaddingValues(Sp.m)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 2)
    }
}

@Composable
private fun TempChart(h: List<Governor.Sample>, limit: Float) {
    val line = MaterialTheme.colorScheme.primary
    val lim = MaterialTheme.colorScheme.error
    val grid = MaterialTheme.colorScheme.outlineVariant
    val lo = minOf(h.minOf { it.tempC } - 1, limit - 6)
    val hi = maxOf(h.maxOf { it.tempC } + 1, limit + 2)
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        fun y(t: Float) = size.height * (1 - (t - lo) / (hi - lo))
        val t0 = h.first().at; val span = (h.last().at - t0).coerceAtLeast(1)
        drawLine(lim, Offset(0f, y(limit)), Offset(size.width, y(limit)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        h.zipWithNext { a, b ->
            drawLine(line, Offset(size.width * (a.at - t0) / span, y(a.tempC)), Offset(size.width * (b.at - t0) / span, y(b.tempC)), 4f)
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("${"%.0f".format(lo)}–${"%.0f".format(hi)}°C", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
        Text("- - limit ${limit.toInt()}°C", style = MaterialTheme.typography.labelSmall, color = lim)
    }
}

/** What's happening right now: listening, processing or paused (and why), and the phone's heat. Tap for Phone health. */
@Composable
fun StatusCard(big: Boolean = false) {
    val g = App.graph
    val nav = LocalNav.current
    val capture by g.capture.state.collectAsStateWithLifecycle()
    val brain by g.brain.state.collectAsStateWithLifecycle()
    val gov by g.governor.state.collectAsStateWithLifecycle()
    val tr by g.transcriber.status.collectAsStateWithLifecycle()
    val ai by g.ai.running.collectAsStateWithLifecycle()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val pending by remember { g.db.audio().pendingCount() }.collectAsStateWithLifecycle(0)
    val ready by remember { g.db.conversations().readyCount() }.collectAsStateWithLifecycle(0)
    // Keeps the reading fresh while it's on screen.
    LaunchedEffect(Unit) { while (true) { g.governor.refresh(); delay(15_000) } }
    val p = gov.power
    val zone = TEMP_ZONES.firstOrNull { p.tempC <= it.to } ?: TEMP_ZONES.last()
    val (level, title) = when {
        !gov.allowed && (pending > 0 || ready > 0) -> Level.CAUTION to gov.reason.ifBlank { "Waiting" }
        brain.phase == Brain.Phase.TRANSCRIBING -> Level.OK to "Transcribing ${if (tr.source == "call") "a call" else "speech"}${if (tr.pieces > 0) " · ${tr.piece}/${tr.pieces}" else ""}"
        brain.phase == Brain.Phase.THINKING || ai != null -> Level.OK to "AI: ${ai?.purpose ?: brain.detail}"
        brain.phase == Brain.Phase.JOURNAL -> Level.OK to brain.detail
        pending > 0 || ready > 0 -> Level.CAUTION to "Waiting: $pending recordings, $ready conversations"
        else -> Level.SAFE to "All caught up"
    }
    val listening = when (capture.mode) {
        CaptureController.Mode.LISTENING -> "● Listening"; CaptureController.Mode.PAUSED -> "Listening paused"; CaptureController.Mode.BLOCKED_CALL -> "On a call"
        CaptureController.Mode.QUIET -> "Quiet hours"; CaptureController.Mode.BLOCKED_APP -> "Mic busy"; else -> "Not listening"
    }
    EchoCard(onClick = if (big) null else ({ nav.go(HealthRoute) }), padding = PaddingValues(Sp.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Thermostat, null, tint = levelColor(zone.level))
            Spacer(Modifier.width(Sp.s))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (level == Level.CAUTION) levelColor(Level.CAUTION) else MaterialTheme.colorScheme.onSurface, maxLines = 2)
                Text("$listening · ${"%.0f".format(p.tempC)}°C ${zone.label.substringBefore(" —").lowercase()} · ${p.percent}%${if (p.charging) " ⚡" else ""}" +
                    if (s.processingPausedUntil > System.currentTimeMillis()) " · processing paused" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!big) TextButton(onClick = { nav.go(HealthRoute) }) { Text("Health") }
        }
    }
}
