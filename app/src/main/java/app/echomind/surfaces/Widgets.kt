package app.echomind.surfaces

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.echomind.App
import app.echomind.MainActivity
import app.echomind.R
import app.echomind.ai.Prompts
import app.echomind.capture.CaptureController
import app.echomind.capture.CaptureService
import app.echomind.tts.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

// ── per-widget personalisation ──

/** Each placed widget's own choices (style, opacity, what it shows), by widget id. */
object WidgetPrefs {
    private fun p(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    fun get(c: Context, id: Int, key: String, def: String) = p(c).getString("$id:$key", def) ?: def
    fun bool(c: Context, id: Int, key: String, def: Boolean) = p(c).getBoolean("$id:$key", def)
    fun int(c: Context, id: Int, key: String, def: Int) = p(c).getInt("$id:$key", def)
    fun set(c: Context, id: Int, key: String, v: Any) = p(c).edit().apply {
        when (v) { is Boolean -> putBoolean("$id:$key", v); is Int -> putInt("$id:$key", v); else -> putString("$id:$key", v.toString()) }
    }.apply()
}

/** Style: auto (wallpaper colours) | light | dark | glass (see-through, opacity in %). */
data class Look(val style: String = "auto", val opacity: Int = 70)

private suspend fun look(context: Context, id: GlanceId): Pair<Int, Look> {
    val wid = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(-1)
    return wid to Look(WidgetPrefs.get(context, wid, "style", "auto"), WidgetPrefs.int(context, wid, "opacity", 70))
}

@Composable
private fun Themed(look: Look, content: @Composable () -> Unit) {
    when (look.style) {
        "light" -> GlanceTheme(colors = ColorProviders(app.echomind.ui.theme.Light)) { content() }
        "dark", "glass" -> GlanceTheme(colors = ColorProviders(app.echomind.ui.theme.Dark)) { content() }
        else -> GlanceTheme { content() }
    }
}

@Composable
private fun Frame(look: Look, onClick: Action?, padding: Int = 14, content: @Composable () -> Unit) {
    val bg = if (look.style == "glass") ColorProvider(Color.Black.copy(alpha = look.opacity / 100f)) else GlanceTheme.colors.widgetBackground
    Box(GlanceModifier.fillMaxSize().background(bg).cornerRadius(24.dp).padding(padding.dp).let { if (onClick != null) it.clickable(onClick) else it }) { content() }
}

// ── building blocks ──

private fun openApp(context: Context, route: String? = null): Action =
    actionStartActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).apply { route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) } }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))

// A widget tap is one of the few ways Android lets an app start the microphone from the background.
private fun capture(context: Context, action: String, minutes: Long = 0): Action =
    actionStartService(Intent(context, CaptureService::class.java).setAction(action).putExtra(CaptureService.EXTRA_MINUTES, minutes), isForegroundService = true)

private fun brief(context: Context): Action =
    actionStartService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_BRIEF), isForegroundService = true)

@Composable
private fun Icon(res: Int, tint: ColorProvider = GlanceTheme.colors.onSurfaceVariant, size: Int = 18) =
    Image(ImageProvider(res), null, GlanceModifier.size(size.dp), colorFilter = ColorFilter.tint(tint))

@Composable
private fun Header(icon: Int, title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, GlanceTheme.colors.primary, 18)
        Spacer(GlanceModifier.width(8.dp))
        Text(title, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = GlanceTheme.colors.onSurface), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        trailing?.invoke()
    }
}

@Composable
private fun Small(text: String, color: ColorProvider = GlanceTheme.colors.onSurfaceVariant, size: Int = 12, lines: Int = 1) =
    Text(text, style = TextStyle(fontSize = size.sp, color = color), maxLines = lines)

@Composable
private fun RoundButton(icon: Int, label: String, action: Action, primary: Boolean = false, size: Int = 44) {
    CircleIconButton(ImageProvider(icon), label, action, GlanceModifier.size(size.dp),
        backgroundColor = if (primary) GlanceTheme.colors.primary else GlanceTheme.colors.secondaryContainer,
        contentColor = if (primary) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSecondaryContainer)
}

private fun todayStart() = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
private fun tempWord(t: Float) = when { t < 35 -> "cool"; t < 40 -> "warm"; t < 45 -> "hot"; else -> "very hot" }

// ── Status & controls ──

