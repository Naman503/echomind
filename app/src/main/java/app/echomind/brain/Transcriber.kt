package app.echomind.brain

import android.util.Log
import app.echomind.capture.SpeechFinder
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.AudioItem
import app.echomind.data.Db
import app.echomind.data.Utterance
import app.echomind.data.VectorRow
import app.echomind.stt.AndroidSpeech
import app.echomind.stt.SherpaSpeech
import app.echomind.stt.SpeechEngine
import app.echomind.stt.SpeechException
import app.echomind.stt.SpeechRequest
import app.echomind.stt.SpokenLine
import app.echomind.stt.VoiceId
import app.echomind.work.Governor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlinx.serialization.builtins.serializer

/**
 * Works through recorded audio: finds the speech, transcribes it piece by
 * piece, writes timestamped lines, and deletes the audio. Progress is saved
 * after every piece, so a restart (or Android killing the app) resumes exactly
 * where it stopped and never repeats or loses text.
 */
class Transcriber(
    private val db: Db,
    private val settings: SettingsStore,
    private val governor: Governor,
    private val engines: Map<String, SpeechEngine>,
    private val android: AndroidSpeech,
    private val sherpa: SherpaSpeech,
    private val voice: VoiceId,
    private val recordings: app.echomind.capture.Recordings,
) {
    data class Status(
        val active: Boolean = false,
        val source: String = "",
        val contact: String? = null,
        val engine: String = "",
        val piece: Int = 0,
        val pieces: Int = 0,
        val audioStartedAt: Long = 0,
        val lastError: String? = null,
        /** Learned speed: ms of audio processed per ms of work, for the ETA. */
        val speed: Double = 1.5,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    enum class Outcome { EMPTY, DONE, FAILED }

    /** Transcribes the next waiting recording. */
    suspend fun step(): Outcome {
        val item = db.audio().next() ?: return Outcome.EMPTY
        return try {
            if (process(item)) Outcome.DONE else Outcome.FAILED
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Anything unexpected (I/O, out of memory) fails this item with backoff instead of stalling the queue.
            Log.w(TAG, "processing ${item.id} crashed", e)
            fail(item, Exception(e.message ?: e.javaClass.simpleName, e))
            Outcome.FAILED
        }
    }

    private fun engineFor(item: AudioItem): Pair<String, SpeechEngine> {
        val s = settings.value
        val feature = if (item.source == "call") Feature.CALLS else Feature.TRANSCRIBE
        var id = s.provider(feature)
        // Engines that need something missing fall back to one that works offline.
        if (id == "sherpa" && sherpa.installedModel() == null) id = "android"
        if (id == "android" && !android.available()) id = if (sherpa.installedModel() != null) "sherpa" else "android"
        return id to (engines[id] ?: android)
    }

    private var hintCache: Pair<Long, List<String>> = 0L to emptyList()

    /** Names of people, projects and places it knows, so the recogniser spells them right. Refreshed every 10 minutes. */
    private suspend fun hints(): List<String> {
        val now = System.currentTimeMillis()
        if (now - hintCache.first < 10 * 60_000L) return hintCache.second
        val names = db.memory().allEntities().sortedByDescending { it.mentions }.take(80).flatMap { e ->
            listOf(e.name) + runCatching { app.echomind.util.Js.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(String.serializer()), e.aliases) }.getOrDefault(emptyList<String>())
        } + listOfNotNull(settings.value.userName.takeIf { it.isNotBlank() })
        return names.map { it.trim() }.filter { it.length in 2..40 }.distinct().take(100).also { hintCache = now to it }
    }

    private fun localFallback(primary: String): SpeechEngine? = when {
        primary != "android" && android.available() -> android
        primary != "sherpa" && sherpa.installedModel() != null -> sherpa
        else -> null
    }

    private suspend fun process(item: AudioItem): Boolean {
        val file = File(item.path)
        if (!file.exists() || file.length() < 1_000) {
            db.audio().update(item.copy(state = "failed", error = "audio file missing"))
            return false
        }
        val (engineId, engine) = engineFor(item)
        db.audio().update(item.copy(state = "working", engine = engineId))
        val pieces = SpeechFinder.pieces(file, engine.maxPieceMs)
            .filter { it.endMs > item.progressMs }
            .map { if (it.startMs < item.progressMs) it.copy(startMs = item.progressMs) else it }
        if (pieces.isEmpty()) {
            finish(item, file)
            return true
        }
        val langs = settings.value.speechLanguages.ifEmpty { listOf("en-IN") }
        val hints = hints()
        var lastText: String? = null
        for ((i, piece) in pieces.withIndex()) {
            governor.awaitTurn()
            _status.value = _status.value.copy(active = true, source = item.source, contact = item.contact, engine = engineId, piece = i + 1, pieces = pieces.size, audioStartedAt = item.startedAt)
            val req = SpeechRequest(file, piece.startMs, piece.endMs, langs, isCall = item.source == "call", hints = hints)
            val began = System.currentTimeMillis()
            val lines: List<SpokenLine> = try {
                engine.transcribe(req)
            } catch (e: Exception) {
                val fallback = localFallback(engineId)
                Log.w(TAG, "$engineId failed on piece ${i + 1}: ${e.message}${if (fallback != null) " — falling back to ${fallback.id}" else ""}")
                if (fallback != null && (engineId !in LOCAL || (e as? SpeechException)?.retryable == false)) {
                    try {
                        fallback.transcribe(req)
                    } catch (e2: Exception) {
                        fail(item, e2); return false
                    }
                } else {
                    fail(item, e); return false
                }
            }
            val workMs = System.currentTimeMillis() - began
            governor.workDone(workMs)
            val pieceMs = piece.endMs - piece.startMs
            if (workMs > 200) {
                val sp = pieceMs.toDouble() / workMs
                _status.value = _status.value.copy(speed = _status.value.speed * 0.8 + sp * 0.2, lastError = null)
            }

            val rows = ArrayList<Utterance>()
            for (l in lines) {
                val text = l.text.trim()
                if (text.isEmpty() || text.equals(lastText, ignoreCase = true)) continue
                lastText = text
                rows.add(
                    Utterance(
                        audioId = item.id,
                        startAt = item.startedAt + l.startMs,
                        endAt = item.startedAt + maxOf(l.endMs, l.startMs + 500),
                        text = text,
                        lang = l.lang,
                        speaker = l.speaker,
                        source = item.source,
                        engine = engineId,
                        searchText = TextNorm.searchable(text),
                    ),
                )
            }
            // "Forget the last N minutes" may have removed this recording while it was being transcribed.
            if (db.audio().get(item.id) == null) {
                file.delete(); _status.value = _status.value.copy(active = false); return true
            }
            if (rows.isNotEmpty()) {
                val ids = db.utterances().insertAll(rows)
                labelVoices(file, item, rows.zip(ids))
            }
            db.audio().setProgress(item.id, piece.endMs)
        }
        finish(item, file)
        return true
    }

    /** "me" vs others by voice; keeps each line's voice-print for per-conversation speaker grouping. */
    private suspend fun labelVoices(file: File, item: AudioItem, rows: List<Pair<Utterance, Long>>) {
        if (!settings.value.labelSpeakers || !voice.ready()) return
        for ((u, id) in rows) {
            val startMs = u.startAt - item.startedAt
            val endMs = u.endAt - item.startedAt
            if (endMs - startMs < 1_200) continue
            val print = runCatching { voice.embed(file, startMs, endMs) }.getOrNull() ?: continue
            val (bytes, scale) = Vectors.quantize(print)
            db.vectors().put(VectorRow("s", id, bytes, scale, "voice-id", System.currentTimeMillis()))
            if (voice.enrolled() && voice.isMe(print)) {
                db.utterances().update(u.copy(id = id, speaker = "me"))
            } else if (item.source == "call" && voice.enrolled()) {
                db.utterances().update(u.copy(id = id, speaker = item.contact ?: "Caller"))
            }
        }
    }

    private suspend fun finish(item: AudioItem, file: File) {
        val latest = db.audio().get(item.id) ?: item
        db.audio().update(latest.copy(state = "done", progressMs = latest.durationMs, error = null))
        // The working WAV goes once its words are safe; a small compressed copy stays as the record
        // for as long as the user chose. Imported call recordings are copies; the dialer's original is never touched.
        recordings.keep(latest, file)
        _status.value = _status.value.copy(active = false)
    }

    private suspend fun fail(item: AudioItem, e: Exception) {
        val latest = db.audio().get(item.id) ?: item
        val attempts = latest.attempts + 1
        val permanent = (e as? SpeechException)?.retryable == false
        // 2, 4, 8, 16, 32 minutes: a network blip or a busy recogniser doesn't burn every attempt at once.
        val retryAt = System.currentTimeMillis() + (60_000L shl attempts.coerceAtMost(5))
        db.audio().update(latest.copy(state = if (permanent || attempts >= 6) "failed" else "pending", attempts = attempts, retryAt = retryAt, error = e.message))
        _status.value = _status.value.copy(active = false, lastError = e.message)
    }

    fun idle() {
        _status.value = _status.value.copy(active = false)
    }

    companion object {
        private const val TAG = "EchoTranscriber"
        private val LOCAL = setOf("android", "sherpa")
    }
}
