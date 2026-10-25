package app.echomind.telemetry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.echomind.App
import app.echomind.core.Feature
import app.echomind.core.Providers

/** Sends anonymous usage every 6 hours (when online). */
class UsageWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = App.graph
        val s = g.settings.awaitLoaded()
        g.usage.flush {
            if (applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) g.usage.once("setup_permissions")
            val ask = s.provider(Feature.ASK)
            // The provider id and whether it is on-device: never keys or server addresses.
            mapOf("model_used" to mapOf("provider" to if (Providers.isLocal(ask)) "on_device" else ask.take(24)))
        }
        return Result.success()
    }
}