/** What it's doing (listening, processing, paused and why), the phone's heat, and the controls. */
class ListenWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    data class Snap(val mode: CaptureController.Mode, val doing: String, val waiting: Boolean, val temp: Float, val battery: Int, val charging: Boolean, val minutes: Int, val queue: Int, val pausedProcessing: Boolean)

    private suspend fun snap(): Snap {
        val g = App.graph
        val p = g.governor.read()
        val now = System.currentTimeMillis()
        val b = g.brain.state.value
        val t = g.transcriber.status.value
        val gov = g.governor.state.value
        val queue = g.db.audio().dueCount() + g.db.conversations().ready(50).size
        val doing = when {
            !gov.allowed && queue > 0 -> gov.reason
            b.phase == app.echomind.brain.Brain.Phase.TRANSCRIBING -> "Transcribing${if (t.pieces > 0) " ${t.piece}/${t.pieces}" else ""}"
            b.phase == app.echomind.brain.Brain.Phase.THINKING -> "AI: ${g.ai.running.value?.purpose ?: b.detail}"
            b.phase == app.echomind.brain.Brain.Phase.JOURNAL || b.phase == app.echomind.brain.Brain.Phase.REVIEW -> b.detail
            queue > 0 -> "$queue waiting to process"
            else -> "All caught up"
        }
        val minutes = (g.db.audio().overlapping(todayStart(), now).filter { it.source == "mic" }.sumOf { minOf(it.endedAt, now) - maxOf(it.startedAt, todayStart()) } / 60_000).toInt()
        return Snap(g.capture.state.value.mode, doing, !gov.allowed && queue > 0, p.tempC, p.percent, p.charging, minutes, queue, g.settings.value.processingPausedUntil > now)
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (wid, lk) = look(context, id)
        val s = snap()
        val showHealth = WidgetPrefs.bool(context, wid, "health", true)
        provideContent { Themed(lk) { Content(context, lk, s, showHealth) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Themed(Look()) { Content(context, Look(), Snap(CaptureController.Mode.LISTENING, "Transcribing 2/5", false, 36.4f, 72, false, 135, 3, false), true) } }
    }

    @Composable
    private fun Content(context: Context, lk: Look, s: Snap, showHealth: Boolean) {
        val size = LocalSize.current
        val listening = s.mode == CaptureController.Mode.LISTENING
        val state = when (s.mode) {
            CaptureController.Mode.LISTENING -> "Listening"; CaptureController.Mode.PAUSED -> "Paused"; CaptureController.Mode.BLOCKED_CALL -> "On a call"
            CaptureController.Mode.BLOCKED_APP -> "Mic busy"; CaptureController.Mode.QUIET -> "Quiet hours"; CaptureController.Mode.PROCESSING -> "Catching up"; else -> "Not listening"
        }
        val main: @Composable () -> Unit = {
            when (s.mode) {
                CaptureController.Mode.LISTENING -> RoundButton(R.drawable.ic_w_pause, "Pause 30 minutes", capture(context, CaptureService.ACTION_PAUSE, 30))
                CaptureController.Mode.PAUSED -> RoundButton(R.drawable.ic_w_play_arrow, "Resume", capture(context, CaptureService.ACTION_RESUME), primary = true)
                else -> RoundButton(R.drawable.ic_w_mic, "Start listening", capture(context, CaptureService.ACTION_START), primary = true)
            }
        }
        // Small (about 2×1): one line of status, the heat, and the main button beside them.
        if (size.height < 130.dp) {
            Frame(lk, openApp(context, "health"), padding = 10) {
                Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(GlanceModifier.size(8.dp).cornerRadius(4.dp).background(if (listening) GlanceTheme.colors.error else GlanceTheme.colors.outline)) {}
                            Text("  $state", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = GlanceTheme.colors.onSurface), maxLines = 1)
                        }
                        Small(if (s.pausedProcessing) "Processing paused" else s.doing, if (s.waiting || s.pausedProcessing) GlanceTheme.colors.tertiary else GlanceTheme.colors.onSurfaceVariant, 12)
                        if (showHealth) Small("${"%.0f".format(s.temp)}°C ${tempWord(s.temp)} · ${s.battery}%${if (s.charging) " ⚡" else ""}", size = 11)
                    }
                    main()
                }
            }
            return
        }
        Frame(lk, openApp(context, "health")) {
            Column(GlanceModifier.fillMaxSize()) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(GlanceModifier.size(10.dp).cornerRadius(5.dp).background(if (listening) GlanceTheme.colors.error else GlanceTheme.colors.outline)) {}
                    Spacer(GlanceModifier.width(8.dp))
                    Text(state, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onSurface), modifier = GlanceModifier.defaultWeight())
                    Small("${s.minutes / 60}h ${s.minutes % 60}m")
                }
                Spacer(GlanceModifier.height(4.dp))
                Small(if (s.pausedProcessing) "Processing paused by you" else s.doing, if (s.waiting || s.pausedProcessing) GlanceTheme.colors.tertiary else GlanceTheme.colors.onSurfaceVariant, 13, 2)
                if (showHealth) Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.padding(top = 4.dp)) {
                    Icon(R.drawable.ic_w_thermostat, if (s.temp >= 42) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant, 14)
                    Small(" ${"%.0f".format(s.temp)}°C ${tempWord(s.temp)} · ${s.battery}%${if (s.charging) " ⚡" else ""}")
                }
                Spacer(GlanceModifier.defaultWeight())
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    when (s.mode) {
                        CaptureController.Mode.LISTENING -> { RoundButton(R.drawable.ic_w_pause, "Pause 30 minutes", capture(context, CaptureService.ACTION_PAUSE, 30)); Spacer(GlanceModifier.width(8.dp)); RoundButton(R.drawable.ic_w_stop, "Stop", capture(context, CaptureService.ACTION_STOP)) }
                        CaptureController.Mode.PAUSED -> { RoundButton(R.drawable.ic_w_play_arrow, "Resume", capture(context, CaptureService.ACTION_RESUME), primary = true); Spacer(GlanceModifier.width(8.dp)); RoundButton(R.drawable.ic_w_stop, "Stop", capture(context, CaptureService.ACTION_STOP)) }
                        else -> RoundButton(R.drawable.ic_w_mic, "Start listening", capture(context, CaptureService.ACTION_START), primary = true)
                    }
                    if (size.width >= 200.dp) {
                        Spacer(GlanceModifier.width(8.dp)); RoundButton(R.drawable.ic_w_sync, "Process now", capture(context, CaptureService.ACTION_PROCESS))
                        Spacer(GlanceModifier.width(8.dp)); RoundButton(R.drawable.ic_w_thermostat, "Phone health", openApp(context, "health"))
                    }
                }
            }
        }
    }
}

