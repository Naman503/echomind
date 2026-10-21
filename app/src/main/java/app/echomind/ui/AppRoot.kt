package app.echomind.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.echomind.App
import app.echomind.ui.components.CloudBanner
import app.echomind.ui.screens.AskScreen
import app.echomind.ui.screens.CallsScreen
import app.echomind.ui.screens.ChatsScreen
import app.echomind.ui.screens.ConversationScreen
import app.echomind.ui.screens.DataScreen
import app.echomind.ui.screens.DayScreen
import app.echomind.ui.screens.JournalScreen
import app.echomind.ui.screens.LedgerScreen
import app.echomind.ui.screens.LibraryScreen
import app.echomind.ui.screens.MemoryScreen
import app.echomind.ui.screens.ModelsScreen
import app.echomind.ui.screens.NoteScreen
import app.echomind.ui.screens.NotesScreen
import app.echomind.ui.screens.NotificationsScreen
import app.echomind.ui.screens.Onboarding
import app.echomind.ui.screens.PeopleScreen
import app.echomind.ui.screens.PersonScreen
import app.echomind.ui.screens.PowerScreen
import app.echomind.ui.screens.ProcessingScreen
import app.echomind.ui.screens.ProvidersScreen
import app.echomind.ui.screens.RecordingScreen
import app.echomind.ui.screens.SessionsScreen
import app.echomind.ui.screens.SettingsScreen
import app.echomind.ui.screens.TasksScreen
import app.echomind.ui.screens.TimelineScreen
import app.echomind.ui.screens.TodayScreen
import app.echomind.ui.screens.VoiceScreen

