package app.echomind.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.ViewTimeline
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
            "journal" -> { nav.tab(LibraryRoute); nav.go(JournalRoute) }
            "note:new" -> { nav.tab(LibraryRoute); nav.go(NotesRoute); nav.go(NewNoteRoute) }
            "settings" -> { nav.tab(TodayRoute); nav.go(SettingsRoute) }
            else -> nav.tab(TodayRoute)
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
                    if (showBar) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Tab(top == TodayRoute, "Today", Icons.Rounded.Today) { nav.tab(TodayRoute) }
                        Tab(top == TimelineRoute, "Timeline", Icons.Rounded.ViewTimeline) { nav.tab(TimelineRoute) }
                        Tab(top is AskRoute, "Ask", Icons.Rounded.Forum) { nav.tab(AskRoute()) }
                        Tab(top == TasksRoute, "Tasks", Icons.Rounded.CheckCircle, badge = suggested) { nav.tab(TasksRoute) }
                        Tab(top == LibraryRoute, "Library", Icons.Rounded.AutoStories) { nav.tab(LibraryRoute) }
                    }
                },
            ) { padding ->
                NavDisplay(
                    backStack = stack,
                    onBack = { nav.back() },
                    modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                    entryProvider = entryProvider {
                        entry<TodayRoute> { TodayScreen() }
                        entry<TimelineRoute> { TimelineScreen() }
                        entry<AskRoute> { AskScreen(it.threadId, it.question) }
                        entry<TasksRoute> { TasksScreen() }
                        entry<LibraryRoute> { LibraryScreen() }
                        entry<ConversationRoute> { ConversationScreen(it.id, it.lineId) }
                        entry<NotesRoute> { NotesScreen() }
                        entry<NoteRoute> { NoteScreen(it.id) }
                        entry<NewNoteRoute> { NoteScreen(null) }
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
                    },
                )
            }
            CloudBanner()
        }
    }
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
