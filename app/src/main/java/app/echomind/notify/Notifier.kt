package app.echomind.notify

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.echomind.MainActivity
import app.echomind.R
import app.echomind.brain.ReminderScheduler
import app.echomind.capture.CaptureService
import app.echomind.data.TaskRow
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Every notification the app posts. Calm by design: one ongoing status, one
 * grouped suggestion notice, reminders only for tasks the user accepted, a
 * morning brief and an evening recap — never a ping per conversation.
 */
class Notifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        fun ch(id: String, name: String, importance: Int, desc: String, silent: Boolean = false) =
            NotificationChannel(id, name, importance).apply {
                description = desc
                if (silent) { setSound(null, null); enableVibration(false) }
                setShowBadge(id == SUGGESTIONS || id == REMINDERS)
            }
        nm.createNotificationChannels(
            listOf(
                ch(CAPTURE, "Listening status", NotificationManager.IMPORTANCE_LOW, "Shown while EchoMind listens or works through recordings", silent = true),
                ch(REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH, "Tasks you asked to be reminded of"),
                ch(SUGGESTIONS, "Suggestions to review", NotificationManager.IMPORTANCE_DEFAULT, "Tasks and reminders the AI found, waiting for your OK", silent = true),
                ch(BRIEF, "Morning brief", NotificationManager.IMPORTANCE_DEFAULT, "Today's reminders and open loops"),
                ch(RECAP, "Evening recap", NotificationManager.IMPORTANCE_DEFAULT, "Your day in a few lines, and your journal"),
                ch(ATTENTION, "Needs attention", NotificationManager.IMPORTANCE_HIGH, "Listening stopped, permissions, storage"),
            ),
        )
    }

    private fun can() = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun open(route: String? = null, requestCode: Int = 0): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Actions on the ongoing notification go to the service itself, which counts as a user interaction. */
    private fun serviceAction(action: String, code: Int, extra: Pair<String, Long>? = null): PendingIntent =
        PendingIntent.getForegroundService(
            context, code,
            Intent(context, CaptureService::class.java).setAction(action).apply { extra?.let { putExtra(it.first, it.second) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    data class Status(val title: String, val text: String, val recording: Boolean, val paused: Boolean, val progress: Int = -1)

    fun capture(s: Status): Notification {
        val b = NotificationCompat.Builder(context, CAPTURE)
            .setSmallIcon(if (s.recording) R.drawable.ic_stat_listen else R.drawable.ic_stat_idle)
            .setContentTitle(s.title)
            .setContentText(s.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open())
        if (s.progress in 0..100) b.setProgress(100, s.progress, false)
        if (s.recording) {
            b.addAction(0, "Pause 30 min", serviceAction(CaptureService.ACTION_PAUSE, 11, CaptureService.EXTRA_MINUTES to 30L))
            val input = RemoteInput.Builder(CaptureService.EXTRA_NOTE).setLabel("Note to remember").build()
            b.addAction(
                NotificationCompat.Action.Builder(0, "Add note", PendingIntent.getForegroundService(
                    context, 12, Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_NOTE),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )).addRemoteInput(input).build(),
            )
        } else if (s.paused) {
            b.addAction(0, "Resume", serviceAction(CaptureService.ACTION_RESUME, 13))
        }
        b.addAction(0, "Stop", serviceAction(CaptureService.ACTION_STOP, 14))
        return b.build()
    }

    fun reminder(t: TaskRow) {
        if (!can()) return
        val done = actionBroadcast(ReminderReceiver.ACTION_DONE, t.id, 1)
        val snooze = actionBroadcast(ReminderReceiver.ACTION_SNOOZE, t.id, 2)
        val tomorrow = actionBroadcast(ReminderReceiver.ACTION_TOMORROW, t.id, 3)
        val n = NotificationCompat.Builder(context, REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_listen)
            .setContentTitle(t.title)
            .setContentText(t.quote?.let { "“$it”" } ?: t.dueText ?: "Reminder")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(t.detail, t.quote?.let { "You said: “$it”" }).joinToString("\n").ifEmpty { t.title }))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open("reminders", (t.id % 100_000).toInt()))
            .addAction(0, "Done", done)
            .addAction(0, "Snooze 15 min", snooze)
            .addAction(0, "Tomorrow", tomorrow)
            .build()
        nm.notify(REMINDER_BASE + (t.id % 100_000).toInt(), n)
    }

    fun cancelReminder(taskId: Long) = nm.cancel(REMINDER_BASE + (taskId % 100_000).toInt())

    private fun actionBroadcast(action: String, taskId: Long, code: Int) = PendingIntent.getBroadcast(
        context, (taskId % 100_000).toInt() * 10 + code,
        Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(ReminderReceiver.EXTRA_TASK, taskId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun suggestions(count: Int, preview: List<String>) {
        if (!can() || count <= 0) return
        val n = NotificationCompat.Builder(context, SUGGESTIONS)
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle("$count ${if (count == 1) "suggestion" else "suggestions"} to review")
            .setContentText(preview.firstOrNull() ?: "Tasks the AI found in your conversations")
            .setStyle(NotificationCompat.InboxStyle().also { st -> preview.take(4).forEach { st.addLine(it) } })
            .setNumber(count)
            .setAutoCancel(true)
            .setContentIntent(open("tasks", 300))
            .build()
        nm.notify(SUGGESTIONS_ID, n)
    }

    fun clearSuggestions() = nm.cancel(SUGGESTIONS_ID)

    fun brief(title: String, lines: List<String>) {
        if (!can()) return
        nm.notify(BRIEF_ID, NotificationCompat.Builder(context, BRIEF)
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "")
            .setStyle(NotificationCompat.InboxStyle().also { st -> lines.take(6).forEach { st.addLine(it) } })
            .setAutoCancel(true)
            .setContentIntent(open("today", 301))
            .build())
    }

    fun recap(headline: String, text: String, suggestions: Int) {
        if (!can()) return
        nm.notify(RECAP_ID, NotificationCompat.Builder(context, RECAP)
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle(headline)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + if (suggestions > 0) "\n\n$suggestions suggestions waiting for review." else ""))
            .setAutoCancel(true)
            .setContentIntent(open("journal", 302))
            .build())
    }

    /** "Listening stopped — tap to resume": the tap is what Android requires to start the microphone again. */
    fun resumePrompt(reason: String) {
        if (!can()) return
        nm.notify(ATTENTION_ID, NotificationCompat.Builder(context, ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle("EchoMind isn't listening")
            .setContentText("$reason Tap to resume.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason Tap to resume listening."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(serviceAction(CaptureService.ACTION_START, 15))
            .addAction(0, "Resume listening", serviceAction(CaptureService.ACTION_START, 16))
            .build())
    }

    fun clearResumePrompt() = nm.cancel(ATTENTION_ID)

    fun attention(title: String, text: String, route: String? = null) {
        if (!can()) return
        nm.notify(ATTENTION_ID + 1, NotificationCompat.Builder(context, ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(route, 303))
            .build())
    }

    companion object {
        const val CAPTURE = "capture"
        const val REMINDERS = "reminders"
        const val SUGGESTIONS = "suggestions"
        const val BRIEF = "brief"
        const val RECAP = "recap"
        const val ATTENTION = "attention"
        const val CAPTURE_ID = 4107
        const val SUGGESTIONS_ID = 4200
        const val BRIEF_ID = 4201
        const val RECAP_ID = 4202
        const val ATTENTION_ID = 4300
        const val REMINDER_BASE = 100_000
    }
}

/** Exact alarms for accepted reminders; inexact when the user hasn't allowed exact ones. */
class ReminderAlarms(private val context: Context) : ReminderScheduler {
    private val am = context.getSystemService(AlarmManager::class.java)

    private fun pi(taskId: Long) = PendingIntent.getBroadcast(
        context, (taskId % 1_000_000).toInt(),
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).putExtra(ReminderReceiver.EXTRA_TASK, taskId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun schedule(task: TaskRow) {
        val at = task.remindAt ?: return
        if (at <= System.currentTimeMillis()) return
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(task.id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(task.id))
    }

    override fun cancel(taskId: Long) {
        am.cancel(pi(taskId))
        Notifier(context).cancelReminder(taskId)
    }

    /** Daily alarms for the morning brief and evening recap. */
    fun scheduleDaily(morningMinute: Int, eveningMinute: Int) {
        fun next(minute: Int): Long {
            val zone = ZoneId.systemDefault()
            var t = LocalDate.now().atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone)
            if (t.toInstant().toEpochMilli() <= System.currentTimeMillis()) t = t.plusDays(1)
            return t.toInstant().toEpochMilli()
        }
        fun daily(action: String, code: Int) = PendingIntent.getBroadcast(context, code,
            Intent(context, DailyReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setWindow(AlarmManager.RTC_WAKEUP, next(morningMinute), 10 * 60_000L, daily(DailyReceiver.ACTION_MORNING, 9001))
        am.setWindow(AlarmManager.RTC_WAKEUP, next(eveningMinute), 15 * 60_000L, daily(DailyReceiver.ACTION_EVENING, 9002))
    }
}
