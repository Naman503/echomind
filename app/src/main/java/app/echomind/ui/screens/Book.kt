package app.echomind.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.brain.BookPart
import app.echomind.brain.Chapter
import app.echomind.data.DayEntry
import app.echomind.ui.ChapterRoute
import app.echomind.ui.DayReviewRoute
import app.echomind.ui.LocalNav
import app.echomind.ui.components.DiaryPage
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.EmptyState
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.components.SectionHeader
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val serif = FontFamily.Serif
private fun range(from: String, to: String): String {
    val a = LocalDate.parse(from); val b = LocalDate.parse(to)
    val f = DateTimeFormatter.ofPattern("d MMM")
    return "${a.format(f)} – ${b.format(f)}"
}
private fun monthName(m: String) = LocalDate.parse("$m-01").format(DateTimeFormatter.ofPattern("MMMM yyyy"))
private fun minutes(text: String) = (text.split(Regex("\\s+")).size / 220).coerceAtLeast(1)

/** The book: a cover, "continue reading", and the contents — months as parts, weeks as chapters. */
@Composable
fun BookScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val chapterRows by remember { g.db.analyses().observeKind("chapter") }.collectAsStateWithLifecycle(emptyList())
    val partRows by remember { g.db.analyses().observeKind("part") }.collectAsStateWithLifecycle(emptyList())
    val chapters = chapterRows.mapNotNull { g.book.decodeChapter(it.json) }.sortedBy { it.week }
    val parts = partRows.mapNotNull { g.book.decodePart(it.json) }.associateBy { it.month }
    val thisWeek = g.book.weekOf(LocalDate.now())
    val read by produceState(emptySet<String>(), chapters.size) { value = chapters.map { it.week }.filter { g.db.memory().kv("book.read.$it") == "1" }.toSet() }
    val inProgress by produceState(emptyList<DayEntry>(), chapters.size) { value = g.book.diaryDays(thisWeek) }
    var writing by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Your book", onBack = { nav.back() })
        LazyColumn(contentPadding = PaddingValues(start = Sp.gutter, end = Sp.gutter, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            item {
                // The cover.
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = Sp.xl, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.AutoStories, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(Sp.m))
                        Text(if (s.userName.isBlank()) "The Book of My Days" else "The Book of ${s.userName.substringBefore(' ')}", textAlign = TextAlign.Center,
                            style = TextStyle(fontFamily = serif, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("${chapters.size} chapter${if (chapters.size == 1) "" else "s"} · written from your diary", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(top = Sp.s))
                    }
                }
            }
            val next = chapters.firstOrNull { it.week !in read } ?: chapters.lastOrNull()
            if (next != null) item {
                EchoCard(onClick = { nav.go(ChapterRoute(next.week)) }) {
                    Text(if (next.week in read) "Read again" else "Continue reading", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Chapter ${chapters.indexOf(next) + 1} · ${next.title}", style = TextStyle(fontFamily = serif, fontSize = 19.sp, fontStyle = FontStyle.Italic))
                    Text("${range(next.from, next.to)} · ${minutes(next.text)} min read", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (chapters.isEmpty()) item {
                EmptyState("Your first chapter is on its way", "When a week ends, EchoMind turns its diary entries into a chapter, at night. A week needs at least two diary days.",
                    icon = Icons.Rounded.AutoStories)
            }
            // Contents: parts (months), each with its chapters.
            if (chapters.isNotEmpty()) item { SectionHeader("Contents") }
            chapters.groupBy { it.from.take(7) }.toSortedMap().forEach { (month, list) ->
                val part = parts[month]
                item(key = "part$month") {
                    Column(Modifier.padding(top = Sp.m, bottom = 4.dp)) {
                        Text("PART · ${monthName(month).uppercase()}", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.15.em), color = MaterialTheme.colorScheme.tertiary)
                        part?.title?.takeIf { it.isNotBlank() && it != month }?.let { Text(it, style = TextStyle(fontFamily = serif, fontSize = 22.sp, fontStyle = FontStyle.Italic)) }
                        part?.intro?.takeIf { it.isNotBlank() }?.let { Text(it, style = TextStyle(fontFamily = serif, fontSize = 15.sp, lineHeight = 22.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
                items(list, key = { "ch" + it.week }) { ch ->
                    val n = chapters.indexOf(ch) + 1
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        EchoCard(onClick = { nav.go(ChapterRoute(ch.week)) }, modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text("$n", style = TextStyle(fontFamily = serif, fontSize = 26.sp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(min = 36.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ch.title, style = TextStyle(fontFamily = serif, fontSize = 18.sp, fontWeight = FontWeight.Medium))
                                    Text(range(ch.from, ch.to) + " · ${minutes(ch.text)} min", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (ch.summary.isNotBlank()) Text(ch.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                if (ch.week in read) Icon(Icons.Rounded.CheckCircle, "Read", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            // This week, still being lived.
            item { SectionHeader("This week, in progress") }
            if (inProgress.isEmpty()) item { Text("No diary entries yet this week.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(inProgress, key = { "ip" + it.day }) { d ->
                DiaryPage(d.diary.orEmpty(), d.headline, LocalDate.parse(d.day), maxLines = 4, onClick = { nav.go(DayReviewRoute(d.day)) })
            }
            val lastFinished = g.book.weekOf(LocalDate.now().minusDays(7))
            if (chapters.none { it.week == lastFinished }) item {
                OutlinedButton(enabled = !writing, onClick = {
                    writing = true
                    scope.launch { runCatching { g.book.step(force = lastFinished) }; writing = false }
                }) { Text(if (writing) "Writing last week's chapter… (a few minutes)" else "Write last week's chapter now") }
            }
        }
    }
}

/** One chapter: an opener page, the prose, then the week's days. */
@Composable
fun ChapterScreen(week: String) {
    val g = App.graph
    val nav = LocalNav.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val rows by remember { g.db.analyses().observeKind("chapter") }.collectAsStateWithLifecycle(emptyList())
    val chapters = rows.mapNotNull { g.book.decodeChapter(it.json) }.sortedBy { it.week }
    val ch = chapters.firstOrNull { it.week == week }
    val days by produceState(emptyList<DayEntry>(), week, ch?.writtenAt) { value = g.book.diaryDays(week) }
    var rewriting by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scale = s.bookFontScale
    val body = TextStyle(fontFamily = serif, fontSize = (18 * scale).sp, lineHeight = (30 * scale).sp, color = MaterialTheme.colorScheme.onSurface)
    val progress by remember { derivedStateOf { val info = list.layoutInfo; if (info.totalItemsCount == 0) 0f else (list.firstVisibleItemIndex + info.visibleItemsInfo.size).toFloat() / info.totalItemsCount } }
    LaunchedEffect(week, progress) { if (progress >= 0.95f) g.book.markRead(week) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar(ch?.let { "Chapter ${chapters.indexOf(it) + 1}" } ?: "Chapter", onBack = { nav.back() }) {
            IconButton(onClick = { scope.launch { g.settings.update { it.copy(bookFontScale = (it.bookFontScale - 0.1f).coerceAtLeast(0.85f)) } } }) { Icon(Icons.Rounded.TextDecrease, "Smaller text") }
            IconButton(onClick = { scope.launch { g.settings.update { it.copy(bookFontScale = (it.bookFontScale + 0.1f).coerceAtMost(1.4f)) } } }) { Icon(Icons.Rounded.TextIncrease, "Larger text") }
            IconButton(enabled = !rewriting, onClick = { rewriting = true; scope.launch { runCatching { g.book.step(force = week) }; rewriting = false } }) { Icon(Icons.Rounded.Refresh, "Rewrite chapter") }
        }
        if (ch == null) { EmptyState("Not written yet", "This chapter is written once its week is over."); return }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            // A comfortable column (about 60 characters a line) even on a wide screen.
            LazyColumn(state = list, modifier = Modifier.widthIn(max = 620.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = Sp.l)) {
                item {
                    // The opener.
                    Column(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("CHAPTER ${chapters.indexOf(ch) + 1}", style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.25.em), color = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.height(Sp.m))
                        Text(ch.title, textAlign = TextAlign.Center, style = TextStyle(fontFamily = serif, fontSize = (30 * scale).sp, lineHeight = (38 * scale).sp, fontWeight = FontWeight.Medium))
                        Text(range(ch.from, ch.to), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Sp.s))
                        if (ch.epigraph.isNotBlank()) Text(ch.epigraph, textAlign = TextAlign.Center, style = TextStyle(fontFamily = serif, fontStyle = FontStyle.Italic, fontSize = (16 * scale).sp, lineHeight = (24 * scale).sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 28.dp, start = Sp.l, end = Sp.l))
                        Text("${minutes(ch.text)} min read", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = Sp.l))
                    }
                }
                ch.text.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, p ->
                    item(key = "p$i") {
                        Text(if (i == 0) androidx.compose.ui.text.buildAnnotatedString {
                            pushStyle(androidx.compose.ui.text.SpanStyle(fontSize = 2.6.em, color = MaterialTheme.colorScheme.primary)); append(p.take(1)); pop(); append(p.drop(1))
                        } else androidx.compose.ui.text.AnnotatedString(p), style = body, modifier = Modifier.padding(bottom = 18.dp))
                    }
                }
                item { Text("❧", textAlign = TextAlign.Center, style = TextStyle(fontSize = 22.sp), color = MaterialTheme.colorScheme.outline, modifier = Modifier.fillMaxWidth().padding(vertical = Sp.l)) }
                if (days.isNotEmpty()) {
                    item { SectionHeader("The days of this chapter") }
                    items(days, key = { "d" + it.day }) { d ->
                        DiaryPage(d.diary.orEmpty(), d.headline, LocalDate.parse(d.day), maxLines = 6, onClick = { nav.go(DayReviewRoute(d.day)) }, modifier = Modifier.padding(bottom = Sp.s))
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = Sp.l))
                    val i = chapters.indexOf(ch)
                    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (i > 0) TextButton(onClick = { nav.back(); nav.go(ChapterRoute(chapters[i - 1].week)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null); Text("Previous") } else Spacer(Modifier.size(1.dp))
                        if (i < chapters.size - 1) Button(onClick = { nav.back(); nav.go(ChapterRoute(chapters[i + 1].week)) }) { Text("Next chapter"); Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }
                    }
                }
            }
        }
    }
}
