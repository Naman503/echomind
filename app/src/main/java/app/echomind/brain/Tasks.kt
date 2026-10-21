package app.echomind.brain

import app.echomind.core.SettingsStore
import app.echomind.data.Db
import app.echomind.data.TaskRow
import app.echomind.util.Js
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Schedules and cancels reminder alarms; implemented by the notification layer. */
interface ReminderScheduler {
    fun schedule(task: TaskRow)
    fun cancel(taskId: Long)
}

/**
 * Tasks and reminders. What the AI finds lands in a review inbox
 * ("suggested") — the category's biggest complaint is assistants that flood
 * people with to-dos from TV and small talk. Only accepted tasks notify;
 * unreviewed suggestions expire after three days.
 */
class Tasks(private val db: Db, private val settings: SettingsStore, private val reminders: ReminderScheduler) {

    data class Found(
        val title: String,
        val owner: String,
        val dueText: String?,
        val due: When.Resolved?,
        val isReminder: Boolean,
        val confidence: Float,
        val quote: String?,
        val sourceIds: List<Long>,
    )

    private fun key(s: String) = TextNorm.keyTerms(s).toSet()

    private fun sameTask(a: String, b: String): Boolean {
        val x = key(a); val y = key(b)
        if (x.isEmpty() || y.isEmpty()) return a.equals(b, true)
        return x.intersect(y).size.toDouble() / minOf(x.size, y.size) >= 0.6
    }

    /** Adds what a conversation produced; returns how many new suggestions were made. */
    suspend fun addFound(found: List<Found>, conversationId: Long, at: Long, origin: String = "ai"): Int {
        val known = db.tasks().live().map { it.title }.toMutableList()
        var added = 0
        for (f in found) {
            if (f.title.isBlank() || f.confidence < 0.35f) continue
            // Also catches the same task found twice in one conversation (two parts of a long one).
            if (known.any { sameTask(it, f.title) }) continue
            known.add(f.title)
            val confident = f.owner == "me" && f.confidence >= 0.85f && f.due != null
            val autoAccept = origin == "voice" || (settings.value.autoAcceptConfident && confident)
            val remindAt = f.due?.let { d -> if (f.isReminder || d.hasTime || f.owner == "me") d.at else null }?.takeIf { it > System.currentTimeMillis() }
            val row = TaskRow(
                title = f.title.trim().take(140),
                owner = f.owner,
                status = if (autoAccept) "open" else "suggested",
                dueAt = f.due?.at,
                dueText = f.dueText?.takeIf { it.isNotBlank() },
                remindAt = remindAt,
                confidence = f.confidence,
                conversationId = conversationId.takeIf { it > 0 },
                sources = Js.json.encodeToString(ListSerializer(Long.serializer()), f.sourceIds),
                quote = f.quote?.takeIf { it.isNotBlank() }?.take(240),
                createdAt = at,
                updatedAt = at,
                origin = origin,
            )
            val id = db.tasks().insert(row)
            if (row.status == "open" && row.remindAt != null) reminders.schedule(row.copy(id = id))
            added++
        }
        return added
    }

    suspend fun accept(t: TaskRow) {
        val now = System.currentTimeMillis()
        val updated = t.copy(status = "open", updatedAt = now)
        db.tasks().update(updated)
        if (updated.remindAt != null && updated.remindAt > now) reminders.schedule(updated)
    }

    suspend fun dismiss(t: TaskRow, reason: String) {
        db.tasks().update(t.copy(status = "dismissed", dismissReason = reason, updatedAt = System.currentTimeMillis()))
        reminders.cancel(t.id)
    }

    suspend fun complete(t: TaskRow) {
        val now = System.currentTimeMillis()
        db.tasks().update(t.copy(status = "done", completedAt = now, updatedAt = now))
        reminders.cancel(t.id)
    }

    suspend fun reopen(t: TaskRow) = db.tasks().update(t.copy(status = "open", completedAt = null, updatedAt = System.currentTimeMillis()))

    suspend fun setReminder(t: TaskRow, at: Long?) {
        val updated = t.copy(remindAt = at, dueAt = t.dueAt ?: at, status = if (t.status == "suggested") "open" else t.status, updatedAt = System.currentTimeMillis())
        db.tasks().update(updated)
        if (at != null) reminders.schedule(updated) else reminders.cancel(t.id)
    }

    suspend fun snooze(id: Long, minutes: Int) {
        val t = db.tasks().get(id) ?: return
        setReminder(t, System.currentTimeMillis() + minutes * 60_000L)
    }

    suspend fun addByUser(title: String, remindAt: Long?): Long {
        val now = System.currentTimeMillis()
        val row = TaskRow(title = title.trim(), status = "open", remindAt = remindAt, dueAt = remindAt, confidence = 1f, createdAt = now, updatedAt = now, origin = "user")
        val id = db.tasks().insert(row)
        if (remindAt != null) reminders.schedule(row.copy(id = id))
        return id
    }

    /** Re-arms every future reminder (after a reboot or an app update). */
    suspend fun rescheduleAll() {
        for (t in db.tasks().upcomingReminders(System.currentTimeMillis())) reminders.schedule(t)
    }

    suspend fun expireOld() {
        val now = System.currentTimeMillis()
        db.tasks().expireSuggestions(now - 72 * 3_600_000L, now)
    }
}