// ── Mic button ──

/** One big button: listening on or off, red while listening. */
class MicWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (_, lk) = look(context, id)
        val mode = App.graph.capture.state.value.mode
        provideContent { Themed(lk) { Content(context, lk, mode) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Themed(Look()) { Content(context, Look(), CaptureController.Mode.LISTENING) } }
    }

    @Composable
    private fun Content(context: Context, lk: Look, mode: CaptureController.Mode) {
        val on = mode == CaptureController.Mode.LISTENING
        val paused = mode == CaptureController.Mode.PAUSED
        Frame(lk, if (on) capture(context, CaptureService.ACTION_PAUSE, 30) else if (paused) capture(context, CaptureService.ACTION_RESUME) else capture(context, CaptureService.ACTION_START), padding = 8) {
            Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.size(48.dp).cornerRadius(24.dp).background(if (on) GlanceTheme.colors.error else GlanceTheme.colors.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(if (on) R.drawable.ic_w_pause else R.drawable.ic_w_mic, if (on) GlanceTheme.colors.onError else GlanceTheme.colors.onPrimaryContainer, 26)
                }
                Spacer(GlanceModifier.height(4.dp))
                Small(if (on) "Listening" else if (paused) "Paused" else "Listen", GlanceTheme.colors.onSurface, 11)
            }
        }
    }
}

// ── Today's tasks ──

private val TaskKey = ActionParameters.Key<Long>("task")

/** Ticks a task off straight from the home screen. */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskKey] ?: return
        val g = App.graph
        g.db.tasks().get(id)?.let { g.tasks.complete(it) }
        TasksWidget().updateAll(context)
    }
}

