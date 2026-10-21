package app.echomind.capture

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.RemoteInput
import app.echomind.App
import app.echomind.ai.Prompts
import app.echomind.brain.Brain
import app.echomind.brain.TextNorm
import app.echomind.data.Gap
import app.echomind.data.Utterance
import app.echomind.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The one long-running service. While it lives, EchoMind keeps working with
 * the screen off.
 *
 *  - **Listening**: foreground type `microphone` only (no time limit). It can
 *    only *gain* the microphone type from something the user did — opening the
 *    app, a notification button, the tile, the widget — so pausing never stops
 *    the service, it only releases the recorder, and resuming needs no tap.
 *    Transcription and AI run inside it while it listens.
 *  - **Catching up**: after listening stops, type `mediaProcessing` until the
 *    queue is done (Android allows ~6 h a day; [onTimeout] stops cleanly).
 *
 * Timers that must fire with the screen off (end of a pause, end of quiet
 * hours) use exact alarms delivered to [ResumeReceiver], because coroutine
 * delays stop while the CPU sleeps.
 */
class CaptureService : Service() {
    private val g get() = App.graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var micGuard: MicGuard
    private var recording = false
    /** The service currently holds the microphone foreground type. */
    private var micGranted = false
    private var brainJob: Job? = null
    private var tickJob: Job? = null
    private var watchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val gapPrefs by lazy { getSharedPreferences("gaps", MODE_PRIVATE) }

