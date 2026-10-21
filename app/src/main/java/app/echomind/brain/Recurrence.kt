package app.echomind.brain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Repeat rules, stored as short strings:
 *   daily · weekdays · weekly:MON,THU · monthly:15 · yearly · every:3:days · every:2:weeks
 */
object Recurrence {
    private val zone get() = ZoneId.systemDefault()

    val presets = listOf(
        "daily" to "Every day",
        "weekdays" to "Every weekday",
        "weekly" to "Every week",
        "monthly" to "Every month",
        "yearly" to "Every year",
    )

    fun label(rule: String?): String? {
        rule ?: return null
        return when {
            rule == "daily" -> "Every day"
            rule == "weekdays" -> "Weekdays"
            rule == "weekly" -> "Every week"
            rule.startsWith("weekly:") -> "Every " + rule.removePrefix("weekly:").split(',').joinToString(", ") { it.lowercase().replaceFirstChar(Char::uppercase) }
            rule == "monthly" -> "Every month"
            rule.startsWith("monthly:") -> "Monthly on the ${rule.removePrefix("monthly:")}"
            rule == "yearly" -> "Every year"
            rule.startsWith("every:") -> rule.split(':').let { "Every ${it.getOrNull(1)} ${it.getOrNull(2)}" }
            else -> rule
        }
    }

    private val days = mapOf("MON" to DayOfWeek.MONDAY, "TUE" to DayOfWeek.TUESDAY, "WED" to DayOfWeek.WEDNESDAY, "THU" to DayOfWeek.THURSDAY,
        "FRI" to DayOfWeek.FRIDAY, "SAT" to DayOfWeek.SATURDAY, "SUN" to DayOfWeek.SUNDAY)

    /** The next occurrence strictly after [from] (epoch ms), keeping the time of day; null if the rule is unknown. */
    fun next(rule: String, from: Long, now: Long = System.currentTimeMillis()): Long? {
        var t = Instant.ofEpochMilli(from).atZone(zone).toLocalDateTime()
        // Skip occurrences already in the past (a task completed late moves to the next future slot).
        repeat(400) {
            t = step(rule, t) ?: return null
            val ms = t.atZone(zone).toInstant().toEpochMilli()
            if (ms > now) return ms
        }
        return null
    }

    private fun step(rule: String, t: LocalDateTime): LocalDateTime? = when {
        rule == "daily" -> t.plusDays(1)
        rule == "weekdays" -> {
            var n = t.plusDays(1)
            while (n.dayOfWeek == DayOfWeek.SATURDAY || n.dayOfWeek == DayOfWeek.SUNDAY) n = n.plusDays(1)
            n
        }
        rule == "weekly" -> t.plusWeeks(1)
        rule.startsWith("weekly:") -> {
            val set = rule.removePrefix("weekly:").split(',').mapNotNull { days[it.trim().uppercase()] }.toSet()
            if (set.isEmpty()) t.plusWeeks(1) else {
                var n = t.plusDays(1)
                while (n.dayOfWeek !in set) n = n.plusDays(1)
                n
            }
        }
        rule == "monthly" -> t.plusMonths(1)
        rule.startsWith("monthly:") -> {
            val d = rule.removePrefix("monthly:").toIntOrNull() ?: t.dayOfMonth
            val m = t.plusMonths(1)
            m.withDayOfMonth(minOf(d, m.toLocalDate().with(TemporalAdjusters.lastDayOfMonth()).dayOfMonth))
        }
        rule == "yearly" -> t.plusYears(1)
        rule.startsWith("every:") -> {
            val p = rule.split(':')
            val n = p.getOrNull(1)?.toLongOrNull() ?: 1
            when (p.getOrNull(2)) {
                "weeks" -> t.plusWeeks(n)
                "months" -> t.plusMonths(n)
                else -> t.plusDays(n)
            }
        }
        else -> null
    }

    /** Finds a repeat phrase in free text ("every Monday", "har din", "daily"); returns rule and the matched text. */
    fun parse(text: String): Pair<String, String>? {
        val t = text.lowercase()
        val patterns = listOf(
            Regex("\\b(every ?day|daily|har ?din|roz|rozana)\\b") to { _: MatchResult -> "daily" },
            Regex("\\b(every ?weekday|weekdays)\\b") to { _: MatchResult -> "weekdays" },
            Regex("\\bevery (monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?\\b") to { m: MatchResult -> "weekly:" + m.groupValues[1].take(3).uppercase() },
            Regex("\\b(every ?week|weekly|har hafte)\\b") to { _: MatchResult -> "weekly" },
            Regex("\\b(every ?month|monthly|har mahine)\\b") to { _: MatchResult -> "monthly" },
            Regex("\\b(every ?year|yearly|annually)\\b") to { _: MatchResult -> "yearly" },
            Regex("\\bevery (\\d+) (day|days|week|weeks|month|months)\\b") to { m: MatchResult ->
                val unit = m.groupValues[2].trimEnd('s') + "s"
                "every:${m.groupValues[1]}:$unit"
            },
        )
        for ((re, rule) in patterns) re.find(t)?.let { return rule(it) to it.value }
        return null
    }
}