/** Today's tasks (or all open, or overdue) to tick off, with a + to add. */
class TasksWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    data class Row1(val id: Long, val title: String, val meta: String?, val late: Boolean)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (wid, lk) = look(context, id)
        val g = App.graph
        val which = WidgetPrefs.get(context, wid, "tasks", "today")
        val end = todayStart() + 86_400_000L
        val open = g.db.tasks().live().filter { it.status == "open" && it.parentId == null }
        val list = when (which) {
            "all" -> open
            "overdue" -> open.filter { it.dueAt != null && it.dueAt < todayStart() }
            else -> open.filter { (it.dueAt != null && it.dueAt < end) || (it.dueAt == null && it.createdAt >= todayStart()) }
        }.sortedWith(compareBy({ it.dueAt == null }, { it.priority }, { it.dueAt })).map { t ->
            val late = t.dueAt != null && t.dueAt < todayStart()
            val at = t.remindAt ?: t.dueAt
            val timed = at != null && java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime().let { !(it.hour == 9 && it.minute == 0) || t.remindAt != null }
            Row1(t.id, t.title, if (late) "Overdue" else if (timed) Prompts.time(at!!) else null, late)
        }
        val done = g.db.tasks().completedIn(todayStart(), System.currentTimeMillis()).size
        // Nothing due today: show what's next instead of an empty box.
        val upcoming = if (list.isEmpty() && which == "today") open.filter { it.dueAt != null && it.dueAt >= end }.sortedBy { it.dueAt }.take(5)
            .map { Row1(it.id, it.title, app.echomind.ui.Fmt.day(it.dueAt!!), false) } else emptyList()
        provideContent { Themed(lk) { Content(context, lk, list, done, which, upcoming) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Themed(Look()) { Content(context, Look(), listOf(Row1(0, "Call the CA about GST", "4:00 PM", false), Row1(0, "Send the invoice to Priya", null, false), Row1(0, "Pay electricity bill", "Overdue", true)), 2, "today") } }
    }

    @Composable
    private fun Content(context: Context, lk: Look, list: List<Row1>, done: Int, which: String, upcoming: List<Row1> = emptyList()) {
        val size = LocalSize.current
        val rows = ((size.height.value - 70) / 34).toInt().coerceIn(1, 10)
        Frame(lk, openApp(context, "tasks")) {
            Column(GlanceModifier.fillMaxSize()) {
                Header(R.drawable.ic_w_task_alt, when (which) { "all" -> "Open tasks"; "overdue" -> "Overdue"; else -> "Today" } + if (list.isNotEmpty()) " · ${list.size}" else "") {
                    RoundButton(R.drawable.ic_w_add, "Add a task", actionStartActivity(Intent(context, QuickAddActivity::class.java)), primary = true, size = 34)
                }
                if (done + list.size > 0) {
                    Spacer(GlanceModifier.height(6.dp))
                    androidx.glance.appwidget.LinearProgressIndicator(done.toFloat() / (done + list.size), GlanceModifier.fillMaxWidth().height(4.dp).cornerRadius(2.dp),
                        color = GlanceTheme.colors.primary, backgroundColor = GlanceTheme.colors.surfaceVariant)
                }
                Spacer(GlanceModifier.height(6.dp))
                if (list.isEmpty()) Small(if (done > 0) "All done — $done finished today" else "Nothing due today.", size = 13)
                if (list.isEmpty() && upcoming.isNotEmpty()) { Spacer(GlanceModifier.height(8.dp)); Small("NEXT UP", GlanceTheme.colors.primary, 10) }
                (list.ifEmpty { upcoming }).take(rows).forEach { t ->
                    Row(GlanceModifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(ImageProvider(R.drawable.ic_w_radio_button_unchecked), "Mark done", GlanceModifier.size(20.dp).clickable(actionRunCallback<CompleteTaskAction>(actionParametersOf(TaskKey to t.id))),
                            colorFilter = ColorFilter.tint(if (t.late) GlanceTheme.colors.error else GlanceTheme.colors.primary))
                        Spacer(GlanceModifier.width(10.dp))
                        Text(t.title, style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurface), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                        t.meta?.let { Small(it, if (t.late) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant) }
                    }
                }
                if (list.size > rows) Small("+${list.size - rows} more", GlanceTheme.colors.primary)
                else if (done > 0 && list.isNotEmpty()) Small("$done done today ✓", GlanceTheme.colors.primary)
            }
        }
    }
}

// ── Agenda ──

const val DEFAULT_SHOW = "focus,next,tasks,talk,chart,health"

/** Next events and timed reminders, today and tomorrow. */
class AgendaWidget : GlanceAppWidget() {
    data class Item(val time: String, val title: String, val day: String)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (_, lk) = look(context, id)
        val g = App.graph
        val now = System.currentTimeMillis()
        val to = todayStart() + 2 * 86_400_000L
        val events = runCatching { g.agenda.range(now - 30 * 60_000L, to) }.getOrDefault(emptyList()).filter { it.status == "confirmed" && (it.allDay && it.startAt >= todayStart() || (it.endAt ?: it.startAt) > now) }
            .map { it.startAt to Item(if (it.allDay) "All day" else Prompts.time(it.startAt), it.title, app.echomind.ui.Fmt.day(it.startAt)) }
        val reminders = g.db.tasks().upcomingReminders(now).filter { (it.remindAt ?: 0) < to }.map { it.remindAt!! to Item(Prompts.time(it.remindAt), "⏰ ${it.title}", app.echomind.ui.Fmt.day(it.remindAt)) }
        val items = (events + reminders).sortedBy { it.first }.map { it.second }
        provideContent { Themed(lk) { Content(context, lk, items) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Themed(Look()) { Content(context, Look(), listOf(Item("12:00 PM", "Team stand-up", "Today"), Item("4:00 PM", "⏰ Call the CA", "Today"), Item("6:00 PM", "Training session", "Tomorrow"))) } }
    }

    @Composable
    private fun Content(context: Context, lk: Look, items: List<Item>) {
        val rows = ((LocalSize.current.height.value - 60) / 36).toInt().coerceIn(1, 8)
        Frame(lk, openApp(context, "calendar")) {
            Column(GlanceModifier.fillMaxSize()) {
                Header(R.drawable.ic_w_calendar_month, "Coming up")
                Spacer(GlanceModifier.height(6.dp))
                if (items.isEmpty()) Small("Nothing on the calendar for today or tomorrow.", size = 13)
                var lastDay = ""
                items.take(rows).forEach { i ->
                    if (i.day != lastDay) { Small(i.day.uppercase(), GlanceTheme.colors.primary, 10); lastDay = i.day }
                    Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(i.time, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.primary), modifier = GlanceModifier.width(64.dp))
                        Text(i.title, style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurface), maxLines = 1)
                    }
                }
            }
        }
    }
}

