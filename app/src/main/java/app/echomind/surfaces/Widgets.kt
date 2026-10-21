package app.echomind.surfaces

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.echomind.App
import app.echomind.MainActivity
import app.echomind.ai.Prompts
import app.echomind.capture.CaptureController
import app.echomind.capture.CaptureService
import app.echomind.tts.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private fun open(context: Context, route: String? = null): Action =
    actionStartActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).apply { route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) } }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))

private fun service(context: Context, action: String, minutes: Long = 0): Action =
    // A widget tap is one of the few ways Android lets an app start the microphone from the background.
    actionStartService(Intent(context, CaptureService::class.java).setAction(action).putExtra(CaptureService.EXTRA_MINUTES, minutes), isForegroundService = true)

@Composable
private fun Chip(label: String, action: Action, primary: Boolean = false) {
    Text(
        label,
        style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, color = if (primary) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSecondaryContainer),
        modifier = GlanceModifier.background(if (primary) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.secondaryContainer).cornerRadius(16.dp)
            .padding(horizontal = 12.dp, vertical = 7.dp).clickable(action),
    )
}

@Composable
private fun Card(context: Context, route: String?, content: @Composable () -> Unit) {
    Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(14.dp).clickable(open(context, route))) { content() }
}

@Composable
private fun Title(text: String, color: ColorProvider = GlanceTheme.colors.onSurface) =
    Text(text, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = color), maxLines = 1)

@Composable
private fun Line(text: String, color: ColorProvider = GlanceTheme.colors.onSurfaceVariant, size: Int = 13) =
    Text(text, style = TextStyle(fontSize = size.sp, color = color), maxLines = 2)

private fun todayStart() = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Listening: status, how long today, phone heat, and start / pause / stop. */
class ListenWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val g = App.graph
        val mode = g.capture.state.value.mode
        val p = g.governor.read()
        val now = System.currentTimeMillis()
        val minutes = (g.db.audio().overlapping(todayStart(), now).filter { it.source == "mic" }.sumOf { minOf(it.endedAt, now) - maxOf(it.startedAt, todayStart()) } / 60_000).toInt()
        provideContent {
            GlanceTheme {
                Card(context, null) {
                    Title(when (mode) {
                        CaptureController.Mode.LISTENING -> "● Listening"
                        CaptureController.Mode.PAUSED -> "Paused"
                        CaptureController.Mode.BLOCKED_CALL -> "On a call"
                        CaptureController.Mode.BLOCKED_APP -> "Mic busy"
                        CaptureController.Mode.QUIET -> "Quiet hours"
                        CaptureController.Mode.PROCESSING -> "Catching up"
                        else -> "Not listening"
                    }, if (mode == CaptureController.Mode.LISTENING) GlanceTheme.colors.error else GlanceTheme.colors.onSurface)
                    Line("${minutes / 60}h ${minutes % 60}m today · ${"%.0f".format(p.tempC)}°C · ${p.percent}%")
                    Spacer(GlanceModifier.height(8.dp))
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        when (mode) {
                            CaptureController.Mode.LISTENING -> {
                                Chip("Pause 30m", service(context, CaptureService.ACTION_PAUSE, 30)); Spacer(GlanceModifier.width(6.dp))
                                Chip("Stop", service(context, CaptureService.ACTION_STOP))
                            }
                            CaptureController.Mode.PAUSED -> { Chip("Resume", service(context, CaptureService.ACTION_RESUME), primary = true); Spacer(GlanceModifier.width(6.dp)); Chip("Stop", service(context, CaptureService.ACTION_STOP)) }
                            else -> Chip("Start listening", service(context, CaptureService.ACTION_START), primary = true)
                        }
                    }
                }
            }
        }
    }
}

