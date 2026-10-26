package app.echomind.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * "Find the best microphone": records a few seconds in each of the phone's microphone modes
 * while someone talks from across the room, and measures how far voices stand out from the
 * room (clarity) and how loud they arrive. Phones differ a lot here, so this is measured on
 * the phone itself rather than guessed.
 */
object MicTest {
    data class Mode(val key: String, val label: String, val about: String)

    val modes = listOf(
        Mode("recognition", "Voice", "Android's speech-recognition microphone: the bottom mic, little processing. Best when the phone is close to you."),
        Mode("room", "Room", "The camcorder microphone: tuned for a whole room, often uses more than one mic. Usually best for people further away."),
        Mode("standard", "Standard", "The plain microphone, as most apps use it."),
        Mode("raw", "Raw", "No processing at all (not every phone has it). EchoMind's room boost does the lifting."),
    )

    fun label(key: String) = modes.firstOrNull { it.key == key }?.label ?: "Voice"

    fun sourceFor(key: String): Int = when (key) {
        "room" -> MediaRecorder.AudioSource.CAMCORDER
        "standard" -> MediaRecorder.AudioSource.MIC
        "raw" -> MediaRecorder.AudioSource.UNPROCESSED
        else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    fun rawSupported(context: Context) =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    /** One mode's measurement: loud = level of the loudest tenth (voices), floor = the quietest fifth (the room). */
    data class Result(val mode: String, val loudDb: Float, val floorDb: Float) {
        val clarity get() = loudDb - floorDb
    }

    /** Level and clarity from 80 ms frame levels in dBFS. */
    fun measure(mode: String, frameDb: List<Float>): Result? {
        if (frameDb.size < 10) return null
        val s = frameDb.sorted()
        return Result(mode, s[(s.size * 0.9).toInt().coerceAtMost(s.lastIndex)], s[(s.size * 0.2).toInt()])
    }

    /** The mode that hears voices best: clearest, with loudness as a tie-breaker; nothing if all were silent. */
    fun best(results: List<Result>): Result? = results.filter { it.loudDb > -62f && it.clarity >= 6f }.maxByOrNull { it.clarity + 0.15f * it.loudDb }

    /** With no voices to judge by: the mode that records much louder than the one in use (10 dB or more), if any. */
    fun louder(results: List<Result>, current: String): Pair<Result, Float>? {
        val cur = results.firstOrNull { it.mode == current } ?: return null
        val top = results.maxByOrNull { it.floorDb } ?: return null
        val gap = top.floorDb - cur.floorDb
        return if (top.mode != current && gap >= 10f) top to gap else null
    }

    @SuppressLint("MissingPermission")
    suspend fun run(context: Context, seconds: Int = 6, progress: (Mode, Float) -> Unit): List<Result> = withContext(Dispatchers.IO) {
        val out = ArrayList<Result>()
        for (m in modes) {
            if (m.key == "raw" && !rawSupported(context)) continue
            val minBuf = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching { AudioRecord(sourceFor(m.key), Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 1280 * 2 * 8)) }.getOrNull() ?: continue
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); continue }
            val levels = ArrayList<Float>()
            try {
                rec.startRecording()
                val buf = ShortArray(1280)
                val frames = seconds * 1000 / 80
                var skip = 4 // the first moments after opening are often silent or ramping
                while (levels.size < frames) {
                    ensureActive()
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (skip-- > 0) continue
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                    levels.add(20f * log10(max((sqrt(sum / n) / 32768.0).toFloat(), 1e-5f)))
                    progress(m, levels.size.toFloat() / frames)
                }
            } finally {
                runCatching { rec.stop() }; rec.release()
            }
            measure(m.key, levels)?.let(out::add)
        }
        out
    }
}
