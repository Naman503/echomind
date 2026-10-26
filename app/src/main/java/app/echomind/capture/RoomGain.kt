package app.echomind.capture

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * EchoMind's own gain stage for a phone lying in a room: lifts quiet and distant
 * voices to the level speech recognisers expect (about -24 dBFS), frame by frame.
 *
 * - Follows two levels: the noise floor (quiet frames) and speech (frames well above it).
 * - Gain rises slowly (about 6 dB/s) and falls at once, so a sudden loud voice never clips;
 *   a soft limiter catches what's left.
 * - Room noise is never lifted above about -40 dBFS, so a quiet room doesn't become a hiss.
 */
class RoomGain(private val maxGainDb: Float = 18f, private val targetDb: Float = -24f, private val noiseCeilingDb: Float = -40f) {
    var gainDb = 0f
        private set
    var speechDb = -40f
        private set
    var noiseDb = -60f
        private set

    /** Processes one frame in place. */
    fun process(frame: ShortArray, n: Int = frame.size) {
        if (n <= 0) return
        var sum = 0.0
        var peak = 0
        for (i in 0 until n) { val s = frame[i].toInt(); sum += (s * s).toDouble(); peak = max(peak, abs(s)) }
        val db = 20f * log10(max((sqrt(sum / n) / 32768.0).toFloat(), 1e-5f))
        // Floor: drops at once, creeps up slowly (as Recorder's meter does).
        noiseDb = if (db < noiseDb) db else noiseDb + 0.02f
        if (db > noiseDb + 8f && db > -65f) speechDb = speechDb * 0.92f + db * 0.08f
        val want = (targetDb - speechDb).coerceIn(0f, maxGainDb).let { min(it, max(0f, noiseCeilingDb - noiseDb)) }
        gainDb = if (want > gainDb) min(want, gainDb + 0.5f) else want
        // Never let this frame's peak go past full scale.
        val g0 = 10f.pow(gainDb / 20f)
        val g = if (peak * g0 > 32_000f) 32_000f / max(peak, 1) else g0
        if (g <= 1.0001f) return
        for (i in 0 until n) {
            val x = frame[i] * g / 32768f
            // Soft knee above 0.7 of full scale.
            val y = if (abs(x) <= 0.7f) x else (0.7f + 0.3f * tanh((abs(x) - 0.7f) / 0.3f)) * if (x < 0) -1 else 1
            frame[i] = (y * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    companion object {
        /** "off" | "auto" | "strong" → the most it may lift, in dB (null = off). */
        fun maxFor(mode: String): Float? = when (mode) { "off" -> null; "strong" -> 30f; else -> 18f }
    }
}
