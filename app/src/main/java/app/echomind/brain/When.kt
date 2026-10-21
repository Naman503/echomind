package app.echomind.brain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Turns "kal shaam 5 baje", "Friday morning" or a model's ISO guess into a
 * time, relative to when it was said. The model's ISO value is used when it is
 * plausible; the phrase is parsed as a check and as the fallback, because
 * small models get weekday arithmetic wrong.
 */
object When {
    data class Resolved(val at: Long, val hasTime: Boolean)

    private val zone get() = ZoneId.systemDefault()
    const val DEFAULT_HOUR = 9

    fun resolve(dueIso: String?, dueText: String?, saidAt: Long): Resolved? {
        val fromText = dueText?.takeIf { it.isNotBlank() }?.let { parse(it, saidAt) }
        val fromIso = dueIso?.takeIf { it.isNotBlank() }?.let { iso(it) }?.takeIf { plausible(it.at, saidAt) }
        return when {
            fromText != null && fromIso != null -> {
                // Same day: trust the model's time if the phrase had none. Different day: trust the phrase.
                val textDay = Instant.ofEpochMilli(fromText.at).atZone(zone).toLocalDate()
                val isoDay = Instant.ofEpochMilli(fromIso.at).atZone(zone).toLocalDate()
                if (textDay == isoDay) (if (fromText.hasTime) fromText else fromIso) else fromText
            }
            fromText != null -> fromText
            else -> fromIso
        }
    }

    private fun plausible(at: Long, saidAt: Long) = at > saidAt - 12 * 3_600_000L && at < saidAt + 400L * 86_400_000L

    fun iso(s: String): Resolved? = runCatching {
        val t = s.trim()
        if (t.length > 10) Resolved(LocalDateTime.parse(t.take(16)).atZone(zone).toInstant().toEpochMilli(), true)
        else Resolved(LocalDate.parse(t).atTime(DEFAULT_HOUR, 0).atZone(zone).toInstant().toEpochMilli(), false)
    }.getOrNull()

    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY, "somvar" to DayOfWeek.MONDAY, "somwar" to DayOfWeek.MONDAY, "सोमवार" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "mangalvar" to DayOfWeek.TUESDAY, "mangalwar" to DayOfWeek.TUESDAY, "मंगलवार" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY, "budhvar" to DayOfWeek.WEDNESDAY, "budhwar" to DayOfWeek.WEDNESDAY, "बुधवार" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "guruvar" to DayOfWeek.THURSDAY, "guruwar" to DayOfWeek.THURSDAY, "गुरुवार" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY, "shukravar" to DayOfWeek.FRIDAY, "shukrawar" to DayOfWeek.FRIDAY, "शुक्रवार" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY, "shanivar" to DayOfWeek.SATURDAY, "shaniwar" to DayOfWeek.SATURDAY, "शनिवार" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY, "ravivar" to DayOfWeek.SUNDAY, "raviwar" to DayOfWeek.SUNDAY, "itvaar" to DayOfWeek.SUNDAY, "रविवार" to DayOfWeek.SUNDAY,
    )

    private val clock = Regex("""(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.|baje|बजे)?""")

    /** Best-effort parse of a spoken time phrase; null if it names no day or time. */
    fun parse(text: String, saidAt: Long): Resolved? {
        val t = " " + text.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N}:.]+"), " ") + " "
        val now = Instant.ofEpochMilli(saidAt).atZone(zone).toLocalDateTime()
        var date: LocalDate? = null
        when {
            Regex(" (day after tomorrow|parso|parson|परसों) ").containsMatchIn(t) -> date = now.toLocalDate().plusDays(2)
            Regex(" (tomorrow|tmrw|kal|कल) ").containsMatchIn(t) -> date = now.toLocalDate().plusDays(1)
            Regex(" (today|tonight|aaj|आज|abhi) ").containsMatchIn(t) -> date = now.toLocalDate()
            Regex(" next week|agle hafte|अगले हफ्ते ").containsMatchIn(t) -> date = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        }
        if (date == null) {
            for ((name, dow) in weekdays) if (t.contains(" $name ")) {
                date = now.toLocalDate().with(TemporalAdjusters.next(dow)); break
            }
        }
        var time: LocalTime? = null
        val m = clock.find(t)
        if (m != null && (m.groupValues[3].isNotEmpty() || m.groupValues[2].isNotEmpty())) {
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val suffix = m.groupValues[3]
            if (suffix.startsWith("p") && h < 12) h += 12
            if (suffix.startsWith("a") && h == 12) h = 0
            // "5 baje" with no am/pm: use the part of day said, else the next sensible hour.
            if (suffix == "baje" || suffix == "बजे" || suffix.isEmpty()) {
                if (Regex("shaam|sham|evening|raat|night|शाम|रात").containsMatchIn(t) && h < 12) h += 12
                else if (Regex("dopahar|afternoon|दोपहर").containsMatchIn(t) && h < 12 && h != 12) h += 12
                else if (!Regex("subah|morning|सुबह").containsMatchIn(t) && h in 1..7) h += 12
            }
            if (h in 0..23 && min in 0..59) time = LocalTime.of(h, min)
        } else {
            time = when {
                Regex("subah|morning|सुबह").containsMatchIn(t) -> LocalTime.of(9, 0)
                Regex("dopahar|afternoon|lunch|दोपहर").containsMatchIn(t) -> LocalTime.of(14, 0)
                Regex("shaam|sham|evening|शाम").containsMatchIn(t) -> LocalTime.of(18, 0)
                Regex("raat|night|tonight|रात").containsMatchIn(t) -> LocalTime.of(21, 0)
                else -> null
            }
        }
        if (date == null && time == null) return null
        val d = date ?: now.toLocalDate().let { if (time != null && time.isBefore(now.toLocalTime())) it.plusDays(1) else it }
        val at = d.atTime(time ?: LocalTime.of(DEFAULT_HOUR, 0)).atZone(zone).toInstant().toEpochMilli()
        return Resolved(at, time != null)
    }
}
