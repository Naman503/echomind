package app.echomind.notify

import android.widget.RemoteViews
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
                // The pinned dashboard: default importance keeps it at the top of the shade; silent, never buzzes.
                ch(DASH, "EchoMind dashboard", NotificationManager.IMPORTANCE_DEFAULT, "The pinned EchoMind card while it listens or works: status, today's numbers, travel mode", silent = true),
                ch(REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH, "Tasks you asked to be reminded of"),
                ch(SUGGESTIONS, "Suggestions to review", NotificationManager.IMPORTANCE_DEFAULT, "Tasks and reminders the AI found, waiting for your OK", silent = true),
                ch(BRIEF, "Morning brief", NotificationManager.IMPORTANCE_DEFAULT, "Today's reminders and open loops"),
                ch(RECAP, "Evening recap", NotificationManager.IMPORTANCE_DEFAULT, "Your day in a few lines, and your journal"),
                ch(NUDGES, "Nudges", NotificationManager.IMPORTANCE_DEFAULT, "Things the assistant thinks you should know — a few a day at most"),
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

    data class Status(
        val title: String, val text: String, val recording: Boolean, val paused: Boolean, val progress: Int = -1,
        /** Short phone health for the collapsed line: "38°C · 72%". */
        val sub: String? = null,
        /** Lines for the expanded notification: what's running, queue, phone, today. */
        val details: List<String> = emptyList(),
        /** The dashboard: a few numbers (value, label), talk per hour today (6 AM →), travel state. Empty = plain notification. */
        val tiles: List<Pair<String, String>> = emptyList(),
        val hours: List<Float> = emptyList(),
        val travel: Boolean = false,
    )

    fun capture(s: Status): Notification = if (s.tiles.isNotEmpty()) dashboard(s) else plain(s)

    private fun travelAction(action: String, code: Int, profile: String? = null): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, TravelReceiver::class.java).setAction(action).apply { profile?.let { putExtra(TravelReceiver.EXTRA_PROFILE, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The pinned EchoMind card: status, today's numbers, talk by hour, progress, and buttons for
     * listening, travel mode and processing. Draws its own dark card so it reads on any phone's
     * notification shade. If it's swiped away while EchoMind works, it comes straight back.
     */
    private fun dashboard(s: Status): Notification {
        fun button(label: String, intent: PendingIntent, on: Boolean = false) = RemoteViews(context.packageName, R.layout.notif_dash_button).apply {
            setTextViewText(R.id.button, label)
            setOnClickPendingIntent(R.id.button, intent)
            if (on) { setInt(R.id.button, "setBackgroundResource", R.drawable.notif_btn_on_bg); setTextColor(R.id.button, 0xFF1B1B1F.toInt()) }
        }
        fun head(v: RemoteViews) {
            v.setTextViewText(R.id.title, s.title)
            v.setTextViewText(R.id.text, s.text)
            v.setTextViewText(R.id.sub, s.sub.orEmpty())
            v.setViewVisibility(R.id.dot, if (s.recording) android.view.View.VISIBLE else android.view.View.GONE)
        }
        val small = RemoteViews(context.packageName, R.layout.notif_dash_small).also(::head)
        val big = RemoteViews(context.packageName, R.layout.notif_dash_big).also(::head)
        s.tiles.take(4).forEach { (v, l) ->
            big.addView(R.id.tiles, RemoteViews(context.packageName, R.layout.notif_dash_tile).apply { setTextViewText(R.id.value, v); setTextViewText(R.id.label, l) })
        }
        if (s.hours.any { it > 0 }) big.setImageViewBitmap(R.id.chart, app.echomind.surfaces.ChartBitmaps.bars(720, 68, s.hours, color = 0xFF8C96FF.toInt(), highlight = java.time.LocalTime.now().hour - 6, accent = 0xFFFFB066.toInt()))
        else big.setViewVisibility(R.id.chart, android.view.View.GONE)
        if (s.progress in 0..100) big.setProgressBar(R.id.progress, 100, s.progress, false) else big.setViewVisibility(R.id.progress, android.view.View.GONE)
        // Row 1: listening; row 2: travel mode and processing.
        when {
            s.recording -> big.addView(R.id.buttons, button("Pause 30 min", serviceAction(CaptureService.ACTION_PAUSE, 11, CaptureService.EXTRA_MINUTES to 30L)))
            s.paused -> big.addView(R.id.buttons, button("Resume", serviceAction(CaptureService.ACTION_RESUME, 13)))
            else -> big.addView(R.id.buttons, button("Listen", serviceAction(CaptureService.ACTION_START, 16)))
        }
        big.addView(R.id.buttons, button("Process now", serviceAction(CaptureService.ACTION_PROCESS, 15)))
        big.addView(R.id.buttons, button("Stop", serviceAction(CaptureService.ACTION_STOP, 14)))
        if (s.travel) big.addView(R.id.buttons2, button("✈ Travel on · I'm back", travelAction(TravelReceiver.ACTION_OFF, 331), on = true))
        else {
            big.addView(R.id.buttons2, button("✈ Travel: rest", travelAction(TravelReceiver.ACTION_ON, 332, "rest")))
            big.addView(R.id.buttons2, button("✈ Note places", travelAction(TravelReceiver.ACTION_ON, 333, "places")))
            big.addView(R.id.buttons2, button("✈ Record only", travelAction(TravelReceiver.ACTION_ON, 334, "record")))
        }
        return NotificationCompat.Builder(context, DASH)
            .setSmallIcon(if (s.recording) R.drawable.ic_stat_listen else R.drawable.ic_stat_idle)
            .setContentTitle(s.title)
            .setContentText(s.text)
            .setSubText(s.sub)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setOngoing(true)
            // Not setSilent: that files it under "Silent" at the bottom. The channel itself makes no sound.
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open())
            // Swiped away (Android 14+ allows it) while EchoMind is working: it's put back.
            .setDeleteIntent(serviceAction(CaptureService.ACTION_REPOST, 17))
            .build()
    }

    private fun plain(s: Status): Notification {
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
        s.sub?.let { b.setSubText(it) }
        if (s.details.isNotEmpty()) b.setStyle(NotificationCompat.InboxStyle().setBigContentTitle(s.title).also { st -> s.details.take(7).forEach { st.addLine(it) } })
        if (s.recording || s.paused) b.addAction(0, "Process now", serviceAction(CaptureService.ACTION_PROCESS, 15))
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

    private fun speak(title: String, body: String, code: Int): PendingIntent = PendingIntent.getForegroundService(
        context, code,
        Intent(context, app.echomind.tts.PlaybackService::class.java).setAction(app.echomind.tts.PlaybackService.ACTION_SPEAK)
            .putExtra(app.echomind.tts.PlaybackService.EXTRA_TITLE, title).putExtra(app.echomind.tts.PlaybackService.EXTRA_TEXT, body),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun nudge(n: app.echomind.data.Nudge) {
        if (!can()) return
        fun receiver(action: String, code: Int) = PendingIntent.getBroadcast(context, (n.id % 10_000).toInt() * 10 + code,
            Intent(context, NudgeReceiver::class.java).setAction(action).putExtra(NudgeReceiver.EXTRA_ID, n.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(context, NUDGES)
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setPriority(if (n.urgency >= 3) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open(if (n.conversationId != null) "conversation:${n.conversationId}" else "today", 400 + (n.id % 1000).toInt()))
            .addAction(0, "Listen", speak(n.title, n.body, 500 + (n.id % 1000).toInt()))
        if (n.taskText != null) b.addAction(0, "Add task", receiver(NudgeReceiver.ACTION_TASK, 1))
        b.addAction(0, "Dismiss", receiver(NudgeReceiver.ACTION_DISMISS, 2))
        nm.notify(NUDGE_BASE + (n.id % 1000).toInt(), b.build())
    }

    fun eventSoon(e: app.echomind.data.EventRow, prep: List<String>) {
        if (!can()) return
        val mins = ((e.startAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        val title = if (mins <= 1) "Now: ${e.title}" else "In $mins min: ${e.title}"
        val body = prep.joinToString("\n").ifBlank { "At ${app.echomind.ai.Prompts.time(e.startAt)}${e.location?.let { " · $it" } ?: ""}" }
        val code = ((e.deviceId ?: e.id) % 1000).toInt()
        nm.notify(EVENT_BASE + code, NotificationCompat.Builder(context, REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle(title)
            .setContentText(prep.firstOrNull() ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open("calendar", 800 + code))
            .addAction(0, "Listen", speak(title, "$title. $body", 900 + code))
            .build())
    }

    /** "Evening check-in — 6 questions" → opens the check-in. */
    fun checkInPrompt(slot: app.echomind.core.CheckInSlot, facts: Int) {
        if (!can()) return
        nm.notify(CHECKIN_ID, NotificationCompat.Builder(context, NUDGES)
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle("${slot.kind.replaceFirstChar { it.uppercase() }} check-in")
            .setContentText(if (facts > 0) "A few questions about your day — $facts things to confirm. Skip any you like." else "A few questions about your day. Skip any you like.")
            .setAutoCancel(true)
            .setContentIntent(open("checkin:${slot.id}", 970))
            .build())
    }

    /** One quick question, answered inline from the notification. */
    fun quickQuestion(question: String, why: String) {
        if (!can()) return
        val reply = RemoteInput.Builder(CheckInReceiver.EXTRA_REPLY).setLabel("Your answer (or “skip”)").build()
        val pi = PendingIntent.getBroadcast(context, 971, Intent(context, CheckInReceiver::class.java).setAction(CheckInReceiver.ACTION_REPLY),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(QUICKQ_ID, NotificationCompat.Builder(context, NUDGES)
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle(question)
            .setContentText(why)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$question\n$why"))
            .setAutoCancel(true)
            .setTimeoutAfter(3 * 3_600_000L)
            .addAction(NotificationCompat.Action.Builder(0, "Answer", pi).addRemoteInput(reply).build())
            .build())
    }

    fun quickAnswered(changes: List<String>) {
        if (!can()) return
        nm.notify(QUICKQ_ID, NotificationCompat.Builder(context, NUDGES).setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle("Thanks").setContentText(changes.firstOrNull() ?: "Noted.").setTimeoutAfter(8_000).build())
    }

    /** "No earphones — play on the speaker?" when the app isn't open. */
    fun speakerPrompt(title: String) {
        if (!can()) return
        fun act(action: String, code: Int) = PendingIntent.getForegroundService(context, code,
            Intent(context, app.echomind.tts.PlaybackService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(SPEAKER_ID, NotificationCompat.Builder(context, NUDGES)
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle("No earphones connected")
            .setContentText("Read “$title” on the phone speaker?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .addAction(0, "Play on speaker", act(app.echomind.tts.PlaybackService.ACTION_SPEAKER_OK, 951))
            .addAction(0, "Dismiss", act(app.echomind.tts.PlaybackService.ACTION_SPEAKER_NO, 952))
            .build())
    }

    fun cancelSpeakerPrompt() = nm.cancel(SPEAKER_ID)

    fun cancelNudge(id: Long) = nm.cancel(NUDGE_BASE + (id % 1000).toInt())

    /** Travel mode's lasting notification, with "I'm back" (and "Not travelling" when it switched on by itself). */
    fun travel(on: Boolean, title: String, text: String, auto: Boolean) {
        if (!on) { nm.cancel(TRAVEL_ID); return }
        if (!can()) return
        if (nm.getNotificationChannel(TRAVEL) == null) nm.createNotificationChannel(NotificationChannel(TRAVEL, "Travel mode", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while EchoMind rests to save battery on a trip"; setShowBadge(false) })
        fun action(a: String, code: Int) = PendingIntent.getBroadcast(context, code, Intent(context, TravelReceiver::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(context, TRAVEL)
            .setSmallIcon(R.drawable.ic_travel)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setContentIntent(open("travel", 320))
            .addAction(0, "I'm back", action(TravelReceiver.ACTION_OFF, 321))
        if (auto) b.addAction(0, "Not travelling", action(TravelReceiver.ACTION_NOT_TRAVELLING, 322))
        nm.notify(TRAVEL_ID, b.build())
    }

    /** After a trip: what happens next. */
    fun travelBack(text: String) {
        if (!can()) return
        nm.notify(TRAVEL_ID + 1, NotificationCompat.Builder(context, BRIEF).setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle("Welcome back").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(open("today", 323)).build())
    }

    fun brief(title: String, lines: List<String>) {
        if (!can()) return
        nm.notify(BRIEF_ID, NotificationCompat.Builder(context, BRIEF)
            .addAction(0, "Listen", PendingIntent.getForegroundService(context, 310,
                Intent(context, app.echomind.tts.PlaybackService::class.java).setAction(app.echomind.tts.PlaybackService.ACTION_BRIEF), PendingIntent.FLAG_IMMUTABLE))
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "")
            .setStyle(NotificationCompat.InboxStyle().also { st -> lines.take(6).forEach { st.addLine(it) } })
            .setAutoCancel(true)
            .setContentIntent(open("today", 301))
            .build())
    }

    /** The evening recap; with [picture] (a day card) it expands to the chart, else to the text. */
    fun recap(headline: String, text: String, suggestions: Int, picture: android.graphics.Bitmap? = null, icon: android.graphics.Bitmap? = null) {
        if (!can()) return
        val more = if (suggestions > 0) "\n\n$suggestions suggestions waiting for review." else ""
        nm.notify(RECAP_ID, NotificationCompat.Builder(context, RECAP)
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle(headline)
            .setContentText(text)
            .also { b -> icon?.let { b.setLargeIcon(it) } }
            .setStyle(if (picture != null) NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as android.graphics.Bitmap?).setSummaryText(text.take(140) + more)
                else NotificationCompat.BigTextStyle().bigText(text + more))
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
        const val DASH = "dashboard"
        const val REMINDERS = "reminders"
        const val SUGGESTIONS = "suggestions"
        const val BRIEF = "brief"
        const val RECAP = "recap"
        const val ATTENTION = "attention"
        const val NUDGES = "nudges"
        const val TRAVEL = "travel"
        const val TRAVEL_ID = 4980
        const val NUDGE_BASE = 4600
        const val EVENT_BASE = 5600
        const val SPEAKER_ID = 4950
        const val CHECKIN_ID = 4960
        const val QUICKQ_ID = 4961
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
