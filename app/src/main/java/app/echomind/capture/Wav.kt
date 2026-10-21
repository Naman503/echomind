package app.echomind.capture

import java.io.File
import java.io.RandomAccessFile

/** 16 kHz mono 16-bit PCM in a 44-byte RIFF container: the one audio format the app stores. */
object Wav {
    const val SAMPLE_RATE = 16_000
    const val HEADER_BYTES = 44
    const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000 // 32

    fun durationMs(file: File): Long = ((file.length() - HEADER_BYTES).coerceAtLeast(0)) / BYTES_PER_MS

    fun header(dataBytes: Long): ByteArray {
        val b = ByteArray(HEADER_BYTES)
        fun str(at: Int, s: String) = s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }
        fun int(at: Int, v: Long) { for (i in 0 until 4) b[at + i] = (v shr (8 * i) and 0xff).toByte() }
        fun short(at: Int, v: Int) { b[at] = (v and 0xff).toByte(); b[at + 1] = (v shr 8 and 0xff).toByte() }
        str(0, "RIFF"); int(4, 36 + dataBytes); str(8, "WAVE")
        str(12, "fmt "); int(16, 16); short(20, 1); short(22, 1)
        int(24, SAMPLE_RATE.toLong()); int(28, SAMPLE_RATE * 2L); short(32, 2); short(34, 16)
        str(36, "data"); int(40, dataBytes)
        return b
    }

    /** Rewrites the sizes in place so a file cut short by a crash is still a valid WAV. */
    fun patchHeader(raf: RandomAccessFile, dataBytes: Long) {
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.seek(pos)
    }

    /** Repairs a window left open by a process death: header sizes from the real file length. */
    fun repair(file: File) {
        if (file.length() < HEADER_BYTES) return
        RandomAccessFile(file, "rw").use { raf ->
            val data = (raf.length() - HEADER_BYTES) / 2 * 2
            raf.seek(0)
            raf.write(header(data))
        }
    }

    /** Reads [startMs, endMs) as 16-bit samples. */
    fun readSamples(file: File, startMs: Long, endMs: Long): ShortArray {
        val from = HEADER_BYTES + startMs * BYTES_PER_MS
        val to = (HEADER_BYTES + endMs * BYTES_PER_MS).coerceAtMost(file.length())
        val n = ((to - from).coerceAtLeast(0) / 2).toInt()
        val out = ShortArray(n)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val bytes = ByteArray(n * 2)
            raf.readFully(bytes)
            for (i in 0 until n) out[i] = ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return out
    }

    fun write(file: File, samples: ShortArray) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header(samples.size * 2L))
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val s = samples[i].toInt()
                bytes[i * 2] = (s and 0xff).toByte()
                bytes[i * 2 + 1] = (s shr 8 and 0xff).toByte()
            }
            raf.write(bytes)
        }
    }
}