// ── Notes ──

/** Pinned or recent notes, or one folder; tap to open, + for a new one. */
class NotesWidget : GlanceAppWidget() {
    data class N(val id: Long, val title: String, val preview: String, val pinned: Boolean)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (wid, lk) = look(context, id)
        val which = WidgetPrefs.get(context, wid, "notes", "recent")
        val folder = WidgetPrefs.get(context, wid, "folder", "")
        val all = App.graph.db.notes().all().filter { !it.archived }
        val notes = when (which) {
            "pinned" -> all.filter { it.pinned }
            "folder" -> all.filter { folder.isNotBlank() && (it.folder == folder || it.folder?.startsWith("$folder/") == true) }
            else -> all
        }.sortedWith(compareByDescending<app.echomind.data.Note> { it.pinned }.thenByDescending { it.updatedAt })
            .map { N(it.id, it.title, it.body.lines().firstOrNull { l -> l.isNotBlank() }?.trimStart('-', '#', ' ', '*', '[', ']').orEmpty(), it.pinned) }
        val title = when (which) { "pinned" -> "Pinned notes"; "folder" -> folder.substringAfterLast('/').ifBlank { "Notes" }; else -> "Notes" }
        provideContent { Themed(lk) { Content(context, lk, title, notes, WidgetPrefs.bool(context, wid, "preview", true)) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Themed(Look()) { Content(context, Look(), "Notes", listOf(N(0, "Goa trip", "Book flights by Friday", true), N(0, "Eazeebox launch", "Pricing page needs review", false), N(0, "Groceries", "Milk, atta, dal", false)), true) } }
    }

    @Composable
    private fun Content(context: Context, lk: Look, title: String, notes: List<N>, preview: Boolean) {
        val rows = ((LocalSize.current.height.value - 60) / (if (preview) 44 else 28)).toInt().coerceIn(1, 8)
        Frame(lk, openApp(context, "notes")) {
            Column(GlanceModifier.fillMaxSize()) {
                Header(R.drawable.ic_w_sticky_note_2, title) { RoundButton(R.drawable.ic_w_add, "New note", openApp(context, "note:new"), primary = true, size = 34) }
                Spacer(GlanceModifier.height(6.dp))
                if (notes.isEmpty()) Small("No notes here yet.", size = 13)
                notes.take(rows).forEach { n ->
                    Column(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(openApp(context, "note:${n.id}"))) {
                        Text((if (n.pinned) "📌 " else "") + n.title, style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, color = GlanceTheme.colors.onSurface), maxLines = 1)
                        if (preview && n.preview.isNotBlank()) Small(n.preview)
                    }
                }
            }
        }
    }
}

// ── Your day ──

