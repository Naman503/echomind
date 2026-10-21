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

    /**
     * Completes a task. A repeating task is kept as done (so history is real)
     * and its next occurrence is created, subtasks reset, reminder moved along.
     */
    suspend fun complete(t: TaskRow): Long? {
        val now = System.currentTimeMillis()
        db.tasks().update(t.copy(status = "done", completedAt = now, updatedAt = now))
        reminders.cancel(t.id)
        val rule = t.recurrence ?: return null
        val base = t.dueAt ?: t.remindAt ?: now
        val nextDue = Recurrence.next(rule, base, now) ?: return null
        val offset = (t.remindAt ?: base) - base
        val next = t.copy(
            id = 0, status = "open", dueAt = nextDue, remindAt = t.remindAt?.let { nextDue + offset },
            completedAt = null, createdAt = now, updatedAt = now, dismissReason = null,
        )
        val id = db.tasks().insert(next)
        for (sub in db.tasks().subtasks(t.id)) db.tasks().insert(sub.copy(id = 0, parentId = id, status = "open", completedAt = null, createdAt = now, updatedAt = now))
        if (next.remindAt != null) reminders.schedule(next.copy(id = id))
        return id
    }

    suspend fun save(t: TaskRow) {
        val updated = t.copy(updatedAt = System.currentTimeMillis())
        db.tasks().update(updated)
        if (updated.status == "open" && updated.remindAt != null && updated.remindAt > System.currentTimeMillis()) reminders.schedule(updated)
        else reminders.cancel(updated.id)
    }

    suspend fun delete(t: TaskRow) {
        reminders.cancel(t.id)
        for (sub in db.tasks().subtasks(t.id)) db.tasks().delete(sub.id)
        db.tasks().delete(t.id)
    }

    suspend fun addSubtask(parent: TaskRow, title: String) {
        val now = System.currentTimeMillis()
        db.tasks().insert(TaskRow(title = title.trim(), status = "open", parentId = parent.id, projectId = parent.projectId, confidence = 1f, createdAt = now, updatedAt = now, origin = "user"))
    }

    suspend fun toggleSubtask(sub: TaskRow) {
        val now = System.currentTimeMillis()
        db.tasks().update(if (sub.status == "done") sub.copy(status = "open", completedAt = null, updatedAt = now) else sub.copy(status = "done", completedAt = now, updatedAt = now))
    }

    /** Moves a task (and its reminder) to a new time, keeping the gap between due and reminder. */
    suspend fun reschedule(t: TaskRow, newDue: Long?) {
        val remind = when {
            newDue == null -> null
            t.remindAt != null && t.dueAt != null -> newDue + (t.remindAt - t.dueAt)
            t.remindAt != null -> newDue
            else -> null
        }
        save(t.copy(dueAt = newDue, remindAt = remind, dueText = null))
    }

    /** Adds a task typed in quick-add syntax; creates the list if it is new. */
    suspend fun addQuick(text: String, defaultProjectId: Long? = null): Long? {
        if (text.isBlank()) return null
        val p = QuickAdd.parse(text)
        val now = System.currentTimeMillis()
        val projectId = p.list?.let { name ->
            db.projects().byName(name)?.id ?: db.projects().insert(app.echomind.data.Project(name = name.replaceFirstChar { it.uppercase() }, color = (name.hashCode() and 0x7fffffff) % 8 + 1, createdAt = now))
        } ?: defaultProjectId
        val row = TaskRow(
            title = p.title, status = "open", priority = p.priority, dueAt = p.due?.at,
            remindAt = if (p.remind) p.due?.at?.takeIf { it > now } else null,
            projectId = projectId, labels = Js.json.encodeToString(ListSerializer(String.serializer()), p.labels),
            recurrence = p.recurrence, confidence = 1f, createdAt = now, updatedAt = now, origin = "user",
        )
        val id = db.tasks().insert(row)
        if (row.remindAt != null) reminders.schedule(row.copy(id = id))
        return id
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
