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
import kotlinx.coroutines.flow.first
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
        val wantsMic = action in setOf(ACTION_START, ACTION_RESUME, ACTION_PAUSE, ACTION_NOTE) || (action == ACTION_REMIC && Recorder.isRunning()) || (action == ACTION_RESTART && g.settings.value.captureWanted)
        // Anonymous usage: which listening control was used (notification, widget, tile or app alike).
        val actionName = when (action) { ACTION_START -> "start"; ACTION_RESTART -> "restart"; ACTION_STOP -> "stop"; ACTION_PAUSE -> "pause"; ACTION_RESUME -> "resume"; ACTION_NOTE -> "note"; ACTION_PROCESS -> "process_now"; else -> "other" }
        if (!enterForeground(wantMic = wantsMic || micGranted)) {
            app.echomind.telemetry.Usage.count("listening_action", mapOf("action" to actionName, "ok" to false))
            if (action == ACTION_RESTART) g.notifier.resumePrompt("Android stopped EchoMind in the background.")
            stopSelf()
            return START_NOT_STICKY
        }
        app.echomind.telemetry.Usage.count("listening_action", mapOf("action" to actionName, "ok" to true, "mic" to micGranted))
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
            // The dashboard was swiped away while EchoMind works: put it back.
            ACTION_REPOST -> refreshNotification()
            // Microphone settings changed: reopen it with the new mode, device and gain.
            ACTION_REMIC -> scope.launch { kotlinx.coroutines.delay(400); if (Recorder.isRunning()) { Recorder.stop(); startRecording() } }
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
            val ok = Recorder.start(this, g.capture.windowsDir, s.windowMinutes * 60_000L, s.inputDeviceId,
                source = MicTest.sourceFor(s.micMode), boost = s.boostQuietSpeech, gainMaxDb = RoomGain.maxFor(s.roomGain))
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
        app.echomind.telemetry.Usage.count("capture_added", mapOf("type" to "text", "source" to "notification_note"))
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
            refreshInfo()
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

    /** Numbers for the notification's expanded view, refreshed every minute (cheap queries only). */
    private data class Info(val pending: Int = 0, val pendingMin: Int = 0, val toRead: Int = 0, val talkMin: Int = 0, val convs: Int = 0, val tasksDone: Int = 0,
                            val nextReminder: String? = null, val listenedMin: Int = 0, val hours: List<Float> = emptyList())
    @Volatile private var info = Info()

    private suspend fun refreshInfo() = runCatching {
        val now = System.currentTimeMillis()
        val dayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val lines = g.db.utterances().range(dayStart, now)
        info = Info(
            pending = g.db.audio().pendingCount().first(), pendingMin = (g.db.audio().pendingMs().first() / 60_000).toInt(),
            toRead = g.db.conversations().readyCount().first(),
            talkMin = (lines.filter { it.speaker != "Song" }.sumOf { (it.endAt - it.startAt).coerceIn(300, 60_000) } / 60_000).toInt(),
            convs = g.db.conversations().inRangeOnce(dayStart, now).count { it.state == "done" },
            tasksDone = g.db.tasks().completedIn(dayStart, now).size,
            nextReminder = g.db.tasks().upcomingReminders(now).firstOrNull()?.let { "${Prompts.time(it.remindAt!!)} · ${it.title.take(40)}" },
            hours = runCatching {
                val today = java.time.LocalDate.now()
                app.echomind.brain.Analytics.heatmap(app.echomind.brain.Analytics.spans(g.db, today, today))[today.dayOfWeek.value - 1].let { h -> (6..23).map { h[it].toFloat() } }
            }.getOrDefault(emptyList()),
            listenedMin = (g.db.audio().overlapping(dayStart, now).filter { it.source == "mic" }.sumOf { minOf(it.endedAt, now) - maxOf(it.startedAt, dayStart) } / 60_000).toInt(),
        )
    }

    private fun status(): Notifier.Status = withDetails(baseStatus())

    /** Adds what's running, the queue, phone health and today's numbers (Settings → Notifications decides how much). */
    private fun withDetails(st: Notifier.Status): Notifier.Status {
        val s = g.settings.value
        if (s.statusDetail == "minimal") return st
        val p = g.governor.read()
        val thermal = when (p.thermal) { 0 -> "cool"; 1 -> "warm"; 2 -> "hot — slowing down"; 3, 4 -> "very hot — paused"; else -> "overheating" }
        val sub = listOfNotNull(if (p.tempC > 0) "${"%.0f".format(p.tempC)}°C" else null, if (p.percent >= 0) "${p.percent}%${if (p.charging) "⚡" else ""}" else null).joinToString(" · ")
        val b = g.brain.state.value
        val t = g.transcriber.status.value
        val i = info
        val running = when (b.phase) {
            Brain.Phase.TRANSCRIBING -> "Now: transcribing ${if (t.source == "call") "a call" else "speech"}${if (t.pieces > 0) " · part ${t.piece} of ${t.pieces}" else ""} (${t.engine})"
            Brain.Phase.THINKING -> "Now: AI ${g.ai.running.value?.purpose ?: b.detail} (${g.ai.running.value?.provider ?: s.provider(app.echomind.core.Feature.NOTES)}, ${s.aiQuality})"
            Brain.Phase.JOURNAL, Brain.Phase.REVIEW -> "Now: ${b.detail}"
            Brain.Phase.WAITING -> "Waiting: ${b.waitingReason ?: "for a good moment"}"
            else -> "Now: idle"
        }
        val details = buildList {
            if (st.recording) add("● Recording · ${Recorder.state.value.device} · ${i.listenedMin / 60}h ${i.listenedMin % 60}m today")
            add(running)
            add(if (i.pending == 0 && i.toRead == 0) "Queue: all caught up" else "Queue: ${i.pending} recording${if (i.pending == 1) "" else "s"} (${i.pendingMin} min) · ${i.toRead} conversation${if (i.toRead == 1) "" else "s"} to read")
            add("Phone: battery ${if (p.tempC > 0) "${"%.1f".format(p.tempC)}°C" else "?"} · $thermal · ${p.percent}%${if (p.charging) " charging" else ""}${p.headroom?.let { " · heat ${(it * 100).toInt()}%" } ?: ""}")
            g.governor.state.value.takeIf { !it.allowed }?.let { add("Paced: ${it.reason}") }
            if (s.statusDetail == "full") {
                add("Today: ${i.talkMin} min talk · ${i.convs} conversations · ${i.tasksDone} tasks done")
                i.nextReminder?.let { add("Next: $it") }
            }
        }
        if (s.statusDetail != "full") return st.copy(sub = sub, details = details)
        // The dashboard: four numbers, talk by hour, and the travel state for its buttons.
        val tiles = listOf(
            (if (i.talkMin < 60) "${i.talkMin}m" else "${i.talkMin / 60}h ${i.talkMin % 60}m") to "talk today",
            "${i.convs}" to "conversations",
            (if (i.pending == 0) "✓" else if (i.pendingMin < 60) "${i.pendingMin}m" else "${i.pendingMin / 60}h ${i.pendingMin % 60}m") to (if (i.pending == 0) "caught up" else "to transcribe"),
            (if (p.tempC > 0) "${"%.0f".format(p.tempC)}°" else "–") to (if (p.percent >= 0) "phone · ${p.percent}%" else "phone"),
        )
        val text = listOfNotNull(st.text.takeIf { it.isNotBlank() }, i.nextReminder?.let { "Next: $it" }).joinToString(" · ")
        return st.copy(sub = sub, details = details, tiles = tiles, hours = i.hours, travel = s.travel, text = text)
    }

    private fun baseStatus(): Notifier.Status {
        val mode = g.capture.state.value.mode
        val t = g.transcriber.status.value
        val b = g.brain.state.value
        val working = when (b.phase) {
            Brain.Phase.TRANSCRIBING -> if (t.active) "Transcribing ${if (t.source == "call") "a call" else "speech"} (${t.piece}/${t.pieces})" else "Transcribing"
            Brain.Phase.THINKING -> "Making notes: ${b.detail}"
            Brain.Phase.JOURNAL, Brain.Phase.REVIEW -> b.detail
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
        const val ACTION_REMIC = "app.echomind.REMIC"
        const val ACTION_REPOST = "app.echomind.REPOST"
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
