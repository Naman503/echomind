package app.echomind.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import app.echomind.core.SettingsStore
import app.echomind.data.AudioItem
import app.echomind.data.Db
import java.io.File
import java.io.RandomAccessFile

/**
 * The record of what was actually heard. Once a recording's words are safe it
 * is squeezed from WAV to a small AAC file (about a tenth of the size) and
 * kept for as long as the user chose; then it is deleted on its own. Single
 * recordings can be kept forever or deleted by hand.
 */
class Recordings(private val context: Context, private val db: Db, private val settings: SettingsStore) {
    val dir = File(context.filesDir, "kept").apply { mkdirs() }

    /** After transcription: keeps a compressed copy (if keeping is on) and deletes the working WAV. */
    suspend fun keep(item: AudioItem, wav: File) {
        val days = settings.value.keepRecordingsDays
        if (days != 0 && wav.exists() && item.keptPath == null) {
            val out = File(dir, "rec_${item.startedAt}_${item.id}.m4a")
            val ok = runCatching { encode(wav, out, if (settings.value.recordingQuality == "clear") 32_000 else 16_000) }
                .onFailure { Log.w("EchoRecordings", "encode failed", it) }.isSuccess && out.length() > 0
            if (ok) db.audio().get(item.id)?.let { db.audio().update(it.copy(keptPath = out.absolutePath)) } else out.delete()
        }
        wav.delete()
    }

    /** Deletes recordings whose time is up. Cheap; called on app open and every 15 minutes. */
    suspend fun sweep(now: Long = System.currentTimeMillis()) {
        val days = settings.value.keepRecordingsDays
        val before = if (days < 0) 0 else now - days * 86_400_000L
        for (a in db.audio().expiredKeeps(before, now)) drop(a)
    }

    suspend fun delete(item: AudioItem) = drop(item)

    suspend fun keepForever(item: AudioItem, forever: Boolean) {
        db.audio().update(item.copy(keepUntil = if (forever) Long.MAX_VALUE else null))
    }

    /** The playable file for a recording: the kept copy, or the WAV still waiting to be transcribed. */
    fun file(item: AudioItem): File? = item.keptPath?.let(::File)?.takeIf { it.exists() }
        ?: item.path.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.exists() }

    fun bytesUsed(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    private suspend fun drop(a: AudioItem) {
        a.keptPath?.let { File(it).delete() }
        db.audio().update(a.copy(keptPath = null, keepUntil = null))
    }

    /** 16 kHz mono PCM WAV → AAC-LC in an .m4a, with the platform encoder. */
    private fun encode(wav: File, out: File, bitrate: Int) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Wav.SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            RandomAccessFile(wav, "r").use { raf ->
                raf.seek(Wav.HEADER_BYTES.toLong())
                val total = raf.length() - Wav.HEADER_BYTES
                var read = 0L
                var inputDone = false
                val info = MediaCodec.BufferInfo()
                val chunk = ByteArray(8_192)
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = raf.read(chunk, 0, minOf(chunk.size, buf.remaining()))
                            val ptsUs = read * 1_000_000L / (Wav.SAMPLE_RATE * 2)
                            if (n <= 0 || read >= total) {
                                codec.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buf.put(chunk, 0, n)
                                codec.queueInputBuffer(i, 0, n, ptsUs, 0)
                                read += n
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxing) { buf.position(info.offset); buf.limit(info.offset + info.size); muxer.writeSampleData(track, buf, info) }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
            runCatching { if (muxing) muxer.stop() }; runCatching { muxer.release() }
        }
    }
}
