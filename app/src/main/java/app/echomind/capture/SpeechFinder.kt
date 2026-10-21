package app.echomind.capture

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds where the speech is in a recorded window, so speech models only see
 * audio that contains some, in pieces short enough to checkpoint.
 *
 * Energy VAD with an adaptive floor: crude next to a neural VAD, but it costs
 * milliseconds for minutes of audio and only has to do the easy half (skip
 * silence); the speech model does the hard half. It errs towards keeping
 * audio. Pieces are cut at the quietest frame near the size limit, so a word
 * is never split between two pieces when a pause exists.
 */
object SpeechFinder {
    private const val FRAME_MS = 30
    private const val FRAME_SAMPLES = Wav.SAMPLE_RATE * FRAME_MS / 1000
    private const val MIN_GAP_MS = 700
    // Generous padding: recognisers need a little silence on both sides to place word boundaries.
    private const val PAD_MS = 400
    private const val MIN_SPEECH_MS = 240
    /** A pause this long starts a new piece even if the current one has room. */
    private const val SPLIT_GAP_MS = 2_500

    data class Piece(val startMs: Long, val endMs: Long, val speechMs: Long)

    /** dB per 30 ms frame. */
    private fun frameLevels(file: File): FloatArray {
        val frames = ((file.length() - Wav.HEADER_BYTES).coerceAtLeast(0) / 2 / FRAME_SAMPLES).toInt()
        val out = FloatArray(frames)
        BufferedInputStream(FileInputStream(file), 1 shl 16).use { input ->
            input.skip(Wav.HEADER_BYTES.toLong())
            val buf = ByteArray(FRAME_SAMPLES * 2)
            for (f in 0 until frames) {
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                var sum = 0.0
                val samples = read / 2
                for (i in 0 until samples) {
                    val s = (buf[i * 2].toInt() and 0xff) or (buf[i * 2 + 1].toInt() shl 8)
                    sum += (s * s).toDouble()
                }
                val rms = if (samples > 0) sqrt(sum / samples) / 32768.0 else 0.0
                out[f] = (20 * log10(max(rms, 1e-5))).toFloat()
            }
        }
        return out
    }

    fun pieces(file: File, maxPieceMs: Long): List<Piece> {
        val db = frameLevels(file)
        if (db.isEmpty()) return emptyList()
        val sorted = db.sortedArray()
        // The 15th percentile is a noise floor that survives a mostly-speech window.
        val floor = sorted[(sorted.size * 0.15).toInt().coerceIn(0, sorted.size - 1)]
        val threshold = max(floor + 8f, -55f)
        val voiced = BooleanArray(db.size) { db[it] > threshold }

        val gapFrames = MIN_GAP_MS / FRAME_MS
        val spans = ArrayList<IntArray>() // start, endExclusive, voicedFrames
        var i = 0
        while (i < voiced.size) {
            if (!voiced[i]) { i++; continue }
            val start = i
            var end = i
            var count = 0
            var gap = 0
            while (i < voiced.size && gap <= gapFrames) {
                if (voiced[i]) { end = i + 1; count++; gap = 0 } else gap++
                i++
            }
            if (count * FRAME_MS >= MIN_SPEECH_MS) spans.add(intArrayOf(start, end, count))
        }
        if (spans.isEmpty()) return emptyList()

        val padFrames = PAD_MS / FRAME_MS
        val maxFrames = (maxPieceMs / FRAME_MS).toInt()
        val splitGapFrames = SPLIT_GAP_MS / FRAME_MS
        val pieces = ArrayList<Piece>()
        var curStart = -1
        var curEnd = -1
        var curVoiced = 0

        fun flush() {
            if (curStart < 0) return
            val s = (curStart - padFrames).coerceAtLeast(0)
            val e = (curEnd + padFrames).coerceAtMost(db.size)
            pieces.add(Piece(s.toLong() * FRAME_MS, e.toLong() * FRAME_MS, curVoiced.toLong() * FRAME_MS))
            curStart = -1
            curVoiced = 0
        }

        for (span in spans) {
            var s = span[0]
            val e = span[1]
            var v = span[2]
            if (curStart >= 0 && (s - curEnd > splitGapFrames || e - curStart > maxFrames)) flush()
            while (e - (if (curStart >= 0) curStart else s) > maxFrames) {
                val from = if (curStart >= 0) curStart else s
                val limit = from + maxFrames
                val searchFrom = (limit - 5_000 / FRAME_MS).coerceAtLeast(from + maxFrames / 2)
                var cut = limit
                var quietest = Float.POSITIVE_INFINITY
                for (f in searchFrom until limit) if (db[f] < quietest) { quietest = db[f]; cut = f }
                if (curStart < 0) curStart = from
                curEnd = cut
                val share = (cut - s).coerceAtLeast(0) * v / max(1, e - s)
                curVoiced += share
                v -= share
                flush()
                s = cut
                curStart = cut
                curEnd = cut
            }
            if (curStart < 0) curStart = s
            curEnd = e
            curVoiced += v
        }
        flush()
        return pieces
    }
}