/** Focus, next event, tasks, talk and the phone — choose which in the widget's settings. */
class DayWidget : GlanceAppWidget() {
    data class D(
        val focus: String?, val next: String?, val left: Int, val done: Int, val talk: Int, val share: Int, val productivity: Int?, val temp: Float, val missing: Int,
        /** Minutes of talk in each hour so far today, and your seconds per language. */
        val hours: List<Float> = emptyList(), val lang: app.echomind.brain.Languages.Split = app.echomind.brain.Languages.Split(),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (wid, lk) = look(context, id)
        val g = App.graph
        val now = System.currentTimeMillis()
        val plan = runCatching { g.plans.get("day") }.getOrNull()
        val next = runCatching { g.agenda.range(now, now + 36 * 3_600_000L).firstOrNull { it.status == "confirmed" } }.getOrNull()
        val end = todayStart() + 86_400_000L
        val st = runCatching { g.stats.day(LocalDate.now()) }.getOrNull()
        val d = D(plan?.focus?.takeIf { it.isNotBlank() }, next?.let { "${if (it.allDay) "" else Prompts.time(it.startAt) + " · "}${it.title}" },
            g.db.tasks().live().count { it.status == "open" && it.parentId == null && it.dueAt != null && it.dueAt < end }, g.db.tasks().completedIn(todayStart(), now).size,
            st?.talkMin ?: 0, st?.meShare ?: 0, st?.productivity, g.governor.read().tempC, plan?.missing?.size ?: 0)
            .let { d0 ->
                val today = LocalDate.now()
                val heat = runCatching { app.echomind.brain.Analytics.heatmap(app.echomind.brain.Analytics.spans(g.db, today, today))[today.dayOfWeek.value - 1] }.getOrNull()
                val lang = runCatching { app.echomind.brain.Analytics.languages(g.db, today, today).first.mine }.getOrNull()
                d0.copy(hours = heat?.let { h -> (6..23).map { h[it].toFloat() } }.orEmpty(), lang = lang ?: app.echomind.brain.Languages.Split())
            }
        val show = WidgetPrefs.get(context, wid, "show", DEFAULT_SHOW).split(',').toSet()
        provideContent { Themed(lk) { Content(context, lk, d, show) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Themed(Look()) { Content(context, Look(), D("Ship the pricing page", "12:00 PM · Team stand-up", 3, 2, 84, 46, 72, 35f, 1,
            listOf(0f, 0f, 0f, 4f, 12f, 8f, 3f, 0f, 14f, 9f, 6f, 2f, 0f, 5f, 0f, 0f, 0f, 0f), app.echomind.brain.Languages.Split(1200, 900, 600)), setOf("focus", "next", "tasks", "talk", "chart", "health")) } }
    }

    @Composable
    private fun Content(context: Context, lk: Look, d: D, show: Set<String>) {
        Frame(lk, openApp(context, "dashboard")) {
            Column(GlanceModifier.fillMaxSize()) {
                Header(R.drawable.ic_w_auto_awesome, "Today")
                Spacer(GlanceModifier.height(6.dp))
                if ("focus" in show) {
                    Text(d.focus ?: "No plan yet — tap to plan", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface), maxLines = 2)
                    if (d.missing > 0) Small("${d.missing} thing${if (d.missing == 1) "" else "s"} you might be missing", GlanceTheme.colors.tertiary)
                    Spacer(GlanceModifier.height(4.dp))
                }
                if ("next" in show) d.next?.let { Row(verticalAlignment = Alignment.CenterVertically) { Icon(R.drawable.ic_w_event, size = 14); Small("  $it", GlanceTheme.colors.onSurface, 13) } }
                Spacer(GlanceModifier.defaultWeight())
                if ("chart" in show && d.hours.any { it > 0 }) {
                    val dens = context.resources.displayMetrics.density
                    val w = ((LocalSize.current.width.value - 28) * dens).toInt().coerceAtLeast(120)
                    val nowIdx = java.time.LocalTime.now().hour - 6
                    Image(ImageProvider(ChartBitmaps.bars(w, (30 * dens).toInt(), d.hours, highlight = nowIdx)), "Talk through the day", GlanceModifier.fillMaxWidth().height(30.dp))
                    Row(GlanceModifier.fillMaxWidth()) { Small("6a", size = 9); Spacer(GlanceModifier.defaultWeight()); Small("12p", size = 9); Spacer(GlanceModifier.defaultWeight()); Small("6p", size = 9); Spacer(GlanceModifier.defaultWeight()); Small("11p", size = 9) }
                    if (d.lang.total >= 30) {
                        Spacer(GlanceModifier.height(4.dp))
                        Image(ImageProvider(ChartBitmaps.stackedBar(w, (6 * dens).toInt(), ChartBitmaps.langParts(d.lang))), "Languages today", GlanceModifier.fillMaxWidth().height(6.dp))
                        Small(app.echomind.brain.Languages.Lang.entries.filter { d.lang[it] > 0 }.joinToString(" · ") { "${it.label} ${d.lang.pct(it)}%" }, size = 10)
                    }
                    Spacer(GlanceModifier.height(6.dp))
                }
                if ("tasks" in show && d.left + d.done > 0) {
                    androidx.glance.appwidget.LinearProgressIndicator(d.done.toFloat() / (d.left + d.done), GlanceModifier.fillMaxWidth().height(4.dp).cornerRadius(2.dp),
                        color = GlanceTheme.colors.primary, backgroundColor = GlanceTheme.colors.surfaceVariant)
                    Spacer(GlanceModifier.height(6.dp))
                }
                Row(GlanceModifier.fillMaxWidth()) {
                    if ("tasks" in show) Stat("${d.left}", "left · ${d.done} done", GlanceModifier.defaultWeight())
                    if ("talk" in show) Stat("${d.talk}m", "talk · you ${d.share}%", GlanceModifier.defaultWeight())
                    if ("productivity" in show) Stat(d.productivity?.toString() ?: "–", "productivity", GlanceModifier.defaultWeight())
                    if ("health" in show) Stat("${"%.0f".format(d.temp)}°", tempWord(d.temp), GlanceModifier.defaultWeight())
                }
            }
        }
    }

    @Composable
    private fun Stat(value: String, label: String, modifier: GlanceModifier) {
        Column(modifier) {
            Text(value, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.primary), maxLines = 1)
            Small(label, size = 11)
        }
    }
}

// ── Ask bar ──

