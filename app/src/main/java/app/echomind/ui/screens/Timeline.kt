package app.echomind.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.TextNorm
import app.echomind.data.Conversation
import app.echomind.data.Gap
import app.echomind.data.Utterance
import app.echomind.ui.ConversationRoute
import app.echomind.ui.Fmt
import app.echomind.ui.LocalNav
import app.echomind.ui.components.EmptyState
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable
fun TimelineScreen() {
    val g = App.graph
    val nav = LocalNav.current
    var dayStr by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val day = LocalDate.parse(dayStr)
    var query by rememberSaveable { mutableStateOf("") }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val (from, to) = remember(day) { g.journal.bounds(day) }
    val convs by remember(day) { g.db.conversations().inRange(from, to) }.collectAsStateWithLifecycle(emptyList())
    val gaps by remember(day) { g.db.gaps().inRange(from, to) }.collectAsStateWithLifecycle(emptyList())
    val sounds by remember(day) { g.db.sounds().observeRange(from, to) }.collectAsStateWithLifecycle(emptyList())
    var results by remember { mutableStateOf<List<Utterance>>(emptyList()) }
    LaunchedEffect(query) {
        delay(250)
        results = TextNorm.ftsQuery(query)?.let { runCatching { g.db.utterances().search(it, limit = 80) }.getOrDefault(emptyList()) } ?: emptyList()
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        app.echomind.ui.components.ScreenTopBar("Timeline", onBack = { nav.back() })
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Sp.gutter),
            placeholder = { Text("Search everything you've said…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
        )
        if (query.isNotBlank()) {
            LazyColumn(contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.s), state = app.echomind.ui.components.rememberKeptListState()) {
                if (results.isEmpty()) item { EmptyState("Nothing found", "Try other words — names work in any spelling.") }
                items(results, key = { it.id }) { u ->
                    Column(Modifier.fillMaxWidth().clickable { u.conversationId?.let { nav.go(ConversationRoute(it, u.id)) } }.padding(vertical = 6.dp)) {
                        Text("${Fmt.dayTime(u.startAt)}${u.speaker?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(u.text, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            return@Column
        }
        DayStrip(day) { dayStr = it.toString() }
        val items = remember(convs, gaps, sounds, showAll) { merge(convs.filter { showAll || (it.state != "skipped" && !it.ignored) }, gaps, sounds.filter { it.kind == "song" }) }
        val list = app.echomind.ui.components.rememberKeptListState()
        LazyColumn(state = list, contentPadding = PaddingValues(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.m)) {
            if (items.isEmpty()) item { EmptyState(if (day == LocalDate.now()) "Nothing yet today" else "Nothing on ${Fmt.day(day)}", "Conversations appear here after they're transcribed.") }
            items(items, key = { when (it) { is Conversation -> "c${it.id}"; is Gap -> "g${it.id}"; is app.echomind.data.SoundRow -> "s${it.id}"; else -> it.hashCode() } }, contentType = { it::class }) { item ->
                when (item) {
                    is Conversation -> ConversationRow(item) { nav.go(ConversationRoute(item.id)) }
                    is app.echomind.data.SoundRow -> SongMoment(item)
                    is Gap -> GapRow(item)
                }
            }
            val hidden = convs.count { it.state == "skipped" || it.ignored }
            if (hidden > 0) item {
                Text(if (showAll) "Hide brief moments and media" else "Show $hidden brief moments and media", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { showAll = !showAll }.padding(Sp.s))
            }
        }
    }
}

private fun merge(convs: List<Conversation>, gaps: List<Gap>, songs: List<app.echomind.data.SoundRow>): List<Any> =
    (convs.map { it.startAt to it as Any } + gaps.map { it.startAt to it as Any } + songs.map { it.startAt to it as Any }).sortedByDescending { it.first }.map { it.second }

@Composable
private fun GapRow(gap: Gap) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Sp.s), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${Fmt.span(gap.startAt, gap.endAt)} · " + when (gap.reason) {
                "paused" -> "Paused"
                "call" -> "On a call"
                "other_app" -> "Another app used the mic"
                "forgotten" -> "Forgotten at your request"
                "quiet" -> "Quiet hours"
                else -> "Not listening"
            },
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun DayStrip(selected: LocalDate, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val days = remember(today) { (0..20).map { today.minusDays(it.toLong()) } }
    LazyRow(contentPadding = PaddingValues(horizontal = Sp.gutter, vertical = Sp.s), horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
        items(days, key = { it.toString() }) { d ->
            FilterChip(selected = d == selected, onClick = { onPick(d) }, label = { Text(Fmt.day(d)) })
        }
    }
    Spacer(Modifier.height(0.dp).width(0.dp))
}
