package app.echomind.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Every screen. Tabs are the roots; everything else is pushed on top. */
@Serializable sealed interface Route : NavKey

@Serializable data object TodayRoute : Route
@Serializable data object TimelineRoute : Route
@Serializable data class AskRoute(val threadId: Long? = null, val question: String? = null) : Route
@Serializable data object TasksRoute : Route
@Serializable data object LibraryRoute : Route

@Serializable data class TaskRoute(val id: Long) : Route
@Serializable data object RemindersRoute : Route
@Serializable data object ListsRoute : Route
@Serializable data class ConversationRoute(val id: Long, val lineId: Long? = null) : Route
@Serializable data class NoteRoute(val id: Long) : Route
@Serializable data class NewNoteRoute(val template: String? = null) : Route
@Serializable data object NotesRoute : Route
@Serializable data object MemoryRoute : Route
@Serializable data object PeopleRoute : Route
@Serializable data class PersonRoute(val id: Long) : Route
@Serializable data object JournalRoute : Route
@Serializable data class DayRoute(val day: String) : Route
@Serializable data object ChatsRoute : Route
@Serializable data object ProcessingRoute : Route
@Serializable data object SettingsRoute : Route
@Serializable data object ProvidersRoute : Route
@Serializable data object ModelsRoute : Route
@Serializable data object RecordingRoute : Route
@Serializable data object CallsRoute : Route
@Serializable data object SessionsRoute : Route
@Serializable data object PowerRoute : Route
@Serializable data object NotificationsRoute : Route
@Serializable data object LedgerRoute : Route
@Serializable data object DataRoute : Route
@Serializable data object VoiceRoute : Route

val tabs: List<Route> = listOf(TodayRoute, TimelineRoute, AskRoute(), TasksRoute, LibraryRoute)
fun Route.isTab() = this == TodayRoute || this == TimelineRoute || this is AskRoute && threadId == null && question == null || this == TasksRoute || this == LibraryRoute