/** One-tap actions: ask, task, note, brief, check-in. */
class QuickWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Row(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("Ask", open(context, "ask"), primary = true); Spacer(GlanceModifier.width(6.dp))
                    Chip("+ Task", actionStartActivity(Intent(context, QuickAddActivity::class.java))); Spacer(GlanceModifier.width(6.dp))
                    Chip("Note", open(context, "note:new")); Spacer(GlanceModifier.width(6.dp))
                    Chip("Brief", actionStartService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_BRIEF), isForegroundService = true)); Spacer(GlanceModifier.width(6.dp))
                    Chip("Check-in", open(context, "checkin"))
                }
            }
        }
    }
}

/** Pinned first, then the most recently changed notes. */
class NotesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val notes = App.graph.db.notes().all().filter { !it.archived }.sortedWith(compareByDescending<app.echomind.data.Note> { it.pinned }.thenByDescending { it.updatedAt }).take(5)
        provideContent {
            GlanceTheme {
                Card(context, "notes") {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Title("Notes")
                        Spacer(GlanceModifier.defaultWeight())
                        Chip("+ New", open(context, "note:new"))
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    if (notes.isEmpty()) Line("No notes yet")
                    notes.forEach { n ->
                        Column(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(open(context, "note:${n.id}"))) {
                            Text((if (n.pinned) "📌 " else "") + n.title, style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, color = GlanceTheme.colors.onSurface), maxLines = 1)
                            Line(n.body.lines().firstOrNull { it.isNotBlank() }?.trimStart('-', '#', ' ', '*').orEmpty(), size = 12)
                        }
                    }
                }
            }
        }
    }
}

/** Your day: focus, next event, what's left, talk time. */
class DayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val g = App.graph
        val now = System.currentTimeMillis()
        val plan = runCatching { g.plans.get("day") }.getOrNull()
        val next = runCatching { g.agenda.range(now, now + 36 * 3_600_000L).firstOrNull { it.status == "confirmed" } }.getOrNull()
        val end = todayStart() + 86_400_000L
        val left = g.db.tasks().live().count { it.status == "open" && it.parentId == null && it.dueAt != null && it.dueAt < end }
        val done = g.db.tasks().completedIn(todayStart(), now).size
        val st = runCatching { g.stats.day(LocalDate.now()) }.getOrNull()
        provideContent {
            GlanceTheme {
                Card(context, "dashboard") {
                    Title("Today")
                    Line(plan?.focus?.takeIf { it.isNotBlank() }?.let { "Focus: $it" } ?: "No plan yet — tap Plan in the app", GlanceTheme.colors.onSurface)
                    Spacer(GlanceModifier.height(6.dp))
                    next?.let { Line("Next: ${if (it.allDay) "" else Prompts.time(it.startAt) + " "}${it.title}") }
                    Line("$left task${if (left == 1) "" else "s"} left · $done done")
                    st?.let { Line("${it.talkMin} min talk · you ${it.meShare}%${it.productivity?.let { p -> " · productivity $p" } ?: ""}") }
                }
            }
        }
    }
}

class ListenWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ListenWidget() }
class QuickWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = QuickWidget() }
class NotesWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = NotesWidget() }
class DayWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = DayWidget() }

/** Keeps the widgets current: listening state, tasks, notes and plans, a couple of seconds after they change. */
object WidgetUpdater {
    @OptIn(FlowPreview::class)
    fun start(context: Context, scope: CoroutineScope) {
        val g = App.graph
        scope.launch {
            g.capture.state.map { it.mode }.distinctUntilChanged().collect { runCatching { ListenWidget().updateAll(context); EchoWidget().updateAll(context) } }
        }
        scope.launch {
            combine(g.db.tasks().observeOpen(), g.db.notes().observeAll(), g.plans.observe("day")) { a, b, c -> Triple(a.size, b.maxOfOrNull { it.updatedAt }, c) }
                .debounce(2_000).collect {
                    runCatching { EchoWidget().updateAll(context); NotesWidget().updateAll(context); DayWidget().updateAll(context) }
                }
        }
    }
}
