package app.echomind.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.echomind.ui.theme.Sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The diary's typeface: a serif, read like a page rather than a screen. */
val DiaryStyle @Composable get() = TextStyle(fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 28.sp, color = MaterialTheme.colorScheme.onSurface)

/**
 * A diary page: the date, the entry's title in italics, and the text in a serif with a
 * large first letter. [maxLines] trims it for previews.
 */
@Composable
fun DiaryPage(text: String, title: String? = null, date: LocalDate? = null, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val paper = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.06f).compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
    EchoCard(modifier = modifier, onClick = onClick, color = paper, padding = PaddingValues(horizontal = Sp.xl, vertical = Sp.l)) {
        date?.let {
            Text(it.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")).uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.15.em),
                color = MaterialTheme.colorScheme.tertiary)
        }
        title?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp, bottom = Sp.s))
        }
        DiaryBody(text, maxLines)
    }
}

@Composable
fun DiaryBody(text: String, maxLines: Int = Int.MAX_VALUE) {
    val paragraphs = text.trim().split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    if (paragraphs.isEmpty()) return
    val accent = MaterialTheme.colorScheme.primary
    if (maxLines != Int.MAX_VALUE) {
        Text(dropCap(paragraphs.joinToString(" "), accent), style = DiaryStyle, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        paragraphs.forEachIndexed { i, p -> Text(if (i == 0) dropCap(p, accent) else buildAnnotatedString { append(p) }, style = DiaryStyle) }
    }
}

private fun dropCap(p: String, color: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    withStyle(SpanStyle(fontSize = 2.1.em, fontFamily = FontFamily.Serif, color = color, fontWeight = FontWeight.Medium)) { append(p.take(1)) }
    append(p.drop(1))
}
