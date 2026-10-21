package app.echomind.brain

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import app.echomind.ai.Prompts
import app.echomind.core.SettingsStore
import app.echomind.data.Db
import app.echomind.data.EventRow
import app.echomind.data.Kv
import app.echomind.notify.EventReceiver
import app.echomind.notify.Notifier
import app.echomind.util.Js
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * The calendar: EchoMind's own events (found in conversations or added by
 * you), merged with the phone's calendars when you allow it. Before an event
 * it tells you who you're about to see and what's open with them.
 */
class Agenda(
    private val context: Context,
    private val db: Db,
    private val settings: SettingsStore,
    private val people: People,
    private val notifier: Notifier,
) {
    private val zone get() = ZoneId.systemDefault()
    private val am = context.getSystemService(AlarmManager::class.java)

    fun canRead() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun canWrite() = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Own events and the phone's, in time order. Phone events have source "device" and id 0. */
    suspend fun range(from: Long, to: Long): List<EventRow> {
        val own = db.events().range(from, to)
        val written = own.mapNotNull { it.deviceId }.toSet()
        val device = if (settings.value.showPhoneCalendar) readDevice(from, to).filter { it.deviceId !in written } else emptyList()
        return (own + device).sortedBy { it.startAt }
    }

    private suspend fun readDevice(from: Long, to: Long): List<EventRow> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val cols = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        val out = ArrayList<EventRow>()
        runCatching {
            context.contentResolver.query(uri, cols, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(4) == 1
                    // All-day instances are stored in UTC midnight; shift them to the local day.
                    val begin = if (allDay) LocalDate.ofEpochDay(c.getLong(2) / 86_400_000L).atStartOfDay(zone).toInstant().toEpochMilli() else c.getLong(2)
                    out.add(EventRow(title = c.getString(1) ?: "(no title)", startAt = begin, endAt = if (allDay) null else c.getLong(3), allDay = allDay,
                        location = c.getString(5)?.takeIf { it.isNotBlank() }, notes = c.getString(6)?.takeIf { it.isNotBlank() }?.take(500),
                        source = "device", deviceId = c.getLong(0), quote = c.getString(7), createdAt = 0, updatedAt = 0))
                }
            }
        }.onFailure { Log.w(TAG, "calendar read failed", it) }
        out
    }

    data class Found(val title: String, val start: Long, val end: Long?, val allDay: Boolean, val location: String?, val people: List<String>, val confidence: Float, val quote: String?)

    /** Events heard in a conversation become suggestions (or confirmed, when very clear and you allow it). */
    suspend fun addFound(found: List<Found>, conversationId: Long, at: Long): Int {
        var n = 0
        val now = System.currentTimeMillis()
        for (f in found) {
            if (f.title.isBlank() || f.start < now - 86_400_000L || f.confidence < 0.5f) continue
            val day = dayBounds(f.start)
            val sameDay = db.events().range(day.first, day.second)
            if (sameDay.any { TextNorm.keyTerms(it.title).toSet().intersect(TextNorm.keyTerms(f.title).toSet()).isNotEmpty() }) continue
            val sure = settings.value.autoAcceptConfident && f.confidence >= 0.85f
            val e = EventRow(title = f.title.trim().take(90), startAt = f.start, endAt = f.end, allDay = f.allDay, location = f.location,
                people = Js.json.encodeToString(ListSerializer(String.serializer()), f.people.take(8)), source = "ai",
                status = if (sure) "confirmed" else "suggested", conversationId = conversationId, quote = f.quote, createdAt = at, updatedAt = at)
            val id = db.events().insert(e)
            if (sure) afterConfirm(e.copy(id = id))
            n++
        }
        return n
    }

    suspend fun save(e: EventRow): Long {
        val now = System.currentTimeMillis()
        val id = if (e.id == 0L) db.events().insert(e.copy(createdAt = now, updatedAt = now)) else { db.events().update(e.copy(updatedAt = now)); e.id }
        val saved = db.events().get(id) ?: return id
        if (saved.status == "confirmed") afterConfirm(saved)
        return id
    }

    suspend fun confirm(e: EventRow) = save(e.copy(status = "confirmed"))

    suspend fun dismiss(e: EventRow) {
        db.events().update(e.copy(status = "cancelled", updatedAt = System.currentTimeMillis()))
        am.cancel(alarm(e.id))
    }

    suspend fun delete(e: EventRow) {
        am.cancel(alarm(e.id))
        e.deviceId?.takeIf { e.source != "device" && canWrite() }?.let { id ->
            runCatching { context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) }
        }
        db.events().delete(e.id)
    }

    /** A confirmed event gets its reminder alarm, and a copy in the phone calendar if you chose that. */
    private suspend fun afterConfirm(e: EventRow) {
        schedule(e)
        if (settings.value.writeToPhoneCalendar && e.deviceId == null && canWrite()) {
            writeDevice(e)?.let { db.events().update(e.copy(deviceId = it)) }
        }
    }

    private suspend fun writeDevice(e: EventRow): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val calId = context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                null, "${CalendarContract.Calendars.IS_PRIMARY} DESC")?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return@runCatching null
            val v = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, e.title)
                put(CalendarContract.Events.DTSTART, e.startAt)
                put(CalendarContract.Events.DTEND, e.endAt ?: (e.startAt + if (e.allDay) 86_400_000L else 3_600_000L))
                put(CalendarContract.Events.ALL_DAY, if (e.allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, if (e.allDay) "UTC" else TimeZone.getDefault().id)
                e.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                put(CalendarContract.Events.DESCRIPTION, listOfNotNull(e.notes, e.quote?.let { "“$it”" }, "Added by EchoMind").joinToString("\n"))
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)?.let { ContentUris.parseId(it) }
        }.onFailure { Log.w(TAG, "calendar write failed", it) }.getOrNull()
    }

    private fun alarm(id: Long) = PendingIntent.getBroadcast(context, 700_000 + (id % 100_000).toInt(),
        Intent(context, EventReceiver::class.java).putExtra(EventReceiver.EXTRA_ID, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(e: EventRow) {
        if (e.allDay || e.remindBeforeMin < 0) return
        val at = e.startAt - e.remindBeforeMin * 60_000L
        if (at <= System.currentTimeMillis()) return
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(e.id)) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(e.id))
    }

    /** After a reboot: every upcoming confirmed event gets its alarm back. */
    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        db.events().range(now, now + 60 * 86_400_000L).filter { it.status == "confirmed" }.forEach { schedule(it) }
    }

    /** "In 15 min: Budget review with Priya" plus who Priya is and what's open with her. */
    suspend fun prep(e: EventRow): List<String> {
        val lines = ArrayList<String>()
        val everyone = db.memory().allEntities().filter { it.kind == "person" }
        val named = runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), e.people) }.getOrDefault(emptyList())
        val text = (e.title + " " + (e.notes ?: "")).lowercase()
        val who = everyone.filter { p -> named.any { it.equals(p.name, true) } || Regex("\\b${Regex.escape(p.name.lowercase().substringBefore(' '))}\\b").containsMatchIn(text) }
            .distinctBy { it.id }.take(3)
        val rels = db.relations().all()
        for (p in who) {
            val clue = people.clue(p, rels)
            lines.add(if (clue.isNotBlank()) "${p.name}: $clue." else "${p.name}.")
            people.details(p).take(2).forEach { lines.add("Remember: $it") }
            val last = db.conversations().inRangeOnce(0, System.currentTimeMillis()).filter { (it.people ?: "").lowercase().contains(p.name.lowercase()) && it.state == "done" }.maxByOrNull { it.startAt }
            last?.let { lines.add("Last talked ${Prompts.day(it.startAt).substringBefore(',')}: ${it.title}.") }
            val first = p.name.lowercase().substringBefore(' ')
            db.tasks().observeOpen().first().filter { it.title.lowercase().contains(first) }.take(2).forEach { lines.add("Open: ${it.title}") }
        }
        e.location?.let { lines.add("At $it.") }
        return lines
    }

    /** Called by the alarm. */
    suspend fun remind(id: Long) {
        val e = db.events().get(id) ?: return
        if (e.status != "confirmed") return
        notifier.eventSoon(e, prep(e))
        db.memory().putKv(Kv("prepped:${e.id}:${e.startAt}", "1", System.currentTimeMillis()))
    }

    /** Every 15 minutes: phone-calendar events starting soon get the same heads-up (once). */
    suspend fun checkUpcoming() {
        if (!settings.value.eventPrep || !settings.value.showPhoneCalendar) return
        val now = System.currentTimeMillis()
        for (e in readDevice(now + 5 * 60_000L, now + 35 * 60_000L)) {
            if (e.allDay) continue
            val key = "prepped:d${e.deviceId}:${e.startAt}"
            if (db.memory().kv(key) != null) continue
            db.memory().putKv(Kv(key, "1", now))
            val lines = prep(e)
            if (lines.isNotEmpty()) notifier.eventSoon(e, lines)
        }
    }

    /** Spoken week ahead: events and due tasks for the next seven days. */
    suspend fun weekAhead(): Briefing.Script {
        val today = LocalDate.now()
        val parts = ArrayList<String>()
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = range(from, to)
        val due = db.tasks().observeOpen().first().filter { it.dueAt != null && it.dueAt in from until to }
        if (events.isEmpty() && due.isEmpty()) return Briefing.Script("Week ahead", listOf("Nothing is on your calendar for the next seven days, and no tasks are due."))
        parts.add("Here's your week. ${events.size} event${if (events.size == 1) "" else "s"} and ${due.size} task${if (due.size == 1) "" else "s"} due.")
        for (d in 0 until 7) {
            val day = today.plusDays(d.toLong())
            val (a, b) = day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val ev = events.filter { it.startAt in a until b }
            val ts = due.filter { it.dueAt!! in a until b }
            if (ev.isEmpty() && ts.isEmpty()) continue
            val name = when (d) { 0 -> "Today"; 1 -> "Tomorrow"; else -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) }
            val bits = ev.map { if (it.allDay) it.title else "${it.title} at ${Prompts.time(it.startAt)}" } + ts.map { "${it.title} is due" }
            parts.add("$name: ${bits.joinToString("; ")}.")
        }
        return Briefing.Script("Week ahead", parts)
    }

    companion object {
        private const val TAG = "EchoAgenda"
    }
}

/** Start and end of the local day containing [at]. */
private fun dayBounds(at: Long): Pair<Long, Long> {
    val z = ZoneId.systemDefault()
    val d = java.time.Instant.ofEpochMilli(at).atZone(z).toLocalDate()
    return d.atStartOfDay(z).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
}
