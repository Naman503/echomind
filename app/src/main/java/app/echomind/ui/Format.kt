package app.echomind.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Human times: "4:15 PM", "Yesterday", "Tue 21 Oct", "12 min ago". */
object Fmt {
    private val zone get() = ZoneId.systemDefault()
    private val time = DateTimeFormatter.ofPattern("h:mm a")
    private val dayShort = DateTimeFormatter.ofPattern("EEE d MMM")
    private val dayLong = DateTimeFormatter.ofPattern("EEEE, d MMMM")

    fun time(at: Long): String = Instant.ofEpochMilli(at).atZone(zone).format(time)
    fun date(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    fun day(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            today.plusDays(1) -> "Tomorrow"
            else -> d.format(if (d.year == today.year) dayShort else DateTimeFormatter.ofPattern("d MMM yyyy"))
        }
    }

    fun dayLong(d: LocalDate): String = d.format(dayLong)
    fun day(at: Long) = day(date(at))
    fun dayTime(at: Long): String = "${day(at)}, ${time(at)}"

    fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
        val s = (now - at) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> day(at)
        }
    }

    fun duration(ms: Long): String {
        val m = ms / 60_000
        return when {
            ms < 60_000 -> "${ms / 1000}s"
            m < 60 -> "$m min"
            else -> "${m / 60} h ${m % 60} min"
        }
    }

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.1f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format("%.0f MB", b / (1L shl 20).toDouble())
        b >= 1024 -> "${b / 1024} KB"
        else -> "$b B"
    }

    fun span(a: Long, b: Long) = "${time(a)} – ${time(b)}"
}