    private val callObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            scope.launch { delay(70_000); g.brain.importCalls() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        g.notifier.ensureChannels()
        micGuard = MicGuard(this) { scope.launch { delay(75_000); g.brain.importCalls() } }
        contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, callObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_RESTART
        // Every start must reach startForeground quickly. Only actions that come
        // from the user may ask for the microphone type.
        val wantsMic = action in setOf(ACTION_START, ACTION_RESUME, ACTION_PAUSE, ACTION_NOTE) || (action == ACTION_RESTART && g.settings.value.captureWanted)
        if (!enterForeground(wantMic = wantsMic || micGranted)) {
            if (action == ACTION_RESTART) g.notifier.resumePrompt("Android stopped EchoMind in the background.")
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_START, ACTION_RESUME -> scope.launch {
                g.notifier.clearResumePrompt()
                cancelResumeAlarm()
                g.settings.update { it.copy(captureWanted = true, pausedUntil = 0) }
                startRecording()
            }
            ACTION_RESTART -> scope.launch {
                val s = g.settings.awaitLoaded()
                if (s.captureWanted && micGranted && !s.isPaused()) startRecording() else idleOrStop()
            }
            ACTION_PAUSE -> {
                val minutes = intent?.getLongExtra(EXTRA_MINUTES, 30) ?: 30
                scope.launch { pause(minutes) }
            }
            ACTION_NOTE -> {
                val text = intent?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(EXTRA_NOTE)?.toString() }
                if (!text.isNullOrBlank()) scope.launch { addTypedNote(text) }
                refreshNotification()
            }
            ACTION_PROCESS -> { ensureBrain(); g.brain.processNow() }
            ACTION_STOP -> scope.launch {
                g.settings.update { it.copy(captureWanted = false) }
                cancelResumeAlarm()
                stopRecording("stopped")
                // The last window is closed by the recorder thread; make sure it is queued
                // before deciding there is nothing left to do.
                g.capture.flushWindows()
                g.brain.poke()
                idleOrStop()
            }
        }
        return START_STICKY
    }

    /** Microphone type while it may listen; media processing only, for catching up. */
    private fun enterForeground(wantMic: Boolean): Boolean {
        val types = if (wantMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        return try {
            startForeground(Notifier.CAPTURE_ID, g.notifier.capture(status()), types)
            micGranted = wantMic
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground refused: ${e.message}")
            if (!wantMic) return false
            // Restarted in the background: we may still catch up, but listening needs a tap.
            runCatching {
                startForeground(Notifier.CAPTURE_ID, g.notifier.capture(status()), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                micGranted = false
                g.notifier.resumePrompt("Listening needs a tap after a restart.")
            }.isSuccess
        }
    }

    private suspend fun startRecording() {
        val s = g.settings.value
        ensureBrain()
        if (!micGranted) {
            g.notifier.resumePrompt("Listening needs a tap to start again.")
            publish(CaptureController.Mode.OFF)
            return
        }
        if (quietNow()) {
            publish(CaptureController.Mode.QUIET)
            scheduleResumeAlarm(quietEnd())
            return
        }
        if (!Recorder.isRunning()) {
            val ok = Recorder.start(this, g.capture.windowsDir, s.windowMinutes * 60_000L, s.inputDeviceId, boost = s.boostQuietSpeech)
            if (!ok) {
                g.notifier.attention("Microphone unavailable", "EchoMind couldn't open the microphone. Another app may be using it, or the permission was removed.", "settings")
                publish(CaptureController.Mode.OFF)
                return
            }
        }
        recording = true
        micGuard.start()
        closeGap()
        publish(CaptureController.Mode.LISTENING)
    }

    private suspend fun stopRecording(reason: String?) {
        micGuard.stop()
        if (Recorder.isRunning()) Recorder.stop()
        g.capture.flushWindows()
        if (recording && reason != null) openGap(reason)
        recording = false
    }

    /** Not listening: keep the service while work remains or a resume is scheduled, else go. */
    private suspend fun idleOrStop() {
        val s = g.settings.value
        if (s.captureWanted) {
            publish(if (s.isPaused()) CaptureController.Mode.PAUSED else if (quietNow()) CaptureController.Mode.QUIET else CaptureController.Mode.OFF)
            ensureBrain()
            return
        }
        if (g.brain.hasWork()) {
            if (micGranted) enterForeground(wantMic = false)
            publish(CaptureController.Mode.PROCESSING)
            ensureBrain()
        } else {
            stopSelf()
        }
    }

    private suspend fun pause(minutes: Long) {
        val until = if (minutes <= 0) Long.MAX_VALUE else System.currentTimeMillis() + minutes * 60_000L
        g.settings.update { it.copy(pausedUntil = until) }
        stopRecording("paused")
        publish(CaptureController.Mode.PAUSED)
        ensureBrain()
        if (until != Long.MAX_VALUE) scheduleResumeAlarm(until) else cancelResumeAlarm()
        refreshNotification()
    }

    /** Called by [ResumeReceiver] when a pause or quiet hours end. */
    fun resumeFromAlarm() {
        scope.launch {
            val s = g.settings.value
            if (s.pausedUntil in 1..System.currentTimeMillis()) g.settings.update { it.copy(pausedUntil = 0) }
            if (g.settings.value.captureWanted && !g.settings.value.isPaused()) startRecording()
            refreshNotification()
        }
    }

    private fun resumeIntent() = PendingIntent.getBroadcast(this, 77, Intent(this, ResumeReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun scheduleResumeAlarm(at: Long) {
        val am = getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, resumeIntent())
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, resumeIntent())
    }

    private fun cancelResumeAlarm() = getSystemService(AlarmManager::class.java).cancel(resumeIntent())

    // Gaps survive the service being destroyed: the open one is kept in prefs.
    private fun openGap(reason: String) {
        if (gapPrefs.contains("reason")) return
        gapPrefs.edit().putLong("start", System.currentTimeMillis()).putString("reason", reason).apply()
    }

    private suspend fun closeGap() {
        val r = gapPrefs.getString("reason", null) ?: return
        val start = gapPrefs.getLong("start", 0)
        gapPrefs.edit().clear().apply()
        if (System.currentTimeMillis() - start > 60_000) g.db.gaps().insert(Gap(startAt = start, endAt = System.currentTimeMillis(), reason = r))
    }

    private fun minuteNow() = LocalTime.now().let { it.hour * 60 + it.minute }
    private fun quietNow() = g.settings.value.inQuietHours(minuteNow())
    private fun quietEnd(): Long {
        val to = g.settings.value.quietTo
        var t = LocalDate.now().atTime(to / 60, to % 60).atZone(ZoneId.systemDefault())
        if (t.toInstant().toEpochMilli() <= System.currentTimeMillis()) t = t.plusDays(1)
        return t.toInstant().toEpochMilli()
    }

    private suspend fun addTypedNote(text: String) {
        val now = System.currentTimeMillis()
        g.db.utterances().insert(Utterance(startAt = now, endAt = now + 1_000, text = text.trim(), speaker = "me", source = "typed", engine = "typed", searchText = TextNorm.searchable(text)))
        g.brain.poke()
    }

    /** The brain loop runs as long as this service lives; it waits if a background worker holds it. */
    private fun ensureBrain() {
        if (brainJob?.isActive != true) brainJob = scope.launch { g.brain.run(wait = true) { true } }
        if (watchJob?.isActive != true) watchJob = scope.launch { watch() }
        if (tickJob?.isActive != true) tickJob = scope.launch { tick() }
        g.brain.poke()
    }

    /** Minute ticker: heartbeat, quiet hours, recorder health, and exit when everything is done. */
    private suspend fun tick() {
        while (scope.isActive) {
            val s = g.settings.value
            if (recording) g.capture.heartbeat()
            when {
                recording && quietNow() -> { stopRecording("quiet"); publish(CaptureController.Mode.QUIET); scheduleResumeAlarm(quietEnd()) }
                // The recorder thread ended on its own (audio server died, device removed).
                recording && !Recorder.isRunning() -> { recording = false; startRecording() }
                !recording && micGranted && s.captureWanted && !s.isPaused() && !quietNow() -> startRecording()
            }
            if (!recording && !s.captureWanted && !g.brain.hasWork() && g.brain.state.value.phase == Brain.Phase.IDLE) {
                stopSelf(); return
            }
            refreshNotification()
            delay(60_000)
        }
    }

    /** Notification and wake lock follow state changes, at most every few seconds. */
    private suspend fun watch() {
        combine(g.brain.state, micGuard.blocker, g.transcriber.status, g.db.audio().pendingCount()) { b, blocker, _, _ -> b to blocker }
            .sample(4_000)
            .collect { (b, blocker) ->
                if (recording) {
                    publish(when (blocker) {
                        MicGuard.Blocker.CALL -> CaptureController.Mode.BLOCKED_CALL
                        MicGuard.Blocker.OTHER_APP -> CaptureController.Mode.BLOCKED_APP
                        else -> CaptureController.Mode.LISTENING
                    })
                    when (blocker) {
                        MicGuard.Blocker.CALL -> openGap("call")
                        MicGuard.Blocker.OTHER_APP -> openGap("other_app")
                        else -> closeGap()
                    }
                }
                // While recording, the audio system keeps the CPU awake; otherwise hold it only while working.
                holdWakeLock(b.phase != Brain.Phase.IDLE && b.phase != Brain.Phase.WAITING && !recording)
                refreshNotification()
            }
    }

    private fun publish(mode: CaptureController.Mode) {
        g.capture.publish(CaptureController.State(mode, true, g.settings.value.pausedUntil, Recorder.state.value.device, Recorder.state.value.windowStartedAt))
    }

    private fun status(): Notifier.Status {
        val mode = g.capture.state.value.mode
        val t = g.transcriber.status.value
        val b = g.brain.state.value
        val working = when (b.phase) {
            Brain.Phase.TRANSCRIBING -> if (t.active) "Transcribing ${if (t.source == "call") "a call" else "speech"} (${t.piece}/${t.pieces})" else "Transcribing"
            Brain.Phase.THINKING -> "Making notes: ${b.detail}"
            Brain.Phase.JOURNAL -> b.detail
            Brain.Phase.WAITING -> b.waitingReason ?: "Waiting"
            else -> null
        }
        val pace = g.governor.state.value.takeIf { !it.allowed }?.reason
        val s = g.settings.value
        return when (mode) {
            CaptureController.Mode.LISTENING -> Notifier.Status("Listening", listOfNotNull(Recorder.state.value.device, working ?: pace).joinToString(" · "), recording = true, paused = false)
            CaptureController.Mode.BLOCKED_CALL -> Notifier.Status("On a call — paused", "The call will be added from your call recording", recording = true, paused = false)
            CaptureController.Mode.BLOCKED_APP -> Notifier.Status("Another app is using the mic", "Listening resumes when it's done", recording = true, paused = false)
            CaptureController.Mode.PAUSED -> Notifier.Status(
                if (s.pausedUntil == Long.MAX_VALUE) "Paused" else "Paused until ${Prompts.time(s.pausedUntil)}",
                working ?: "Not listening", recording = false, paused = true,
            )
            CaptureController.Mode.QUIET -> Notifier.Status("Quiet hours", working ?: "Listening resumes at ${s.quietTo / 60}:${"%02d".format(s.quietTo % 60)}", recording = false, paused = false)
            CaptureController.Mode.OFF -> Notifier.Status("Not listening", working ?: "Tap to open EchoMind", recording = false, paused = false)
            else -> Notifier.Status("Catching up", working ?: pace ?: "Working through recordings", recording = false, paused = false)
        }
    }

    private fun refreshNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(Notifier.CAPTURE_ID, g.notifier.capture(status())) }
    }

    private fun holdWakeLock(on: Boolean) {
        if (on && wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EchoMind:work").apply {
                setReferenceCounted(false); acquire(30 * 60_000L)
            }
        } else if (!on) {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    /** Android 15: the daily media-processing allowance is used up (never applies while listening). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground time limit reached for type $fgsType")
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE == 0) stopSelf()
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        micGuard.stop()
        if (Recorder.isRunning()) Recorder.stop()
        if (recording) openGap("stopped")
        contentResolver.unregisterContentObserver(callObserver)
        wakeLock?.let { if (it.isHeld) it.release() }
        g.capture.publish(CaptureController.State())
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EchoService"
        const val ACTION_START = "app.echomind.START"
        const val ACTION_RESTART = "app.echomind.RESTART"
        const val ACTION_STOP = "app.echomind.STOP"
        const val ACTION_PAUSE = "app.echomind.PAUSE"
        const val ACTION_RESUME = "app.echomind.RESUME"
        const val ACTION_NOTE = "app.echomind.NOTE"
        const val ACTION_PROCESS = "app.echomind.PROCESS"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_NOTE = "note"

        @Volatile var running = false
            private set
        @Volatile internal var instance: CaptureService? = null
    }
}

/** Ends a pause or quiet hours inside the running service (no new foreground start needed). */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = CaptureService.instance
        if (svc != null) svc.resumeFromAlarm()
        else if (App.graph.settings.value.captureWanted) App.graph.notifier.resumePrompt("Your pause is over.")
    }
}