class Navigator(private val stack: NavBackStack<NavKey>) {
    val top: NavKey? get() = stack.lastOrNull()
    fun go(r: Route) { stack.add(r) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun tab(r: Route) {
        if (stack.size == 1 && stack.first() == r) return
        stack.clear(); stack.add(r)
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }

/*
 * Motion: Material's shared-axis pattern, kept short. Opening a screen slides
 * it in a sixth of the width while the old one drifts back and fades; closing
 * reverses it (and follows the finger for predictive back). Switching tabs is
 * a quick crossfade — tabs are siblings, not a step deeper.
 */
private val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun push(): ContentTransform =
    (slideInHorizontally(tween(280, easing = Ease)) { it / 6 } + fadeIn(tween(220, delayMillis = 30))) togetherWith
        (slideOutHorizontally(tween(280, easing = Ease)) { -it / 14 } + fadeOut(tween(120)))

private fun pop(): ContentTransform =
    (slideInHorizontally(tween(280, easing = Ease)) { -it / 14 } + fadeIn(tween(220))) togetherWith
        (slideOutHorizontally(tween(240, easing = Ease)) { it / 6 } + fadeOut(tween(160)))

private val TabSwitch: Map<String, Any> =
    NavDisplay.transitionSpec { fadeIn(tween(160)) togetherWith fadeOut(tween(90)) } +
        NavDisplay.popTransitionSpec { fadeIn(tween(160)) togetherWith fadeOut(tween(90)) }

@Composable
fun AppRoot(pending: MutableState<String?>) {
    val g = App.graph
    val loaded by g.settings.loaded.collectAsStateWithLifecycle()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    if (!loaded) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    if (!s.onboarded) {
        Box(Modifier.fillMaxSize()) {
            Onboarding()
            CloudBanner()
        }
        return
    }
    val stack = rememberNavBackStack(TodayRoute)
    val nav = remember(stack) { Navigator(stack) }
    LaunchedEffect(pending.value) {
        val r = pending.value ?: return@LaunchedEffect
        pending.value = null
        when (r) {
            "ask" -> nav.tab(AskRoute())
            "tasks" -> nav.tab(TasksRoute)
            "reminders" -> { nav.tab(TasksRoute); nav.go(RemindersRoute) }
            "journal" -> { nav.tab(LibraryRoute); nav.go(JournalRoute) }
            "calendar" -> nav.tab(CalendarRoute)
            "plan" -> { nav.tab(TodayRoute); nav.go(PlanRoute) }
            "dashboard" -> { nav.tab(TodayRoute); nav.go(DashboardRoute) }
            "note:new" -> { nav.tab(LibraryRoute); nav.go(NotesRoute); nav.go(NewNoteRoute()) }
            "settings" -> { nav.tab(TodayRoute); nav.go(SettingsRoute) }
            else -> if (r.startsWith("conversation:")) { nav.tab(TodayRoute); r.removePrefix("conversation:").toLongOrNull()?.let { nav.go(ConversationRoute(it)) } } else nav.tab(TodayRoute)
        }
    }
    val suggested by remember { g.db.tasks().suggestedCount() }.collectAsStateWithLifecycle(0)
    val top = stack.lastOrNull()
    val showBar = top is Route && top.isTab() && stack.size == 1

    BackHandler(enabled = stack.size == 1 && top != TodayRoute) { nav.tab(TodayRoute) }

    CompositionLocalProvider(LocalNav provides nav) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                bottomBar = {
                    androidx.compose.foundation.layout.Column {
                    app.echomind.ui.components.MiniPlayer()
                    AnimatedVisibility(showBar, enter = slideInVertically(tween(220, easing = Ease)) { it } + fadeIn(tween(150)), exit = slideOutVertically(tween(180, easing = Ease)) { it } + fadeOut(tween(120))) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Tab(top == TodayRoute, "Today", Icons.Rounded.Today) { nav.tab(TodayRoute) }
                        Tab(top == CalendarRoute, "Calendar", Icons.Rounded.CalendarMonth) { nav.tab(CalendarRoute) }
                        Tab(top is AskRoute, "Ask", Icons.Rounded.Forum) { nav.tab(AskRoute()) }
                        Tab(top == TasksRoute, "Tasks", Icons.Rounded.CheckCircle, badge = suggested) { nav.tab(TasksRoute) }
                        Tab(top == LibraryRoute, "Library", Icons.Rounded.AutoStories) { nav.tab(LibraryRoute) }
                    }
                    }
                    }
                },
            ) { padding ->
                NavDisplay(
                    backStack = stack,
                    onBack = { nav.back() },
                    modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                    transitionSpec = { push() },
                    popTransitionSpec = { pop() },
                    predictivePopTransitionSpec = { pop() },
                    entryProvider = entryProvider {
                        entry<TodayRoute>(metadata = TabSwitch) { TodayScreen() }
                        entry<CalendarRoute>(metadata = TabSwitch) { app.echomind.ui.screens.CalendarScreen() }
                        entry<TimelineRoute> { TimelineScreen() }
                        entry<EventRoute> { app.echomind.ui.screens.EventScreen(it.id) }
                        entry<NewEventRoute> { app.echomind.ui.screens.EventScreen(null, it.day) }
                        entry<CirclesRoute> { app.echomind.ui.screens.CirclesScreen() }
                        entry<OrganizeRoute> { app.echomind.ui.screens.OrganizeScreen() }
                        entry<AskRoute>(metadata = TabSwitch) { AskScreen(it.threadId, it.question) }
                        entry<TasksRoute>(metadata = TabSwitch) { TasksScreen() }
                        entry<LibraryRoute>(metadata = TabSwitch) { LibraryScreen() }
                        entry<ConversationRoute> { ConversationScreen(it.id, it.lineId) }
                        entry<TaskRoute> { app.echomind.ui.screens.TaskScreen(it.id) }
                        entry<RemindersRoute> { app.echomind.ui.screens.RemindersScreen() }
                        entry<ListsRoute> { app.echomind.ui.screens.ListsScreen() }
                        entry<NotesRoute> { NotesScreen() }
                        entry<NoteRoute> { NoteScreen(it.id) }
                        entry<NewNoteRoute> { NoteScreen(null, it.template) }
                        entry<MemoryRoute> { MemoryScreen() }
                        entry<PeopleRoute> { PeopleScreen() }
                        entry<PersonRoute> { PersonScreen(it.id) }
                        entry<JournalRoute> { JournalScreen() }
                        entry<DayRoute> { DayScreen(it.day) }
                        entry<ChatsRoute> { ChatsScreen() }
                        entry<ProcessingRoute> { ProcessingScreen() }
                        entry<SettingsRoute> { SettingsScreen() }
                        entry<ProvidersRoute> { ProvidersScreen() }
                        entry<ModelsRoute> { ModelsScreen() }
                        entry<RecordingRoute> { RecordingScreen() }
                        entry<CallsRoute> { CallsScreen() }
                        entry<SessionsRoute> { SessionsScreen() }
                        entry<PowerRoute> { PowerScreen() }
                        entry<NotificationsRoute> { NotificationsScreen() }
                        entry<LedgerRoute> { LedgerScreen() }
                        entry<DataRoute> { DataScreen() }
                        entry<VoiceRoute> { VoiceScreen() }
                        entry<ReadingRoute> { app.echomind.ui.screens.ReadingScreen() }
                        entry<RecordingsRoute> { app.echomind.ui.screens.RecordingsScreen() }
                        entry<DashboardRoute> { app.echomind.ui.screens.DashboardScreen() }
                        entry<PlanRoute> { app.echomind.ui.screens.PlanScreen() }
                        entry<SongsRoute> { app.echomind.ui.screens.SongsScreen() }
                        entry<WebSettingsRoute> { app.echomind.ui.screens.WebSettingsScreen() }
                        entry<SoundsSettingsRoute> { app.echomind.ui.screens.SoundsSettingsScreen() }
                        entry<AiSettingsRoute> { app.echomind.ui.screens.AiSettingsScreen() }
                        entry<PeopleSettingsRoute> { app.echomind.ui.screens.PeopleSettingsScreen() }
                        entry<CalendarSettingsRoute> { app.echomind.ui.screens.CalendarSettingsScreen() }
                        entry<TasksSettingsRoute> { app.echomind.ui.screens.TasksSettingsScreen() }
                        entry<VoicesRoute> { app.echomind.ui.screens.VoicesScreen(it.lang) }
                    },
                )
            }
            CloudBanner()
            SpeakerQuestion()
            WebQuestion()
        }
    }
}

