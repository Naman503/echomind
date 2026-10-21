package app.echomind.calls

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import app.echomind.capture.AudioDecoder
import app.echomind.core.SettingsStore
import app.echomind.data.AudioItem
import app.echomind.data.Db
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * During a call Android gives the microphone only to the call, so EchoMind
 * hears silence. Instead, the phone's own call recorder saves the call, and
 * this picks the recording up afterwards: found through the media library
 * (READ_MEDIA_AUDIO), decoded to the app's format as a private copy, and
 * queued for transcription like any other recording. The dialer's original
 * file is never modified or deleted.
 */
class CallImporter(private val context: Context, private val db: Db, private val settings: SettingsStore) {
    private val lock = Mutex()
    private val dir = File(context.filesDir, "calls").apply { mkdirs() }

    fun permitted() = context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Imports new call recordings; returns how many were queued. */
    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        val s = settings.value
        if (!s.importCalls || !permitted()) return@withContext 0
        lock.withLock {
            var queued = 0
            val since = s.callsSince / 1000
            val projection = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.IS_PENDING,
            )
            val where = "(${MediaStore.Audio.Media.IS_RECORDING} = 1 OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE '%Call%') AND " +
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE '%Call%' AND ${MediaStore.Audio.Media.DATE_ADDED} >= ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0"
            val found = ArrayList<Triple<Long, String, Pair<Long, Long>>>()
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, where, arrayOf(since.toString()),
                "${MediaStore.Audio.Media.DATE_ADDED} ASC")?.use { c ->
                while (c.moveToNext()) {
                    found.add(Triple(c.getLong(0), c.getString(1) ?: "", c.getLong(3) to c.getLong(4)))
                }
            }
            for ((id, name, meta) in found) {
                val (durationMs, added) = meta
                if (durationMs in 1 until MIN_CALL_MS) continue
                if (db.audio().hasMedia(id)) continue
                // Still being written? Recordings appear when the call ends; wait until it is a minute old.
                if (System.currentTimeMillis() / 1000 - added < 60) continue
                val parsed = parse(name)
                if (parsed.spam) {
                    db.audio().insert(AudioItem(path = "", startedAt = parsed.at ?: added * 1000, endedAt = (parsed.at ?: added * 1000) + durationMs,
                        durationMs = durationMs, voicedMs = 0, source = "call", state = "skipped", contact = parsed.contact, mediaId = id, error = "spam call"))
                    continue
                }
                val out = File(dir, "call_$id.wav")
                val ms = try {
                    AudioDecoder.toWav(context, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), out)
                } catch (e: Exception) {
                    Log.w(TAG, "decode failed for $name", e)
                    out.delete()
                    continue
                }
                val start = parsed.at ?: (added * 1000 - ms)
                db.audio().insert(AudioItem(path = out.absolutePath, startedAt = start, endedAt = start + ms, durationMs = ms, voicedMs = ms,
                    source = "call", contact = parsed.contact, mediaId = id))
                queued++
            }
            queued
        }
    }

    companion object {
        data class Parsed(val contact: String?, val at: Long?, val spam: Boolean)

        /** vivo: "Name 2026-10-20 22-10-16.m4a", "(⛔ Spam Alert From Jio) Name  2026-03-22 16-19-15.m4a"; others: "Name_20261020221016". */
        fun parse(displayName: String): Parsed {
            var name = displayName.substringBeforeLast('.')
            val spam = name.contains("Spam", ignoreCase = true)
            name = name.replace(Regex("^\\(.*?\\)\\s*"), "")
            var at: Long? = null
            Regex("(\\d{4}-\\d{2}-\\d{2})[ _](\\d{2})-(\\d{2})-(\\d{2})").find(name)?.let { m ->
                at = runCatching {
                    LocalDateTime.parse("${m.groupValues[1]}T${m.groupValues[2]}:${m.groupValues[3]}:${m.groupValues[4]}").atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
                name = name.substring(0, m.range.first)
            } ?: Regex("(\\d{14})").find(name)?.let { m ->
                at = runCatching {
                    LocalDateTime.parse(m.value, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
                name = name.substring(0, m.range.first)
            }
            val contact = name.replace('_', ' ').trim().trim('-', ' ').takeIf { it.isNotBlank() }
            return Parsed(contact, at, spam)
        }

        private const val TAG = "EchoCalls"
        const val MIN_CALL_MS = 10_000L
    }
}
