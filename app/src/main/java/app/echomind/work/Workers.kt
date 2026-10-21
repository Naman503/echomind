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
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = App.graph
        val s = g.settings.awaitLoaded()
        if (s.captureWanted && !s.isPaused() && !CaptureService.running) {
            val beat = g.capture.lastHeartbeat()
            if (beat == 0L || System.currentTimeMillis() - beat > 5 * 60_000L) g.notifier.resumePrompt("Listening stopped in the background.")
        }
        runCatching { g.calls.scan() }
        runCatching { g.nudges.check() }
        runCatching { g.recordings.sweep() }
        runCatching { g.agenda.checkUpcoming() }
        if (!CaptureService.running && g.brain.hasWork()) ProcessWorker.enqueue(applicationContext)
        return Result.success()
    }
}

/** Catch-up processing when no service is running; Android decides when (charging / idle). */
class ProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (CaptureService.running) return Result.success()
        App.graph.brain.drain(System.currentTimeMillis() + 9 * 60_000L)
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val s = App.graph.settings.value
            val c = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(s.onlyWhileCharging).build()
            WorkManager.getInstance(context).enqueueUniqueWork("process", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ProcessWorker>().setConstraints(c).build())
        }
    }
}

/** Morning brief (no AI needed) and evening journal + recap. */
class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = App.graph
        val s = g.settings.awaitLoaded()
        val today = LocalDate.now()
        if (inputData.getString("kind") == "morning") {
            if (!s.notifyMorning) return Result.success()
            val (_, to) = g.journal.bounds(today)
            val reminders = g.db.tasks().upcomingReminders(System.currentTimeMillis()).filter { (it.remindAt ?: 0) < to }
            val due = g.db.tasks().live().filter { it.status == "open" && it.dueAt != null && it.dueAt < to && it.remindAt == null }
            val yesterday = g.db.days().get(today.minusDays(1).toString())
            val carry = yesterday?.tomorrow?.let { runCatching { Js.json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()
            val lines = reminders.map { "${Prompts.time(it.remindAt!!)} · ${it.title}" } + due.map { "Due · ${it.title}" } + carry.take(3).map { "From yesterday · $it" }
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
