package app.echomind.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The all-day recorder. It only records: 16 kHz mono PCM into one WAV per
 * window, rotated on its own audio thread so nothing depends on timers that
 * Android throttles with the screen off.
 *
 *  - **Crash-safe files.** The header is rewritten every second, so if the
 *    process dies the window is still a valid WAV and is transcribed later.
 *  - **Knows when nobody is talking.** A level meter with an adaptive noise
 *    floor counts voiced time, so silent windows are dropped without ever
 *    running a speech model.
 *  - **Knows when it is being silenced.** During a phone call (or when another
 *    app takes the microphone) Android feeds this recorder zeros. [MicGuard]
 *    flips [silenced]; the window is cut there so the gap is recorded as a gap,
 *    and the call itself is picked up later from the dialer's recording.
 */
object Recorder {
    private const val TAG = "EchoRecorder"
    private const val FRAME_SAMPLES = 1_280 // 80 ms

    data class Window(
        val file: File,
        val startedAt: Long,
        val endedAt: Long,
        val voicedMs: Long,
        val reason: String,
        val device: String,
    ) {
        val durationMs get() = Wav.durationMs(file)
    }

    data class State(
        val running: Boolean = false,
        val windowStartedAt: Long = 0,
        val voicedMs: Long = 0,
        val device: String = "Phone microphone",
        val silenced: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Smoothed input level 0..1, for the live meter. Cheap to update; nobody pays if nobody collects. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /**
     * Receives every closed window, on the audio thread; must return quickly.
     * Windows that never reach it (process death) are found on disk by
     * [CaptureController.recoverOrphans].
     */
    @Volatile var onWindow: ((Window) -> Unit)? = null

    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var rotateReason: String? = null
    @Volatile var silenced = false
        private set
    @Volatile var sessionId = 0
        private set

    private val lock = Object()
    private var file: RandomAccessFile? = null
    private var path: File? = null
    private var windowStartedAt = 0L
    private var windowBytes = 0L
    private var voicedFrames = 0L
    private var noiseFloorDb = Float.POSITIVE_INFINITY
    private var device = "Phone microphone"
    private var am: AudioManager? = null
    private var usedCommunicationDevice = false

    fun isRunning() = running

    /**
     * @param deviceId an AudioDeviceInfo id from [AudioInputs.list], or 0 for the phone's own microphone.
     * @return false if the microphone could not be opened.
     */
    @SuppressLint("MissingPermission")
    fun start(context: Context, dir: File, windowMs: Long, deviceId: Int, source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION): Boolean {
        synchronized(lock) {
            if (running) return true
            dir.mkdirs()
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am = audio
            val dev = if (deviceId != 0) AudioInputs.find(audio, deviceId) else null
            val bluetooth = dev != null && AudioInputs.isBluetooth(dev)

            // A Bluetooth headset microphone only exists on the "call" route
            // (SCO / LE audio), which needs the communication source.
            var audioSource = source
            if (bluetooth && Build.VERSION.SDK_INT >= 31) {
                usedCommunicationDevice = audio.setCommunicationDevice(dev!!)
                audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }
            val minBuf = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = try {
                AudioRecord(audioSource, Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, FRAME_SAMPLES * 2 * 16))
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord failed: ${e.message}")
                releaseRoute()
                return false
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                releaseRoute()
                return false
            }
            if (dev != null) record.setPreferredDevice(dev)
            device = dev?.let { AudioInputs.label(it) } ?: "Phone microphone"
            sessionId = record.audioSessionId

            running = true
            rotateReason = null
            silenced = false
            noiseFloorDb = Float.POSITIVE_INFINITY
            openWindow(dir)
            thread = Thread({ loop(record, dir, windowMs) }, "EchoMind-recorder").apply { start() }
            publish()
            return true
        }
    }

    /** Ends the current window now and starts the next one. */
    fun rotate(reason: String = "rotate") {
        rotateReason = reason
    }

    /** Called by [MicGuard] when Android starts or stops feeding us silence. */
    fun setSilenced(value: Boolean) {
        if (silenced == value) return
        silenced = value
        if (running) rotate(if (value) "silenced" else "resumed")
        publish()
    }

