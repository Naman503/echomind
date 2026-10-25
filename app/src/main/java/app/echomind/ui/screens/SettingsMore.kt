package app.echomind.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.core.Settings
import app.echomind.ui.LocalNav
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.PrivacyChip
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.components.SettingRow
import app.echomind.ui.components.SwitchRow
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch

@Composable
private fun Page(title: String, content: LazyListScope.() -> Unit) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar(title, onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.xs), content = content, state = app.echomind.ui.components.rememberKeptListState())
    }
}

private fun set(change: (Settings) -> Settings) { val g = App.graph; g.scope.launch { g.settings.update(change); g.brain.poke() } }

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Sp.s))

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (k, l) -> SegmentedButton(selected == k, { onPick(k) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(l) } }
    }
}

/** How smart and how independent the AI is. */
@Composable
fun AiSettingsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    Page("AI behaviour") {
        item {
            SectionHeader("Thinking")
            Choice(listOf("fast" to "Fast", "balanced" to "Balanced", "best" to "Best"), s.aiQuality) { v -> set { it.copy(aiQuality = v) } }
            Hint(when (s.aiQuality) {
                "fast" -> "Answers straight away. Quickest and lightest on battery; notes may miss nuance."
                "best" -> "Thinks deeply before notes, memory, people, plans and the journal, then double-checks what it found against the transcript. Slowest; costs more with a cloud model."
                else -> "Thinks before the jobs that need judgement and answers questions straight away. Recommended."
            })
            SettingRow("Who runs the AI", "On this phone, or a cloud model you choose — per feature", onClick = { nav.go(app.echomind.ui.ProvidersRoute) })
            SettingRow("When the AI reads your day", "Live, every hour, after enough words, while charging, or by hand", onClick = { nav.go(app.echomind.ui.SessionsRoute) })
        }
        item {
            SectionHeader("Language")
            Choice(listOf("English" to "English", "Hindi" to "Hindi", "Hinglish" to "Hinglish"), s.notesLanguage) { v -> set { it.copy(notesLanguage = v) } }
            Hint("Notes, tasks, summaries and the journal are written in this language. Quotes always stay as they were said.")
        }
        item {
            SectionHeader("What it does by itself")
            SwitchRow("Add sure tasks and events directly", "Very clear commitments of yours with a time skip the review list", s.autoAcceptConfident) { v -> set { it.copy(autoAcceptConfident = v) } }
            SwitchRow("Follow up on tasks", "When a conversation shows a task is done, moved or stuck, the task is updated (done needs your tap)", s.taskFollowUps) { v -> set { it.copy(taskFollowUps = v) } }
            SwitchRow("Morning plans", "Plan for today, this week and this month from your tasks, calendar and memory", s.autoPlans) { v -> set { it.copy(autoPlans = v) } }
            SwitchRow("File notes every night", "New notes go into folders with tags", s.organizeNightly) { v -> set { it.copy(organizeNightly = v) } }
            SwitchRow("Keep people's pages up to date", "Rewrites who someone is when they come up again", s.storiesNightly) { v -> set { it.copy(storiesNightly = v) } }
            SwitchRow("Events from conversations", "Plans you mention (\"dentist Friday 5\") are offered for the calendar", s.eventsFromConversations) { v -> set { it.copy(eventsFromConversations = v) } }
            SwitchRow("Search by meaning", "Find things even when you don't remember the exact words (uses the search model)", s.semanticSearch) { v -> set { it.copy(semanticSearch = v) } }
            SettingRow("Organise now", "Review a folder tree for notes and circles for people", onClick = { nav.go(app.echomind.ui.OrganizeRoute) })
        }
        item {
            SectionHeader("Internet")
            SettingRow("Web search", when (s.webSearch) { "off" -> "Off"; "auto" -> "On, automatic"; else -> "On, asks first" }, onClick = { nav.go(app.echomind.ui.WebSettingsRoute) })
        }
    }
}

