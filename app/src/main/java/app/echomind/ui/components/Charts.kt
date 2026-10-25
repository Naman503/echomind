package app.echomind.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.echomind.brain.Languages
import app.echomind.brain.Languages.Lang
import app.echomind.ui.theme.Sp

/** One share of a whole: a value, its colour and a label for the legend. */
data class Slice(val value: Float, val color: Color, val label: String = "")

/** Colours for the three languages, readable in light and dark. */
@Composable
fun langColor(l: Lang): Color {
    val dark = isSystemInDarkTheme()
    return when (l) {
        Lang.HINDI -> if (dark) Color(0xFFFFB066) else Color(0xFFE07A1F)
        Lang.ENGLISH -> if (dark) Color(0xFF8C9EFF) else Color(0xFF3D5AFE)
        Lang.HINGLISH -> if (dark) Color(0xFF5FD4C4) else Color(0xFF0F8A7E)
    }
}

/** 0 → 1 once, when the chart first shows. */
@Composable
private fun grow(): Float {
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { target = 1f }
    return animateFloatAsState(target, tween(700), label = "grow").value
}

/** A ring split into [slices], with [center] drawn in the middle. */
@Composable
fun Donut(slices: List<Slice>, modifier: Modifier = Modifier, size: Dp = 112.dp, thickness: Dp = 14.dp, center: @Composable () -> Unit = {}) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val t = grow()
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = thickness.toPx(); val inset = w / 2
            val arc = Size(this.size.width - w, this.size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            if (total <= 0f) return@Canvas
            var start = -90f
            val gap = if (slices.count { it.value > 0 } > 1) 2f else 0f
            slices.filter { it.value > 0 }.forEach { s ->
                val sweep = s.value / total * 360f * t
                drawArc(s.color, start + gap / 2, (sweep - gap).coerceAtLeast(0.5f), false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Butt))
                start += sweep
            }
        }
        center()
    }
}

/** A progress ring (0..1) with a value in the middle. */
@Composable
fun Ring(fraction: Float, color: Color, modifier: Modifier = Modifier, size: Dp = 56.dp, thickness: Dp = 6.dp, center: @Composable () -> Unit = {}) {
    val t = grow()
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = thickness.toPx(); val inset = w / 2
            val arc = Size(this.size.width - w, this.size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f) * t, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
        }
        center()
    }
}

/** A single bar split into shares, rounded at the ends. */
@Composable
fun StackedBar(slices: List<Slice>, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(MaterialTheme.colorScheme.surfaceContainerHighest), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (total > 0f) slices.filter { it.value > 0 }.forEach { s -> Box(Modifier.weight(s.value / total).fillMaxHeight().background(s.color)) }
    }
}

/** Coloured dots with labels, wrapping. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChartLegend(slices: List<Slice>, modifier: Modifier = Modifier, value: (Slice) -> String? = { null }) {
    androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Sp.m), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        slices.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(s.color))
                Spacer(Modifier.width(5.dp))
                Text(s.label, style = MaterialTheme.typography.labelMedium)
                value(s)?.let { Text(" $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** A small line with a soft fill under it; nulls are gaps. */
@Composable
fun Sparkline(values: List<Float?>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, height: Dp = 40.dp) {
    val t = grow()
    Canvas(modifier.fillMaxWidth().height(height)) {
        val pts = values.withIndex().filter { it.value != null }
        if (pts.size < 2) return@Canvas
        val lo = pts.minOf { it.value!! }; val hi = pts.maxOf { it.value!! }; val span = (hi - lo).takeIf { it > 0 } ?: 1f
        val step = size.width / (values.size - 1).coerceAtLeast(1)
        fun at(i: Int, v: Float) = Offset(i * step, size.height - 3f - (v - lo) / span * (size.height - 6f) * t)
        val line = Path(); val fill = Path()
        pts.forEachIndexed { k, p ->
            val o = at(p.index, p.value!!)
            if (k == 0) { line.moveTo(o.x, o.y); fill.moveTo(o.x, size.height); fill.lineTo(o.x, o.y) } else { line.lineTo(o.x, o.y); fill.lineTo(o.x, o.y) }
        }
        fill.lineTo(pts.last().index * step, size.height); fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, 3.5.dp.toPx(), at(pts.last().index, pts.last().value!!))
    }
}