/** "No earphones — play on the phone speaker?" before anything is read aloud there. */
@Composable
private fun SpeakerQuestion() {
    val g = App.graph
    val pending by g.speaker.pending.collectAsStateWithLifecycle()
    val notice by g.speaker.notice.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(notice) { notice?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show(); g.speaker.clearNotice() } }
    val p = pending ?: return
    var always by remember(p) { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { g.speaker.dismissPending() },
        icon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null) },
        title = { Text("No earphones connected") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("Read “${p.title}” out loud on the phone speaker? People nearby will hear it.")
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    androidx.compose.material3.Checkbox(always, { always = it })
                    Text("Don't ask again (change in Settings → Voice)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { g.speaker.confirmPending(always) }) { Text("Play on speaker") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { g.speaker.dismissPending() }) { Text("Not now") } },
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(selected: Boolean, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, badge: Int = 0, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            if (badge > 0) BadgedBox(badge = { Badge { Text(if (badge > 99) "99+" else "$badge") } }) { Icon(icon, label) }
            else Icon(icon, label)
        },
        label = { Text(label) },
    )
}

/** Shows exactly what would be searched, before anything is sent. */
@Composable
private fun WebQuestion() {
    val g = App.graph
    val a by g.web.approval.collectAsStateWithLifecycle()
    val req = a ?: return
    val picked = remember(req) { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(req.queries) } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { g.web.answer(null) },
        icon = { Icon(Icons.Rounded.Public, null) },
        title = { Text("Search the web?") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("${req.purpose}. Only these words go to ${g.web.providerLabel()} — nothing from your conversations:", style = MaterialTheme.typography.bodyMedium)
                req.queries.forEach { q ->
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(q in picked, { if (it) picked.add(q) else picked.remove(q) })
                        Text("“$q”", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text("Turn off this question in Settings → Web search.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = picked.isNotEmpty(), onClick = { g.web.answer(picked.toList()) }) { Text("Search") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { g.web.answer(null) }) { Text("Don't search") } },
    )
}

