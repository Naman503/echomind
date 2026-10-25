package app.echomind.capture

import android.content.Context
import android.content.Intent
import android.util.Log
import app.echomind.brain.Brain
import app.echomind.core.SettingsStore
import app.echomind.data.AudioItem
import app.echomind.data.Db
import app.echomind.data.Gap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * App-side handle on listening: what the UI calls, and what turns recorder
 * windows into queued audio. The microphone itself lives in [CaptureService].
 */
class CaptureController(
    private val context: Context,
    private val db: Db,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val brain: () -> Brain,
    private val sounds: () -> app.echomind.brain.Sounds,
) {
    val windowsDir = File(context.filesDir, "audio").apply { mkdirs() }

    enum class Mode { OFF, LISTENING, PAUSED, QUIET, BLOCKED_CALL, BLOCKED_APP, PROCESSING }

    data class State(val mode: Mode = Mode.OFF, val serviceRunning: Boolean = false, val pausedUntil: Long = 0, val device: String = "", val since: Long = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)

    internal fun publish(s: State) { _state.value = s }

    private val accepting = java.util.Collections.synchronizedList(ArrayList<kotlinx.coroutines.Job>())

    init {
        Recorder.onWindow = { w ->
            val job = scope.launch(Dispatchers.IO) { accept(w) }
            accepting.add(job)
            job.invokeOnCompletion { accepting.remove(job) }
        }
    }

    /** Waits until every closed window is safely queued (used right after stopping the recorder). */
    suspend fun flushWindows() {
        val jobs = synchronized(accepting) { accepting.toList() }
        jobs.forEach { it.join() }
    }

    /** A closed window: dropped if nobody spoke, else queued for transcription. */
    private suspend fun accept(w: Recorder.Window) {
        val forgetFrom = prefs.getLong("forgetFrom", 0)
        if (w.startedAt < forgetFrom && w.endedAt <= forgetFrom + 5_000) { w.file.delete(); return }
        if (w.voicedMs < MIN_VOICED_MS) {
            app.echomind.App.graph.usage.count("capture_dropped", mapOf("reason" to "silence"))
            // No talk — but maybe music, or surroundings for the activity timeline, before it's deleted unheard.
            if (sounds().wanted()) runCatching {
                sounds().analyse(w.file, AudioItem(path = w.file.absolutePath, startedAt = w.startedAt, endedAt = w.endedAt, durationMs = w.durationMs, voicedMs = w.voicedMs, source = "mic"), dropped = true)
            }
            w.file.delete()
            return
        }
        if (db.audio().hasPath(w.file.absolutePath)) return
        db.audio().insert(AudioItem(path = w.file.absolutePath, startedAt = w.startedAt, endedAt = w.endedAt, durationMs = w.durationMs, voicedMs = w.voicedMs, source = "mic"))
        // Anonymous usage: a count only, never the audio or its words.
        app.echomind.App.graph.usage.count("captures")
        app.echomind.App.graph.usage.count("capture_added", mapOf("type" to "audio", "source" to "mic", "len" to app.echomind.telemetry.UsageMath.durationBucket(w.durationMs)))
        app.echomind.App.graph.usage.once("first_capture")
        brain().poke()
    }

    /** Windows left on disk by a process death become queued audio again. */
    suspend fun recoverOrphans() {
        val known = db.audio().livePaths().toSet()
        val current = if (Recorder.isRunning()) Recorder.state.value.windowStartedAt else -1
        windowsDir.listFiles { f -> f.name.startsWith("win_") && f.name.endsWith(".wav") }?.forEach { f ->
            if (f.absolutePath in known) return@forEach
            val started = f.name.removePrefix("win_").removeSuffix(".wav").toLongOrNull() ?: return@forEach
            if (started == current) return@forEach
            if (db.audio().hasPath(f.absolutePath)) return@forEach // kept for playback, or failed and waiting for a retry
            runCatching { Wav.repair(f) }
            val ms = Wav.durationMs(f)
            if (ms < 1_000) { f.delete(); return@forEach }
            Log.i("EchoCapture", "recovered ${f.name} (${ms / 1000}s)")
            db.audio().insert(AudioItem(path = f.absolutePath, startedAt = started, endedAt = started + ms, durationMs = ms, voicedMs = ms, source = "mic"))
        }
        // Working WAVs of finished recordings (left by a crash between transcription and compression).
        for (a in db.audio().doneBefore(System.currentTimeMillis() - 3_600_000L)) File(a.path).takeIf { it.exists() }?.delete()
        // A gap for time the app was not alive although the user wanted it listening.
        val beat = prefs.getLong("heartbeat", 0)
        val t = java.time.LocalTime.now()
        val excused = settings.value.isPaused() || settings.value.inQuietHours(t.hour * 60 + t.minute)
        if (settings.value.captureWanted && !excused && beat > 0 && System.currentTimeMillis() - beat > 3 * 60_000L && !Recorder.isRunning()) {
            db.gaps().insert(Gap(startAt = beat, endAt = System.currentTimeMillis(), reason = "stopped"))
            prefs.edit().putLong("heartbeat", 0).apply()
        }
    }

    fun heartbeat() = prefs.edit().putLong("heartbeat", System.currentTimeMillis()).apply()
    fun lastHeartbeat() = prefs.getLong("heartbeat", 0)

    private fun send(action: String, extra: Intent.() -> Unit = {}): Boolean = try {
        context.startForegroundService(Intent(context, CaptureService::class.java).setAction(action).apply(extra))
        true
    } catch (e: Exception) {
        Log.w("EchoCapture", "service refused: ${e.message}")
        false
    }

    /** Must be called from something the user just did (Android's rule for the microphone). */
    fun start(): Boolean {
        scope.launch { settings.update { it.copy(captureWanted = true, pausedUntil = 0) } }
        return send(CaptureService.ACTION_START)
    }

    fun stop() {
        scope.launch { settings.update { it.copy(captureWanted = false) } }
        send(CaptureService.ACTION_STOP)
    }

    fun pause(minutes: Long) = send(CaptureService.ACTION_PAUSE) { putExtra(CaptureService.EXTRA_MINUTES, minutes) }
    fun resume() = send(CaptureService.ACTION_RESUME)
    fun processNow() = send(CaptureService.ACTION_PROCESS)

    /**
     * "Forget the last N minutes": the current window is cut and dropped, queued
     * audio and transcript lines from that time are deleted, and the gap is
     * recorded so the timeline is honest about it.
     */
    suspend fun forget(minutes: Int) {
        val from = System.currentTimeMillis() - minutes * 60_000L
        app.echomind.App.graph.usage.event("forget_used", mapOf("minutes" to minutes))
        prefs.edit().putLong("forgetFrom", System.currentTimeMillis()).apply()
        if (Recorder.isRunning()) Recorder.rotate("forget")
        for (a in db.audio().overlapping(from, Long.MAX_VALUE)) {
            File(a.path).delete()
            a.keptPath?.let { File(it).delete() }
            db.audio().delete(a)
        }
        db.utterances().deleteSince(from)
        db.gaps().insert(Gap(startAt = from, endAt = System.currentTimeMillis(), reason = "forgotten"))
    }

    companion object {
        /** Windows with less voiced time than this are silence, a cough, a door — deleted unheard. */
        const val MIN_VOICED_MS = 1_200L
    }
}