    fun stop() {
        val t = thread ?: return
        running = false
        try { t.join(3_000) } catch (_: InterruptedException) {}
        thread = null
        releaseRoute()
        _level.value = 0f
        publish()
    }

    private fun releaseRoute() {
        if (usedCommunicationDevice && Build.VERSION.SDK_INT >= 31) {
            try { am?.clearCommunicationDevice() } catch (_: Exception) {}
        }
        usedCommunicationDevice = false
    }

    private fun publish() {
        _state.value = State(running, windowStartedAt, voicedFrames * 80, device, silenced)
    }

    private fun loop(record: AudioRecord, dir: File, windowMs: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ShortArray(FRAME_SAMPLES)
        val bytes = ByteArray(FRAME_SAMPLES * 2)
        val windowLimit = windowMs * Wav.BYTES_PER_MS
        var sinceHeader = 0L
        var sincePublish = 0
        var smoothed = 0f
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buffer, 0, FRAME_SAMPLES)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_DEAD_OBJECT || n == AudioRecord.ERROR_INVALID_OPERATION) {
                        // The audio server restarted or the device went away: end cleanly; the service restarts us.
                        Log.w(TAG, "recorder lost ($n)")
                        break
                    }
                    if (n < 0) Thread.sleep(50)
                    continue
                }
                var sum = 0.0
                for (i in 0 until n) {
                    val s = buffer[i].toInt()
                    bytes[i * 2] = (s and 0xff).toByte()
                    bytes[i * 2 + 1] = (s shr 8 and 0xff).toByte()
                    sum += (s * s).toDouble()
                }
                val rms = (sqrt(sum / n) / 32768.0).toFloat()
                if (!silenced) meter(rms)
                synchronized(lock) {
                    file?.write(bytes, 0, n * 2)
                    windowBytes += n * 2
                }
                sinceHeader += n * 2
                if (sinceHeader >= Wav.SAMPLE_RATE * 2) {
                    sinceHeader = 0
                    synchronized(lock) { file?.let { Wav.patchHeader(it, windowBytes) } }
                }
                // Perceptual meter: fast attack, slow release.
                val lvl = ((20f * log10(max(rms, 1e-5f)) + 60f) / 60f).coerceIn(0f, 1f)
                smoothed = if (lvl > smoothed) lvl else smoothed * 0.85f + lvl * 0.15f
                _level.value = smoothed
                if (++sincePublish >= 12) { // ~1 s
                    sincePublish = 0
                    publish()
                }

                val reason = rotateReason
                if (windowBytes >= windowLimit || reason != null) {
                    rotateReason = null
                    closeWindow(reason ?: "full")
                    openWindow(dir)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "recorder loop failed", e)
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            closeWindow("stop")
            running = false
            publish()
        }
    }

    /** Frames 8 dB above an adaptive floor count as voiced. */
    private fun meter(rms: Float) {
        val db = 20f * log10(max(rms, 1e-5f))
        noiseFloorDb = if (db < noiseFloorDb) db else noiseFloorDb + 0.02f
        if (db > noiseFloorDb + 8f && db > -55f) voicedFrames += 1
    }

    private fun openWindow(dir: File) {
        synchronized(lock) {
            windowStartedAt = System.currentTimeMillis()
            val f = File(dir, "win_$windowStartedAt.wav")
            val raf = RandomAccessFile(f, "rw")
            raf.setLength(0)
            raf.write(Wav.header(0))
            file = raf
            path = f
            windowBytes = 0
            voicedFrames = 0
        }
    }

    private fun closeWindow(reason: String) {
        val w = synchronized(lock) {
            val raf = file ?: return
            val f = path ?: return
            try {
                Wav.patchHeader(raf, windowBytes)
                raf.fd.sync()
                raf.close()
            } catch (_: Exception) {}
            file = null
            path = null
            Window(f, windowStartedAt, System.currentTimeMillis(), voicedFrames * 80, reason, device)
        }
        try { onWindow?.invoke(w) } catch (e: Exception) { Log.w(TAG, "window handler failed", e) }
    }
}