/** A search-style bar: tap to ask, mic to ask by voice. */
class AskWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (_, lk) = look(context, id)
        provideContent { Themed(lk) { Content(context, lk) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) { provideContent { Themed(Look()) { Content(context, Look()) } } }

    @Composable
    private fun Content(context: Context, lk: Look) {
        Frame(lk, openApp(context, "ask"), padding = 8) {
            Row(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surfaceVariant).cornerRadius(20.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(R.drawable.ic_w_search, GlanceTheme.colors.onSurfaceVariant, 20)
                Spacer(GlanceModifier.width(10.dp))
                Text("Ask anything you've said…", style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                RoundButton(R.drawable.ic_w_mic, "Ask by voice", openApp(context, "ask:voice"), primary = true, size = 36)
            }
        }
    }
}

// ── Quick actions ──

/** Tiles you choose: ask, voice question, task, note, brief, check-in, listen, dashboard, calendar, health. */
class QuickWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    data class Tile(val key: String, val icon: Int, val label: String)
    companion object {
        val ALL = listOf(
            Tile("ask", R.drawable.ic_w_forum, "Ask"), Tile("voice", R.drawable.ic_w_mic, "Voice ask"), Tile("task", R.drawable.ic_w_task_alt, "Task"),
            Tile("note", R.drawable.ic_w_edit_note, "Note"), Tile("brief", R.drawable.ic_w_volume_up, "Brief me"), Tile("checkin", R.drawable.ic_w_record_voice_over, "Check-in"),
            Tile("listen", R.drawable.ic_w_play_arrow, "Listen"), Tile("pause", R.drawable.ic_w_pause, "Pause"), Tile("dashboard", R.drawable.ic_w_auto_awesome, "Dashboard"),
            Tile("calendar", R.drawable.ic_w_calendar_month, "Calendar"), Tile("health", R.drawable.ic_w_thermostat, "Health"), Tile("plan", R.drawable.ic_w_checklist, "Plan"),
            Tile("travel", R.drawable.ic_w_flight, "Travel"),
        )
        const val DEFAULT = "ask,task,note,brief,checkin"
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (wid, lk) = look(context, id)
        val keys = WidgetPrefs.get(context, wid, "tiles", DEFAULT).split(',')
        val travel = App.graph.settings.value.travel
        provideContent { Themed(lk) { Content(context, lk, keys.mapNotNull { k -> ALL.firstOrNull { it.key == k } }, WidgetPrefs.bool(context, wid, "labels", true), travel) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Themed(Look()) { Content(context, Look(), DEFAULT.split(',').mapNotNull { k -> ALL.firstOrNull { it.key == k } }, true) } }
    }

    private fun action(context: Context, key: String): Action = when (key) {
        "task" -> actionStartActivity(Intent(context, QuickAddActivity::class.java))
        "note" -> openApp(context, "note:new")
        "brief" -> brief(context)
        "voice" -> openApp(context, "ask:voice")
        "listen" -> capture(context, CaptureService.ACTION_START)
        "pause" -> capture(context, CaptureService.ACTION_PAUSE, 30)
        "travel" -> travelAction(context, app.echomind.notify.TravelReceiver.ACTION_TOGGLE)
        else -> openApp(context, key)
    }

    @Composable
    private fun Content(context: Context, lk: Look, tiles: List<Tile>, labels: Boolean, travel: Boolean = false) {
        Frame(lk, null, padding = 8) {
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                tiles.forEach { t ->
                    Column(GlanceModifier.defaultWeight().fillMaxHeight().clickable(action(context, t.key)), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                        // The travel tile lights up while travel mode is on.
                        val on = t.key == "travel" && travel
                        Box(GlanceModifier.size(40.dp).cornerRadius(20.dp).background(if (on) GlanceTheme.colors.tertiary else GlanceTheme.colors.secondaryContainer), contentAlignment = Alignment.Center) {
                            Icon(t.icon, if (on) GlanceTheme.colors.onTertiary else GlanceTheme.colors.onSecondaryContainer, 22)
                        }
                        if (labels) { Spacer(GlanceModifier.height(3.dp)); Small(if (on) "Travel on" else t.label, GlanceTheme.colors.onSurface, 11) }
                    }
                }
            }
        }
    }
}

// ── receivers ──

class ListenWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ListenWidget() }
class MicWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MicWidget() }
/** The first widget's receiver name is kept, so widgets already on the home screen keep working (now: Today's tasks). */
class EchoWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TasksWidget() }
class AgendaWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = AgendaWidget() }
class NotesWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = NotesWidget() }
class DayWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = DayWidget() }
class AskWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = AskWidget() }
class QuickWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = QuickWidget() }

private fun travelAction(context: Context, action: String, profile: String? = null): Action =
    androidx.glance.appwidget.action.actionSendBroadcast(Intent(context, app.echomind.notify.TravelReceiver::class.java).setAction(action).apply { profile?.let { putExtra(app.echomind.notify.TravelReceiver.EXTRA_PROFILE, it) } })

// ── Travel ──

