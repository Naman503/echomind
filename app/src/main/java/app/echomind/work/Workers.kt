package app.echomind.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.echomind.App
import app.echomind.ai.Prompts
import app.echomind.capture.CaptureService
import app.echomind.util.Js
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate

/**
 * Every 15 minutes: notices when listening stopped although the user wants it
 * (offers "tap to resume" — the silent-failure guard), picks up new call
 * recordings, and makes sure waiting work gets done even with no service.
 */
private var lastTravelCheck = 0L

class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = app.echomind.telemetry.Usage.worker("watchdog") { work() }

    private suspend fun work(): Result {
        val g = App.graph
        val s = g.settings.awaitLoaded()
        if (s.captureWanted && !s.isPaused() && !CaptureService.running) {
            val beat = g.capture.lastHeartbeat()
            if (beat == 0L || System.currentTimeMillis() - beat > 5 * 60_000L) g.notifier.resumePrompt("Listening stopped in the background.")
        }
        // Travel: planned trips and, about every 30 minutes, whether you're away.
        if (System.currentTimeMillis() - lastTravelCheck >= 25 * 60_000L) { lastTravelCheck = System.currentTimeMillis(); runCatching { g.travel.check() } }
        runCatching { g.places.maybeRecord() }
        if (g.settings.value.travel) { g.travel.showOngoing(); if (!g.brain.hasWork()) return Result.success() }
        runCatching { g.calls.scan() }
        runCatching { g.nudges.check() }
        runCatching { g.recordings.sweep() }
        runCatching { g.agenda.checkUpcoming() }
        runCatching { g.sounds.identifyPending() }
        runCatching { g.checkIns.quick() }
        // At night the review should start promptly, not whenever Android gets round to it; a job
        // already running is kept, never replaced.
        val night = java.time.LocalTime.now().let { it.hour * 60 + it.minute }.let { it >= g.settings.value.nightReviewAt || it < 7 * 60 }
        if (!CaptureService.running && g.brain.hasWork()) ProcessWorker.enqueue(applicationContext, now = night, replace = false)
        return Result.success()
    }
}

/** Catch-up processing when no service is running; Android decides when (charging / idle). */
class ProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = app.echomind.telemetry.Usage.worker("process") { work() }

    private suspend fun work(): Result {
        if (CaptureService.running) return Result.success()
        // As a foreground job (a quiet notification) it may run past ten minutes and isn't
        // killed as an idle background app, so a night review isn't cut off mid-answer.
        val foreground = runCatching { setForeground(info()) }.isSuccess
        App.graph.brain.drain(System.currentTimeMillis() + if (foreground) 3 * 3_600_000L else 6 * 60_000L)
        return Result.success()
    }

    private fun info(): androidx.work.ForegroundInfo {
        val n = androidx.core.app.NotificationCompat.Builder(applicationContext, app.echomind.notify.Notifier.CAPTURE)
            .setSmallIcon(app.echomind.R.drawable.ic_stat_idle)
            .setContentTitle("Working through your day")
            .setContentText("Reading conversations and writing the review — paused when the phone is hot")
            .setOngoing(true).setSilent(true)
            .build()
        return androidx.work.ForegroundInfo(4970, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        /**
         * [now]: you asked for it (Review now), so it runs straight away as an expedited job;
         * otherwise Android picks a good moment (Android 15 may hold ordinary jobs back for a while).
         */
        fun enqueue(context: Context, now: Boolean = false, replace: Boolean = now) {
            val s = App.graph.settings.value
            val req = if (now) OneTimeWorkRequestBuilder<ProcessWorker>().setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            else OneTimeWorkRequestBuilder<ProcessWorker>().setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(s.onlyWhileCharging).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("process", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
        }
    }

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo = info()

}

/** Morning brief (no AI needed) and evening journal + recap. */
class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = app.echomind.telemetry.Usage.worker("daily") { work() }

    private suspend fun work(): Result {
        val g = App.graph
        val s = g.settings.awaitLoaded()
        val today = LocalDate.now()
        if (s.travel) return Result.success()
        if (inputData.getString("kind") == "morning") {
            if (s.autoPlans) runCatching { g.plans.morning() }
            if (!s.notifyMorning) return Result.success()
            val (_, to) = g.journal.bounds(today)
            val reminders = g.db.tasks().upcomingReminders(System.currentTimeMillis()).filter { (it.remindAt ?: 0) < to }
            val due = g.db.tasks().live().filter { it.status == "open" && it.dueAt != null && it.dueAt < to && it.remindAt == null }
            val yesterday = g.db.days().get(today.minusDays(1).toString())
            val carry = yesterday?.tomorrow?.let { runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()
            val plan = g.plans.get("day")
            val weather = runCatching { g.places.morningWeather() }.getOrNull()
            val lines = listOfNotNull(weather?.let { "Weather · $it" }, plan?.focus?.takeIf { it.isNotBlank() }?.let { "Focus · $it" }) + reminders.map { "${Prompts.time(it.remindAt!!)} · ${it.title}" } +
                due.map { "Due · ${it.title}" } + carry.take(3).map { "From yesterday · $it" } + plan?.missing.orEmpty().take(2).map { "Missing? · ${it.title}" }
            val suggested = g.db.tasks().suggestedCount().first()
            if (lines.isEmpty() && suggested == 0) return Result.success()
            g.notifier.brief(
                if (reminders.isEmpty()) "Good morning" else "Today: ${reminders.size} reminder${if (reminders.size == 1) "" else "s"}",
                lines + if (suggested > 0) listOf("$suggested suggestion${if (suggested == 1) "" else "s"} to review") else emptyList(),
            )
        } else {
            if (g.db.audio().pendingCount().first() == 0 && g.db.conversations().readyCount().first() == 0) {
                if (g.journal.nightly(today)) g.brain.recap(today)
            } else if (!CaptureService.running) {
                ProcessWorker.enqueue(applicationContext)
            }
            g.tasks.expireOld()
        }
        return Result.success()
    }
}