/** Looking things up on the internet. */
@Composable
fun WebSettingsScreen() {
    val g = App.graph
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val keys by g.secrets.present.collectAsStateWithLifecycle()
    Page("Web search") {
        item {
            Hint("The AI can look things up — song names, facts, how-tos — to answer better and to plan. Only short, general searches are sent: never your conversations, and names, numbers and emails are removed on the phone first. Every search is in the Data ledger.")
            SectionHeader("Web search")
            Choice(listOf("off" to "Off", "ask" to "Ask first", "auto" to "Automatic"), s.webSearch) { v -> set { it.copy(webSearch = v) } }
            Hint(when (s.webSearch) {
                "off" -> "Nothing is ever searched."
                "auto" -> "Searches when you tap the globe in Ask, or when your memory has no answer. Songs can be named in the background."
                else -> "You see the exact words before each search and can untick any of them."
            })
        }
        if (s.webSearch != "off") {
            item {
                SectionHeader("Search with")
                listOf(
                    Triple("duckduckgo", "DuckDuckGo", "No key, but it often blocks automated searches from apps."),
                    Triple("searxng", "Your own SearXNG", "Most private: your server asks the search engines for you."),
                    Triple("wikipedia", "Wikipedia only", "Facts and topics, nothing else."),
                    Triple("brave", "Brave Search", "Independent index; needs a free API key."),
                    Triple("google", "Google", "Needs a Programmable Search key and engine ID."),
                ).forEach { (id, label, desc) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(s.webProvider == id, { set { it.copy(webProvider = id) } })
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (s.webProvider == "searxng") item {
                var url by remember { mutableStateOf(s.searxngUrl) }
                OutlinedTextField(url, { url = it; set { st -> st.copy(searxngUrl = it.trim()) } }, label = { Text("SearXNG address (JSON format enabled)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (s.webProvider == "brave") item { KeyField("brave", "Brave Search API key", "brave" in keys) }
            if (s.webProvider == "google") item {
                KeyField("google_search", "Google API key", "google_search" in keys)
                var cx by remember { mutableStateOf(s.googleCx) }
                OutlinedTextField(cx, { cx = it; set { st -> st.copy(googleCx = it.trim()) } }, label = { Text("Search engine ID (cx)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                SectionHeader("Depth")
                SwitchRow("Read the top pages", "Better answers from the two best results, not just their snippets", s.webReadPages) { v -> set { it.copy(webReadPages = v) } }
                Text("Songs are looked up by their own switch in Settings → Sounds, not by web search.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun KeyField(name: String, label: String, present: Boolean) {
    val g = App.graph
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, label = { Text(if (present) "$label (saved)" else label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.weight(1f))
        TextButton(enabled = text.isNotBlank(), onClick = { g.secrets.set(name, text.trim()); text = ""; g.usage.event("api_key_added", mapOf("provider" to app.echomind.telemetry.UsageMath.keyName(name), "where" to "settings")) }) { Text("Save") }
        if (present) TextButton(onClick = { g.secrets.set(name, null); g.usage.event("api_key_removed", mapOf("provider" to app.echomind.telemetry.UsageMath.keyName(name))) }) { Text("Remove") }
    }
}

/** Songs and the surroundings timeline. */
@Composable
fun SoundsSettingsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val models by g.models.states.collectAsStateWithLifecycle()
    Page("Sounds") {
        item {
            PrivacyChip(true, "Recognised on this phone")
            Hint("A small sound model (29 MB) listens to the recordings EchoMind already makes and recognises what isn't talk: singing, music, traffic, kitchen, TV and 500 more.")
            val st = models["sound-tagger"]
            if (st?.installed != true) EchoCard {
                Text("Sound recognition model", style = MaterialTheme.typography.titleSmall)
                if (st?.downloading == true || st?.extracting == true) Text(if (st.extracting) "Unpacking…" else downloadLine(st, 28_531_989))
                else Button(onClick = { g.models.download("sound-tagger") }) { Text("Download · 29 MB") }
            }
        }
        item {
            SectionHeader("Songs")
            SwitchRow("Keep songs", "When you sing or music plays, that part is kept as its own clip in Songs, with the words", s.songs) { v -> set { it.copy(songs = v) } }
            if (s.songs) {
                SwitchRow("Name songs by themselves", "Right after each song is transcribed", s.songsAutoIdentify) { v -> set { it.copy(songsAutoIdentify = v) } }
                SwitchRow("Look songs up online", "Title, film, year, poster and listening links: a few lyric words and the title go to Apple's music catalogue and LRCLIB (open lyrics). Off = the phone's AI guesses offline.", s.songsOnline) { v -> set { it.copy(songsOnline = v) } }
            }
            SettingRow("Open Songs", "Posters, your singing, the original, links — and a scan of kept recordings", onClick = { nav.go(app.echomind.ui.SongsRoute) })
        }
        item {
            SectionHeader("What's going on around you")
            SwitchRow("Notice surroundings", "Builds a timeline — on the road, kitchen, at the computer, TV, walking, outdoors. Also checks moments with no talk, so it uses a bit more battery.", s.ambient) { v -> set { it.copy(ambient = v) } }
            if (s.ambient) {
                var days by remember { mutableFloatStateOf(s.ambientDays.toFloat()) }
                Text("Keep the timeline ${days.toInt()} days", style = MaterialTheme.typography.bodyMedium)
                Slider(days, { days = it }, valueRange = 7f..180f, onValueChangeFinished = { set { it.copy(ambientDays = days.toInt()) } })
            }
            Hint("Shown in the Calendar (each day) and the Dashboard. Only labels are stored — no extra audio.")
        }
    }
}

/** People, names and voices. */
@Composable
fun PeopleSettingsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    Page("People & names") {
        item {
            SectionHeader("Recognising people")
            SwitchRow("Recognise people by voice", "When you name a speaker (or it's a call with a contact), their voice is remembered and their lines are named next time", s.recognisePeople) { v -> set { it.copy(recognisePeople = v) } }
            SettingRow("Your voice", "Teach it your voice, sensitivity, corrections", onClick = { nav.go(app.echomind.ui.VoiceRoute) })
        }
        item {
            SectionHeader("Remembering names")
            var w by remember { mutableFloatStateOf(s.namesWindowDays.toFloat()) }
            Text("“Names to remember” shows people you met in the last ${w.toInt()} days", style = MaterialTheme.typography.bodyMedium)
            Slider(w, { w = it }, valueRange = 7f..120f, onValueChangeFinished = { set { it.copy(namesWindowDays = w.toInt()) } })
            SwitchRow("Keep people's pages up to date", "Who they are, what they do, how you know them — rewritten when they come up again", s.storiesNightly) { v -> set { it.copy(storiesNightly = v) } }
            SettingRow("People", "Circles, connections, practise names", onClick = { nav.go(app.echomind.ui.PeopleRoute) })
        }
    }
}

/** Calendar sources, write-back and heads-ups. */
@Composable
fun CalendarSettingsScreen() {
    val g = App.graph
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.READ_CALENDAR] == true) set { it.copy(showPhoneCalendar = true) }
    }
    Page("Calendar") {
        item {
            SectionHeader("Phone calendars")
            SwitchRow("Show the phone's calendars", if (g.agenda.canRead()) "Google Calendar and others, read on the phone" else "Needs calendar permission", s.showPhoneCalendar) { v ->
                if (v && !g.agenda.canRead()) perms.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) else set { it.copy(showPhoneCalendar = v) }
            }
            SwitchRow("Copy my events to the phone calendar", "Confirmed EchoMind events are added to your main calendar too", s.writeToPhoneCalendar) { v ->
                if (v && !g.agenda.canWrite()) perms.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) else set { it.copy(writeToPhoneCalendar = v) }
            }
        }
        item {
            SectionHeader("Before an event")
            SwitchRow("Heads-up with who you're meeting", "Who they are, what's open with them and where — also for phone-calendar events", s.eventPrep) { v -> set { it.copy(eventPrep = v) } }
            Text("Default heads-up for new events", style = MaterialTheme.typography.bodyMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                items(listOf(-1 to "None", 5 to "5 min", 10 to "10 min", 15 to "15 min", 30 to "30 min", 60 to "1 hour")) { (m, l) ->
                    FilterChip(s.eventRemindDefault == m, { set { it.copy(eventRemindDefault = m) } }, label = { Text(l) })
                }
            }
        }
        item {
            SectionHeader("From conversations")
            SwitchRow("Offer events I mention", "\"Dentist on Friday at 5\" appears under “Heard in conversations” to add with one tap", s.eventsFromConversations) { v -> set { it.copy(eventsFromConversations = v) } }
            SwitchRow("Add very clear ones directly", "Skips the question when it's certain (same switch as for tasks)", s.autoAcceptConfident) { v -> set { it.copy(autoAcceptConfident = v) } }
        }
        item {
            SectionHeader("Days")
            Hint("Each day in the calendar is coloured by how productive it was (your star rating wins over the AI's score), and shows the journal, events, tasks, conversations and — with “Notice surroundings” on — what was going on.")
        }
    }
}

/** Tasks and reminders. */
@Composable
fun TasksSettingsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    Page("Tasks & reminders") {
        item {
            SectionHeader("Adding tasks")
            SwitchRow("New tasks go on Today", "A task you type without a date is planned for today, so it's never lost", s.undatedToToday) { v -> set { it.copy(undatedToToday = v) } }
            Hint("Quick-add understands “tomorrow 6pm”, “every Monday”, #list, !1 priority and @labels.")
        }
        item {
            SectionHeader("From conversations")
            SwitchRow("Review before adding", "Off: notes, tasks and events from conversations are added automatically. On: tasks and events wait in Review for your OK.", s.reviewAiTasks) { v -> set { it.copy(reviewAiTasks = v) }; if (!v) g.scope.launch { g.tasks.acceptWaiting(force = true) } }
            if (s.reviewAiTasks) SwitchRow("Add sure ones directly", "Clear commitments of yours with a time skip the review list", s.autoAcceptConfident) { v -> set { it.copy(autoAcceptConfident = v) } }
            SwitchRow("Follow up on tasks", "Done, moved, stuck — what conversations say about tasks on your list", s.taskFollowUps) { v -> set { it.copy(taskFollowUps = v) } }
            SwitchRow("Morning plans", "Today / week / month plans, with what you might be missing", s.autoPlans) { v -> set { it.copy(autoPlans = v) } }
            SettingRow("Open today's plan", null, onClick = { nav.go(app.echomind.ui.PlanRoute) })
        }
        item {
            SectionHeader("Reminders")
            SwitchRow("Smart reminders", "Remind at the time you said; if you didn't say one, the AI picks the most useful moment (an hour before a call, the evening before an early start, the morning of an errand)", s.smartReminders) { v -> set { it.copy(smartReminders = v) } }
            SettingRow("Notifications", "Morning brief, evening recap, suggestions", onClick = { nav.go(app.echomind.ui.NotificationsRoute) })
            SettingRow("Assistant nudges", if (s.nudges) "Up to ${s.nudgesPerDay} a day" else "Off", onClick = { nav.go(app.echomind.ui.ReadingRoute) })
        }
    }
}