/** Vertical bars, each split into coloured shares (e.g. minutes per language, per hour). */
@Composable
fun StackedColumns(columns: List<List<Slice>>, labels: List<String>, modifier: Modifier = Modifier, height: Dp = 88.dp, highlight: Int = -1) {
    val max = columns.maxOfOrNull { c -> c.sumOf { it.value.toDouble() } }?.toFloat()?.takeIf { it > 0 } ?: 1f
    val t = grow()
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            columns.forEachIndexed { i, c ->
                val sum = c.sumOf { it.value.toDouble() }.toFloat()
                Column(
                    Modifier.weight(1f).fillMaxHeight((sum / max * t).coerceIn(0.03f, 1f)).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .then(if (i == highlight) Modifier.background(MaterialTheme.colorScheme.outlineVariant) else Modifier),
                ) {
                    if (sum <= 0f) Box(Modifier.fillMaxWidth().weight(1f).background(empty))
                    else c.filter { it.value > 0 }.reversed().forEach { s -> Box(Modifier.fillMaxWidth().weight(s.value / sum).background(s.color)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1) }
        }
    }
}

/** An icon in a tinted circle, a big value and a label: the building block for number rows. */
@Composable
fun MetricChip(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary, hint: String? = null, hintColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Sp.m)) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(value, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = hintColor, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A labelled horizontal bar for ranked lists (people, words, places). */
@Composable
fun RankBar(label: String, value: Float, max: Float, trailing: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, slices: List<Slice>? = null) {
    Column(modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val f = (value / max.coerceAtLeast(0.0001f)).coerceIn(0.02f, 1f) * grow()
        Box(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.01f))) {
                if (slices != null) StackedBar(slices, height = 8.dp)
                else Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
            }
        }
    }
}

// ── Languages ──

fun langSlices(s: Languages.Split, colors: Map<Lang, Color>): List<Slice> = Lang.entries.map { Slice(s[it].toFloat(), colors.getValue(it), it.label) }

@Composable
fun langColors(): Map<Lang, Color> = Lang.entries.associateWith { langColor(it) }

private fun mins(sec: Int) = when { sec <= 0 -> "0m"; sec < 60 -> "<1m"; sec < 3600 -> "${sec / 60}m"; else -> "${sec / 3600}h ${sec % 3600 / 60}m" }

/**
 * The language card: a donut of your minutes per language, the hours you spoke them,
 * people by language, switches and vocabulary. Used by Day review and the Dashboard.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LanguageCard(day: Languages.Day, title: String = "Languages you spoke", hourly: Boolean = true) {
    val c = langColors()
    val mine = langSlices(day.mine, c)
    EchoCard {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (day.mine.total == 0) {
            Text("No lines of yours to measure yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@EchoCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Sp.s)) {
            Donut(mine, size = 104.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val main = day.mine.main
                    Text(main?.let { "${day.mine.pct(it)}%" } ?: "–", style = MaterialTheme.typography.titleLarge)
                    Text(main?.label ?: "", style = MaterialTheme.typography.labelSmall, color = main?.let { c.getValue(it) } ?: MaterialTheme.colorScheme.outline)
                }
            }
            Column(Modifier.padding(start = Sp.l).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Lang.entries.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c.getValue(l)))
                        Text(l.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp).weight(1f))
                        Text(mins(day.mine[l]), style = MaterialTheme.typography.labelLarge)
                        Text("${day.mine.pct(l)}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                    }
                }
            }
        }
        if (day.others.total > 0) {
            Text("Others around you", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Sp.m, bottom = 4.dp))
            StackedBar(langSlices(day.others, c), height = 10.dp)
        }
        if (hourly && day.byHour.any { it.total > 0 }) {
            val first = day.byHour.indexOfFirst { it.total > 0 }.coerceAtMost(8); val last = day.byHour.indexOfLast { it.total > 0 }.coerceAtLeast(20)
            val hours = (first..last).toList()
            Text("Through the day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Sp.m, bottom = 4.dp))
            StackedColumns(hours.map { h -> langSlices(day.byHour[h], c)}, hours.map { h -> if (h % 3 == 0) app.echomind.brain.Whereabouts.hourName(h).replace(" ", "").lowercase().removeSuffix("m") else "" }, height = 64.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s), modifier = Modifier.padding(top = Sp.m)) {
            Pill("${day.switches} switch${if (day.switches == 1) "" else "es"}")
            Pill("${day.vocabulary} different words")
        }
        val people = day.byPerson.entries.filter { it.value.total >= 20 }.sortedByDescending { it.value.total }.take(5)
        if (people.isNotEmpty()) {
            Text("With people", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Sp.m))
            val max = people.first().value.total.toFloat()
            people.forEach { (name, s) -> RankBar(name, s.total.toFloat(), max, "${s.main?.label ?: ""} · ${mins(s.total)}", slices = langSlices(s, c)) }
        }
        if (day.topWords.isNotEmpty()) {
            Text("Your words", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Sp.m, bottom = 4.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                day.topWords.take(10).forEach { (w, n) -> Pill(if (n > 1) "$w ×$n" else w) }
            }
        }
    }
}
