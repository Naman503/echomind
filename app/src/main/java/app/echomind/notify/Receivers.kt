package app.echomind.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.echomind.App
import app.echomind.work.DailyWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try { block() } finally { pending.finish() }
    }
}

/** Fires a reminder, and handles its Done / Snooze buttons. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_TASK, -1)
        if (id < 0) return
        val g = App.graph
        async {
            val t = g.db.tasks().get(id) ?: return@async
            // Anonymous usage: reminders fired and the button pressed on them (fixed names only).
            app.echomind.telemetry.Usage.count("reminder_action", mapOf("action" to when (intent.action) { ACTION_FIRE -> "fired"; ACTION_DONE -> "done"; ACTION_SNOOZE -> "snooze"; ACTION_TOMORROW -> "tomorrow"; else -> "other" }))
            when (intent.action) {
                ACTION_FIRE -> if (t.status == "open") g.notifier.reminder(t)
                ACTION_DONE -> { g.tasks.complete(t); g.notifier.cancelReminder(id) }
                ACTION_SNOOZE -> { g.notifier.cancelReminder(id); g.tasks.snooze(id, 15) }
                ACTION_TOMORROW -> {
                    g.notifier.cancelReminder(id)
                    val at = java.time.LocalDate.now().plusDays(1).atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    g.tasks.setReminder(t, at)
                }
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "app.echomind.REMINDER"
        const val ACTION_DONE = "app.echomind.REMINDER_DONE"
        const val ACTION_SNOOZE = "app.echomind.REMINDER_SNOOZE"
        const val ACTION_TOMORROW = "app.echomind.REMINDER_TOMORROW"
        const val EXTRA_TASK = "task"
    }
}

/** "I'm back" / "Not travelling" on the travel notification. */
class TravelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = App.graph
        async {
            when (intent.action) {
                ACTION_OFF -> { app.echomind.telemetry.Usage.count("notification_action", mapOf("action" to "travel_off")); g.travel.disable() }
                ACTION_NOT_TRAVELLING -> { app.echomind.telemetry.Usage.count("notification_action", mapOf("action" to "travel_not_travelling")); g.travel.disable(suppressHours = 12) }
            }
        }
    }

    companion object {
        const val ACTION_OFF = "app.echomind.TRAVEL_OFF"
        const val ACTION_NOT_TRAVELLING = "app.echomind.TRAVEL_NOT"
    }
}

/** "Add task" / "Dismiss" on a nudge. */
class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return
        val g = App.graph
        async {
            when (intent.action) {
                ACTION_TASK -> { app.echomind.telemetry.Usage.count("nudge_action", mapOf("action" to "add_task", "where" to "notification")); g.nudges.act(id) { g.tasks.addQuick(it, via = "nudge") } }
                ACTION_DISMISS -> { app.echomind.telemetry.Usage.count("nudge_action", mapOf("action" to "dismiss", "where" to "notification")); g.nudges.dismiss(id) }
            }
            g.notifier.cancelNudge(id)
        }
    }

    companion object {
        const val ACTION_TASK = "app.echomind.NUDGE_TASK"
        const val ACTION_DISMISS = "app.echomind.NUDGE_DISMISS"
        const val EXTRA_ID = "id"
    }
}

/** A scheduled check-in, and inline replies to quick questions. */
class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = App.graph
        when (intent.action) {
            ACTION_REPLY -> {
                val text = androidx.core.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(EXTRA_REPLY)?.toString()?.trim().orEmpty()
                async {
                    val changes = if (text.isNotEmpty()) g.checkIns.answerQuick(text) else emptyList()
                    g.notifier.quickAnswered(changes)
                }
            }
            // No check-ins on a trip: they'd use the mic, voice and AI.
            else -> intent.getStringExtra(EXTRA_SLOT)?.let { id -> if (!g.settings.value.travel) async { g.checkIns.due(id) } }
        }
    }

    companion object {
        const val EXTRA_SLOT = "slot"
        const val EXTRA_REPLY = "reply"
        const val ACTION_REPLY = "app.echomind.QUICK_REPLY"
    }
}

/** An event's reminder time: the heads-up with who you're meeting. */
class EventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return
        async { App.graph.agenda.remind(id) }
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}

/** Morning brief and evening recap alarms hand off to a worker (which may need the AI). */
class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = if (intent.action == ACTION_MORNING) "morning" else "evening"
        app.echomind.telemetry.Usage.count("daily_alarm", mapOf("kind" to kind))
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<DailyWorker>().setInputData(workDataOf("kind" to kind)).build())
        val s = App.graph.settings.value
        App.graph.reminders.scheduleDaily(s.morningBriefAt, s.eveningRecapAt)
    }

    companion object {
        const val ACTION_MORNING = "app.echomind.MORNING"
        const val ACTION_EVENING = "app.echomind.EVENING"
    }
}

/**
 * After a reboot or an update: re-arm reminders and daily alarms. The
 * microphone cannot be restarted from here (Android forbids it), so if the
 * user wants EchoMind listening, a "tap to resume" notification is posted.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = App.graph
        async {
            val s = g.settings.awaitLoaded()
            g.tasks.rescheduleAll()
            g.agenda.rescheduleAll()
            g.checkIns.schedule()
            g.reminders.scheduleDaily(s.morningBriefAt, s.eveningRecapAt)
            app.echomind.telemetry.Usage.count("device_event", mapOf("kind" to when (intent.action) { Intent.ACTION_MY_PACKAGE_REPLACED -> "app_updated"; Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> "time_changed"; else -> "boot" }, "listening_wanted" to s.captureWanted))
            if (s.captureWanted && intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) {
                g.notifier.resumePrompt(if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "EchoMind was updated." else "Your phone restarted.")
            }
        }
    }
}
