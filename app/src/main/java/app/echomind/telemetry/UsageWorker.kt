package app.echomind.telemetry

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.echomind.App
import app.echomind.core.Feature
import app.echomind.core.Providers
import kotlinx.coroutines.flow.first
import java.io.File

/** Sends anonymous usage every 6 hours (when online). */
class UsageWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = app.echomind.telemetry.Usage.worker("usage") { work() }

    private suspend fun work(): Result {
        val g = App.graph
        g.settings.awaitLoaded()
        g.usage.flush {
            if (applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) g.usage.once("setup_permissions")
            UsageDaily.collect(applicationContext)
        }
        return Result.success()
    }
}

/** The once-a-day snapshot: settings, permissions, storage and data sizes as buckets, widgets in use. */
object UsageDaily {
    fun permissions(ctx: Context): Map<String, Boolean> {
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val power = ctx.getSystemService(android.os.PowerManager::class.java)
        val alarms = ctx.getSystemService(android.app.AlarmManager::class.java)
        return linkedMapOf(
            "mic" to has(Manifest.permission.RECORD_AUDIO),
            "notifications" to has(Manifest.permission.POST_NOTIFICATIONS),
            "calendar" to has(Manifest.permission.READ_CALENDAR),
            "location" to (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)),
            "bg_location" to has(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            "audio_files" to has(Manifest.permission.READ_MEDIA_AUDIO),
            "battery_unrestricted" to (power?.isIgnoringBatteryOptimizations(ctx.packageName) == true),
            "exact_alarms" to (alarms?.canScheduleExactAlarms() != false),
        )
    }

    private fun dirBytes(f: File?): Long = f?.takeIf { it.exists() }?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    private fun tableCount(table: String): Long = runCatching {
        App.graph.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }.getOrDefault(-1L)

    private fun bucket(n: Long) = if (n < 0) "unknown" else UsageMath.countBucket(n)

    suspend fun collect(ctx: Context): Map<String, Map<String, Any?>> {
        val g = App.graph
        val s = g.settings.value
        val out = LinkedHashMap<String, Map<String, Any?>>()
        val ask = s.provider(Feature.ASK)
        // The provider id and whether it is on-device: never keys, server addresses or model names.
        out["model_used"] = mapOf("provider" to if (Providers.isLocal(ask)) "on_device" else UsageMath.provider(ask))
        out += UsageMath.settingsSnapshot(s)
        val perms = permissions(ctx)
        out["permissions"] = perms
        g.usage.checkPermissions(perms)
        runCatching {
            val db = ctx.getDatabasePath("echomind.db")
            val dbBytes = listOf(db, File(db.path + "-wal")).sumOf { if (it.exists()) it.length() else 0L }
            val audio = dirBytes(g.capture.windowsDir)
            val kept = dirBytes(g.recordings.dir)
            val models = g.models.usedBytes()
            out["storage"] = mapOf(
                "db" to UsageMath.sizeBucket(dbBytes), "audio_queue" to UsageMath.sizeBucket(audio), "recordings" to UsageMath.sizeBucket(kept),
                "models" to UsageMath.sizeBucket(models), "total" to UsageMath.sizeBucket(dbBytes + audio + kept + models),
                "free" to UsageMath.sizeBucket(ctx.filesDir.usableSpace),
            )
        }
        out["data_size"] = mapOf(
            "conversations" to bucket(tableCount("conversations")), "lines" to bucket(tableCount("utterances")), "notes" to bucket(tableCount("notes")),
            "tasks" to bucket(tableCount("tasks")), "people" to bucket(tableCount("entities WHERE kind = 'person'")), "facts" to bucket(tableCount("facts")),
            "events" to bucket(tableCount("events")), "chats" to bucket(tableCount("chat_threads")),
        )
        runCatching {
            val pending = g.db.audio().pendingCount().first()
            out["pipeline_state"] = mapOf(
                "listening_wanted" to s.captureWanted, "travel" to s.travel, "voice_enrolled" to g.voice.enrolled(),
                "audio_waiting" to UsageMath.countBucket(pending), "convs_waiting" to UsageMath.countBucket(g.db.conversations().readyCount().first()),
                "audio_failed" to bucket(tableCount("audio WHERE state = 'failed'")), "convs_failed" to bucket(tableCount("conversations WHERE state = 'failed'")),
                "places" to s.places.size,
            )
        }
        runCatching {
            val wm = AppWidgetManager.getInstance(ctx)
            fun n(cls: Class<*>) = wm.getAppWidgetIds(ComponentName(ctx, cls)).size
            out["widgets"] = mapOf(
                "status" to n(app.echomind.surfaces.ListenWidgetReceiver::class.java), "mic" to n(app.echomind.surfaces.MicWidgetReceiver::class.java),
                "tasks" to n(app.echomind.surfaces.EchoWidgetReceiver::class.java), "agenda" to n(app.echomind.surfaces.AgendaWidgetReceiver::class.java),
                "notes" to n(app.echomind.surfaces.NotesWidgetReceiver::class.java), "today" to n(app.echomind.surfaces.DayWidgetReceiver::class.java),
                "ask" to n(app.echomind.surfaces.AskWidgetReceiver::class.java), "quick" to n(app.echomind.surfaces.QuickWidgetReceiver::class.java),
            )
        }
        return out
    }
}
