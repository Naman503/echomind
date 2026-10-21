package app.echomind.brain

/**
 * Natural-language quick add, Todoist-style:
 *
 *   "Call mom tomorrow 6pm #family !1 @phone every week"
 *     → title "Call mom", due tomorrow 18:00, list "family", priority 1, label "phone", weekly
 *
 * Works with Hinglish time words through [When] ("kal shaam 5 baje").
 */
object QuickAdd {
    data class Parsed(
        val title: String,
        val due: When.Resolved?,
        val priority: Int,
        val list: String?,
        val labels: List<String>,
        val recurrence: String?,
        /** "Remind me…" phrasing, or an explicit time: notify at the due time. */
        val remind: Boolean,
    )

    private val timeWords = listOf(
        "day after tomorrow", "tomorrow", "tmrw", "today", "tonight", "this evening", "this morning", "next week",
        "kal", "parso", "parson", "aaj", "abhi", "agle hafte",
        "morning", "afternoon", "evening", "night", "subah", "dopahar", "shaam", "sham", "raat",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "mon", "tue", "wed", "thu", "fri", "sat", "sun",
        "somvar", "mangalvar", "budhvar", "guruvar", "shukravar", "shanivar", "ravivar", "itvaar",
    )

    fun parse(input: String, now: Long = System.currentTimeMillis()): Parsed {
        var text = " " + input.trim() + " "
        var priority = 4
        Regex("\\s(!|p)([1-4])(?=\\s)", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            priority = m.groupValues[2].toInt(); text = text.removeRange(m.range).let { " $it " }
        }
        var list: String? = null
        Regex("\\s#([\\p{L}\\p{N}_-]+)").find(text)?.let { m -> list = m.groupValues[1]; text = text.removeRange(m.range) }
        val labels = Regex("\\s@([\\p{L}\\p{N}_-]+)").findAll(text).map { it.groupValues[1] }.toList()
        text = text.replace(Regex("\\s@[\\p{L}\\p{N}_-]+"), " ")

        val rec = Recurrence.parse(text)
        if (rec != null) text = text.replace(rec.second, " ", ignoreCase = true)

        val remind = Regex("^\\s*(remind me( to)?|reminder:?|yaad dila(na)?( ki)?)\\s", RegexOption.IGNORE_CASE).containsMatchIn(text)
        text = text.replace(Regex("^\\s*(remind me( to)?|reminder:?|yaad dila(na)?( ki)?)\\s", RegexOption.IGNORE_CASE), " ")

        val due = When.parse(text, now)
        if (due != null) {
            // Strip the time phrase from the title.
            text = text.replace(Regex("\\b(at|by|on|ko|se pehle)?\\s*\\d{1,2}([:.]\\d{2})?\\s*(am|pm|a\\.m\\.|p\\.m\\.|baje)\\b", RegexOption.IGNORE_CASE), " ")
            for (w in timeWords.sortedByDescending { it.length }) {
                text = text.replace(Regex("\\b(on |at |by |this |next )?${Regex.escape(w)}\\b", RegexOption.IGNORE_CASE), " ")
            }
        }
        val title = text.replace(Regex("\\s+"), " ").trim().trim(',', '-', '.').replaceFirstChar { it.uppercase() }
        // With a repeat rule but no date, start today.
        val effectiveDue = due ?: if (rec != null) When.parse("today", now) else null
        return Parsed(title.ifEmpty { input.trim() }, effectiveDue, priority, list, labels, rec?.first, remind || (due?.hasTime == true))
    }
}
