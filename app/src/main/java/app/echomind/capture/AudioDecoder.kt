package app.echomind.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/**
 * Decodes any audio file Android can read (the dialer's AAC .m4a call
 * recordings, voice notes) into the app's one format: 16 kHz mono 16-bit WAV.
 * Streams through the codec, so a 30-minute call never sits in memory.
 */
object AudioDecoder {
    fun toWav(context: Context, uri: Uri, out: File): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val raf = RandomAccessFile(out, "rw")
            raf.setLength(0)
            raf.write(Wav.header(0))
            var written = 0L
            val resampler = Resampler()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        outIndex >= 0 -> {
                            val buf = codec.getOutputBuffer(outIndex)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = shorts.remaining() / channels
                            val mono = FloatArray(frames)
                            for (i in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) sum += shorts.get(i * channels + c)
                                mono[i] = sum / channels
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            val pcm = resampler.process(mono, rate)
                            val bytes = ByteArray(pcm.size * 2)
                            for (i in pcm.indices) {
                                val s = pcm[i].toInt().coerceIn(-32768, 32767)
                                bytes[i * 2] = (s and 0xff).toByte()
                                bytes[i * 2 + 1] = (s shr 8 and 0xff).toByte()
                            }
                            raf.write(bytes)
                            written += bytes.size
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                Wav.patchHeader(raf, written)
            } finally {
                raf.close()
                codec.stop()
                codec.release()
            }
            return written / Wav.BYTES_PER_MS
        } finally {
            extractor.release()
        }
    }

    /**
     * Streaming resampler to 16 kHz: a box low-pass before decimation (enough
     * for speech; aliasing above 8 kHz carries nothing a recogniser uses),
     * then linear interpolation. Keeps phase across buffers.
     */
    private class Resampler {
        private var pos = 0.0
        private var last = 0f
        private var carry = FloatArray(0)

        fun process(input: FloatArray, rate: Int): FloatArray {
            if (rate == Wav.SAMPLE_RATE) return input
            val step = rate.toDouble() / Wav.SAMPLE_RATE
            val src = if (step > 1.0) lowPass(input, step.toInt().coerceAtLeast(1)) else input
            val all = FloatArray(carry.size + src.size).also { carry.copyInto(it); src.copyInto(it, carry.size) }
            val out = ArrayList<Float>((all.size / step).toInt() + 2)
            while (pos + 1 < all.size) {
                val i = pos.toInt()
                val frac = (pos - i).toFloat()
                out.add(all[i] * (1 - frac) + all[i + 1] * frac)
                pos += step
            }
            val keepFrom = pos.toInt().coerceAtMost(all.size)
            carry = all.copyOfRange(keepFrom, all.size)
            pos -= keepFrom
            return out.toFloatArray()
        }

        private fun lowPass(x: FloatArray, n: Int): FloatArray {
            if (n <= 1) return x
            val y = FloatArray(x.size)
            for (i in x.indices) {
                last = last + (x[i] - last) / n
                y[i] = last
            }
            return y
        }
    }
}
