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
            when (intent.action) {
                ACTION_FIRE -> if (t.status == "open") g.notifier.reminder(t)
                ACTION_DONE -> { g.tasks.complete(t); g.notifier.cancelReminder(id) }
                ACTION_SNOOZE -> { g.notifier.cancelReminder(id); g.tasks.snooze(id, 60) }
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "app.echomind.REMINDER"
        const val ACTION_DONE = "app.echomind.REMINDER_DONE"
        const val ACTION_SNOOZE = "app.echomind.REMINDER_SNOOZE"
        const val EXTRA_TASK = "task"
    }
}

/** Morning brief and evening recap alarms hand off to a worker (which may need the AI). */
class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = if (intent.action == ACTION_MORNING) "morning" else "evening"
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
            g.reminders.scheduleDaily(s.morningBriefAt, s.eveningRecapAt)
            if (s.captureWanted && intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) {
                g.notifier.resumePrompt(if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "EchoMind was updated." else "Your phone restarted.")
            }
        }
    }
}