/** Travel mode at a glance: on or off, and how it rests — tap a profile to start in it, or "I'm back". */
class TravelWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    data class T(val on: Boolean, val profile: String, val since: Long, val auto: Boolean)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (_, lk) = look(context, id)
        val s = App.graph.settings.value
        val t = T(s.travel, s.travelProfile, s.travelSince, s.travelAuto)
        provideContent { Themed(lk) { Content(context, lk, t) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Themed(Look()) { Content(context, Look(), T(false, "rest", 0, true)) } }
    }

    private val profiles = listOf("rest" to "Rest", "places" to "Note places", "record" to "Record only")

    @Composable
    private fun Content(context: Context, lk: Look, t: T) {
        Frame(lk, openApp(context, "travel")) {
            Column(GlanceModifier.fillMaxSize()) {
                Header(R.drawable.ic_w_flight, if (t.on) "Travel mode · On" else "Travel mode") {
                    if (t.on) RoundButton(R.drawable.ic_w_stop, "I'm back", travelAction(context, app.echomind.notify.TravelReceiver.ACTION_OFF), primary = true, size = 34)
                }
                Spacer(GlanceModifier.height(4.dp))
                Small(
                    if (t.on) "${profiles.firstOrNull { it.first == t.profile }?.second ?: "Rest"} since ${Prompts.time(t.since)} · nothing is processed until you're back"
                    else "Off${if (t.auto) " · turns on by itself away from your places" else ""}. Start it as:",
                    size = 12, lines = 2,
                )
                Spacer(GlanceModifier.defaultWeight())
                Row(GlanceModifier.fillMaxWidth()) {
                    profiles.forEachIndexed { i, (k, label) ->
                        val active = t.on && t.profile == k
                        Box(
                            GlanceModifier.defaultWeight().height(38.dp).cornerRadius(19.dp)
                                .background(if (active) GlanceTheme.colors.tertiary else GlanceTheme.colors.secondaryContainer)
                                .clickable(travelAction(context, app.echomind.notify.TravelReceiver.ACTION_ON, k)),
                            contentAlignment = Alignment.Center,
                        ) { Small(label, if (active) GlanceTheme.colors.onTertiary else GlanceTheme.colors.onSecondaryContainer, 12) }
                        if (i < profiles.lastIndex) Spacer(GlanceModifier.width(6.dp))
                    }
                }
            }
        }
    }
}
class TravelWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TravelWidget() }

/** Keeps the widgets current, and their previews in the widget picker. */
object WidgetUpdater {
    val receivers = listOf(ListenWidgetReceiver::class, MicWidgetReceiver::class, EchoWidgetReceiver::class, AgendaWidgetReceiver::class,
        NotesWidgetReceiver::class, DayWidgetReceiver::class, AskWidgetReceiver::class, QuickWidgetReceiver::class, TravelWidgetReceiver::class)

    suspend fun updateAll(context: Context) {
        runCatching {
            ListenWidget().updateAll(context); MicWidget().updateAll(context); TasksWidget().updateAll(context); AgendaWidget().updateAll(context)
            NotesWidget().updateAll(context); DayWidget().updateAll(context); AskWidget().updateAll(context); QuickWidget().updateAll(context)
            TravelWidget().updateAll(context)
        }
    }

    @OptIn(FlowPreview::class)
    fun start(context: Context, scope: CoroutineScope) {
        val g = App.graph
        // Real previews in the widget picker (Android 15+), once per app version (Android rate-limits this).
        scope.launch {
            val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0)
            if (android.os.Build.VERSION.SDK_INT >= 35 && prefs.getLong("previewsFor", -1) != version) {
                val m = GlanceAppWidgetManager(context)
                val ok = receivers.all { r -> runCatching { m.setWidgetPreviews(r) }.getOrDefault(-1) == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }
                if (ok) prefs.edit().putLong("previewsFor", version).apply()
            }
        }
        scope.launch {
            g.capture.state.map { it.mode }.distinctUntilChanged().collect { runCatching { ListenWidget().updateAll(context); MicWidget().updateAll(context) } }
        }
        // Travel mode on/off or a new profile: the travel widget and the quick travel tile follow.
        scope.launch {
            g.settings.flow.map { Triple(it.travel, it.travelProfile, it.travelSince) }.distinctUntilChanged().collect { runCatching { TravelWidget().updateAll(context); QuickWidget().updateAll(context) } }
        }
        // Processing and heat change often: the status widget follows at most every 20 s.
        scope.launch {
            combine(g.brain.state, g.governor.state) { b, gv -> Triple(b.phase, b.detail, gv.reason) }.distinctUntilChanged().sample(20_000).collect { runCatching { ListenWidget().updateAll(context) } }
        }
        scope.launch {
            combine(g.db.tasks().observeOpen(), g.db.notes().observeAll(), g.plans.observe("day")) { a, b, c -> Triple(a.size to a.maxOfOrNull { it.updatedAt }, b.maxOfOrNull { it.updatedAt }, c) }
                .debounce(2_000).collect {
                    runCatching { TasksWidget().updateAll(context); NotesWidget().updateAll(context); DayWidget().updateAll(context); AgendaWidget().updateAll(context) }
                }
        }
    }
}
