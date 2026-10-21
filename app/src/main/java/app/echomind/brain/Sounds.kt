package app.echomind.brain

import android.util.Log
import app.echomind.ai.Ai
import app.echomind.ai.LlmRequest
import app.echomind.ai.S
import app.echomind.capture.Recordings
import app.echomind.capture.Wav
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.AudioItem
import app.echomind.data.Db
import app.echomind.data.SoundRow
import app.echomind.models.ModelManager
import app.echomind.stt.VoiceId
import app.echomind.util.str
import com.k2fsa.sherpa.onnx.AudioTagging
import com.k2fsa.sherpa.onnx.AudioTaggingConfig
import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What was heard besides talk. A small on-device sound model (CED, trained on
 * AudioSet's 527 sound classes) labels each 10-second slice of a recording.
 *
 * Songs — sung by you or playing nearby — are cut out as their own clip and
 * kept in Songs, with the words that were sung. The optional activity
 * timeline turns slices into stretches like "on the road 8:10–8:40" or
 * "kitchen 1:05–1:30". Nothing here leaves the phone, except a few words of
 * lyrics if you ask to look a song up on the web.
 */
class Sounds(
    private val db: Db,
    private val settings: SettingsStore,
    private val models: ModelManager,
    private val recordings: Recordings,
    private val voice: VoiceId,
    private val ai: Ai,
    private val web: () -> Web,
) {
    private val lock = Mutex()
    private var tagger: AudioTagging? = null
    val songsDir get() = File(recordings.dir.parentFile, "songs").apply { mkdirs() }

    fun ready() = models.isInstalled("sound-tagger")
    fun wanted() = ready() && (settings.value.songs || settings.value.ambient)

    data class Slice(val startMs: Long, val endMs: Long, val events: List<Pair<String, Float>>) {
        fun p(names: Set<String>) = events.filter { it.first in names }.maxOfOrNull { it.second } ?: 0f
        /** Singing shows up spread over related classes (male singing, mantra, lullaby…), so they are summed. */
        fun singing() = events.filter { it.first in SINGING }.sumOf { it.second.toDouble() }.toFloat()
        /** Tuned on real and synthetic singing, speech (alone, quiet, over music), alarms and whistling. */
        fun songish(): Boolean {
            if (events.firstOrNull()?.first in NOT_SONG) return false
            return singing() >= 0.35f || (p(MUSIC) >= 0.5f && events.take(2).any { it.first in MUSIC })
        }
    }

    private fun load(): AudioTagging? {
        tagger?.let { return it }
        val dir = models.path("sound-tagger") ?: return null
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val model = (files.firstOrNull { it.name == "model.int8.onnx" } ?: files.firstOrNull { it.name.endsWith(".onnx") })?.absolutePath ?: return null
        val labels = files.firstOrNull { it.name.endsWith(".csv") }?.absolutePath ?: return null
        return runCatching { AudioTagging(config = AudioTaggingConfig(model = AudioTaggingModelConfig(ced = model, numThreads = 1), labels = labels, topK = 6)) }
            .onFailure { Log.w(TAG, "tagger load failed", it) }.getOrNull()?.also { tagger = it }
    }

    suspend fun release() = lock.withLock { tagger?.release(); tagger = null }

    /** Labels 10-second slices of [file] (every slice, or [maxSlices] spread across it). */
    suspend fun slices(file: File, durationMs: Long, maxSlices: Int = Int.MAX_VALUE): List<Slice> = withContext(Dispatchers.Default) {
        lock.withLock {
            val t = load() ?: return@withLock emptyList()
            val n = ((durationMs + SLICE_MS - 1) / SLICE_MS).toInt().coerceAtLeast(1)
            val picks = if (n <= maxSlices) (0 until n).toList() else (0 until maxSlices).map { it * n / maxSlices }
            picks.mapNotNull { i ->
                val a = i * SLICE_MS
                val b = minOf(durationMs, a + SLICE_MS)
                if (b - a < 2_000) return@mapNotNull null
                val pcm = Wav.readSamples(file, a, b)
                val stream = t.createStream()
                try {
                    stream.acceptWaveform(FloatArray(pcm.size) { pcm[it] / 32768f }, Wav.SAMPLE_RATE)
                    Slice(a, b, t.compute(stream, 6).map { it.name to it.prob })
                } catch (e: Exception) {
                    Log.w(TAG, "tagging failed", e); null
                } finally {
                    stream.release()
                }
            }
        }
    }

    /** A recording about to be transcribed (or one dropped as "no talk"): find songs and surroundings. */
    suspend fun analyse(file: File, item: AudioItem, dropped: Boolean = false) {
        if (!wanted() || !file.exists()) return
        val s = settings.value
        val list = slices(file, item.durationMs, if (dropped && !s.songs) 3 else Int.MAX_VALUE)
        if (list.isEmpty()) return
        if (s.songs) findSongs(file, item, list)
        if (s.ambient) timeline(item, list)
    }

    private suspend fun findSongs(file: File, item: AudioItem, list: List<Slice>) {
        // Runs of slices that are clearly singing or music, at least 20 seconds long.
        val runs = ArrayList<List<Slice>>()
        var cur = ArrayList<Slice>()
        for (sl in list) {
            if (sl.songish()) cur.add(sl) else { if (cur.isNotEmpty()) runs.add(cur); cur = ArrayList() }
        }
        if (cur.isNotEmpty()) runs.add(cur)
        for (run in runs) {
            // A slice that is partly singing (the song started or ended inside it) adds its half next to the song.
            val i0 = list.indexOf(run.first()); val i1 = list.indexOf(run.last())
            val before = list.getOrNull(i0 - 1)?.takeIf { it.singing() >= 0.12f || it.p(MUSIC) >= 0.25f }
            val after = list.getOrNull(i1 + 1)?.takeIf { it.singing() >= 0.12f || it.p(MUSIC) >= 0.25f }
            val a = before?.let { (it.startMs + it.endMs) / 2 } ?: run.first().startMs
            val b = after?.let { (it.startMs + it.endMs) / 2 } ?: run.last().endMs
            val singing = run.map { it.singing().toDouble() }.average()
            val music = run.map { it.p(MUSIC).toDouble() }.average()
            // A short burst counts only when it is clearly singing; otherwise at least 20 s.
            if (b - a < 20_000 && !(b - a >= 8_000 && singing >= 0.6)) continue
            // A singing voice drifts from your speaking voice, so the match is a little looser here.
            val mine = singing > 0.3 && voice.ready() && voice.enrolled() &&
                (voice.embed(file, a + (b - a) / 3, minOf(b, a + (b - a) / 3 + 8_000))?.let { p -> (voice.meScore(p) ?: 0f) >= voice.threshold() - 0.08f } == true)
            val out = File(songsDir, "song_${item.startedAt + a}.m4a")
            if (!recordings.clip(file, a, b, out)) continue
            val top = run.flatMap { it.events }.filter { it.first !in SPEECH }.groupBy { it.first }.mapValues { e -> e.value.sumOf { it.second.toDouble() } }
                .entries.sortedByDescending { it.value }.take(3).joinToString(",", "[", "]") { "{\"name\":\"${it.key.replace("\"", "")}\",\"p\":${"%.2f".format(it.value / run.size)}}" }
            db.sounds().insert(SoundRow(kind = "song", startAt = item.startedAt + a, endAt = item.startedAt + b,
                label = if (singing >= music * 0.6) "Singing" else "Music", labels = top, confidence = maxOf(singing, music).toFloat(),
                audioId = item.id.takeIf { it > 0 }, clipPath = out.absolutePath, mine = mine, createdAt = System.currentTimeMillis()))
        }
    }

    private suspend fun timeline(item: AudioItem, list: List<Slice>) {
        var last = db.sounds().last("ambient")
        for (sl in list) {
            val activity = activity(sl) ?: continue
            val start = item.startedAt + sl.startMs
            val end = item.startedAt + sl.endMs
            // Joins the previous stretch when it's the same thing and close in time.
            if (last != null && last.label == activity && start - last.endAt < 3 * 60_000L) {
                last = last.copy(endAt = maxOf(last.endAt, end)).also { db.sounds().update(it) }
            } else {
                val row = SoundRow(kind = "ambient", startAt = start, endAt = end, label = activity, confidence = sl.events.firstOrNull()?.second ?: 0f, audioId = item.id.takeIf { it > 0 },
                    labels = sl.events.take(3).joinToString(",", "[", "]") { "{\"name\":\"${it.first.replace("\"", "")}\",\"p\":${"%.2f".format(it.second)}}" }, createdAt = System.currentTimeMillis())
                last = row.copy(id = db.sounds().insert(row))
            }
        }
        db.sounds().pruneAmbient(System.currentTimeMillis() - settings.value.ambientDays * 86_400_000L)
    }

    /** After transcription: the words sung in each song become its lyrics. */
    suspend fun fillLyrics(item: AudioItem) {
        for (song in db.sounds().range(item.startedAt, item.endedAt).filter { it.kind == "song" && it.lyrics == null }) {
            val lines = db.utterances().range(song.startAt - 1_000, song.endAt + 1_000)
            val text = lines.joinToString(" ") { it.text }.trim()
            db.sounds().update(song.copy(lyrics = text.ifEmpty { "" }))
            // The lines are lyrics, not talk: labelled so notes and tasks ignore them.
            for (u in lines) if (u.speaker == null || u.speaker == "me" || u.speaker.startsWith("S")) db.utterances().update(u.copy(speaker = Sessionizer.SONG, conversationId = Sessionizer.SONG_BUCKET))
        }
    }

    private val idSchema = S.obj("title" to S.str("song title, or empty if unknown"), "artist" to S.str("artist or film, or empty"), "sure" to S.bool("true only if the results clearly name this song"))

    /** Names a song from its lyrics: the web when allowed (a few lyric words only), else the AI's best guess. */
    suspend fun identify(song: SoundRow, allowWeb: Boolean): SoundRow? {
        val words = song.lyrics.orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 4) return null
        val fragment = words.drop(words.size / 4).take(9).joinToString(" ")
        val results = if (allowWeb && web().enabled()) {
            val q = web().scrub("\"$fragment\" lyrics song") ?: return null
            val ok = web().approve("To name a song you heard", listOf(q))
            ok.flatMap { web().search(it, 6) }
        } else emptyList()
        val user = buildString {
            append("Lyrics heard (automatic transcript, may be misheard): ").append(song.lyrics?.take(600)).append('\n')
            if (results.isNotEmpty()) { append("\nSearch results:\n"); results.forEach { append("- ${it.title}: ${it.snippet.take(200)}\n") } }
        }
        val system = "Name the song these lyrics come from (any language, incl. Hindi film songs). Use the search results when given. If you are not sure, leave title empty rather than guess."
        val r = runCatching { ai.json(LlmRequest(Feature.ASK, "Naming a song", system, user, idSchema, maxTokens = 200, deep = true)) }.getOrNull() ?: return null
        val title = r.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val updated = song.copy(title = title, artist = r.str("artist")?.trim()?.takeIf { it.isNotEmpty() }, identifiedBy = if (results.isNotEmpty()) "web" else "ai")
        db.sounds().update(updated)
        return updated
    }

    /** Background naming of new songs, when allowed to do it by itself. */
    suspend fun identifyPending() {
        if (!settings.value.songsAutoIdentify || settings.value.webSearch != "auto") return
        db.sounds().range(System.currentTimeMillis() - 2 * 86_400_000L, Long.MAX_VALUE).filter { it.kind == "song" && it.title == null && !it.lyrics.isNullOrBlank() }.take(3)
            .forEach { runCatching { identify(it, allowWeb = true) } }
    }

    suspend fun delete(song: SoundRow) {
        song.clipPath?.let { File(it).delete() }
        db.sounds().delete(song.id)
    }

    companion object {
        private const val TAG = "EchoSounds"
        const val SLICE_MS = 10_000L
        val SINGING = setOf("Singing", "Male singing", "Female singing", "Child singing", "Choir", "Humming", "Rapping", "Yodeling", "Chant", "Mantra", "Lullaby", "Vocal music", "A capella", "Synthetic singing")
        /** Sounds that also score as "music" but are not songs. */
        val NOT_SONG = setOf("Smoke detector, smoke alarm", "Fire alarm", "Alarm", "Alarm clock", "Siren", "Civil defense siren", "Telephone bell ringing", "Ringtone", "Beep, bleep", "Whistling", "Speech synthesizer", "Doorbell")
        val MUSIC = setOf("Music", "Musical instrument", "Pop music", "Music of Bollywood", "Music of Asia", "Rock music", "Hip hop music", "Electronic music",
            "Dance music", "Folk music", "Carnatic music", "Classical music", "Soundtrack music", "Background music", "Guitar", "Piano", "Drum", "Tabla", "Sitar",
            "Christian music", "Gospel music", "Patriotic music", "Traditional music", "Independent music", "Happy music", "Sad music", "Tender music")
        val SPEECH = setOf("Speech", "Male speech, man speaking", "Female speech, woman speaking", "Child speech, kid speaking", "Conversation", "Narration, monologue")

        /** What a slice says about what was going on. Order matters: the first match wins. */
        private val activities = listOf(
            "On the road" to listOf("traffic", "vehicle horn", "car passing", "motor vehicle", "car", "bus", "truck", "motorcycle", "honk", "engine", "tire squeal"),
            "Train / metro" to listOf("train", "rail transport", "subway", "railroad"),
            "Kitchen" to listOf("dishes", "cutlery", "frying", "microwave", "blender", "chopping", "boiling", "kettle", "pressure cooker", "sizzle"),
            "Housework" to listOf("vacuum cleaner", "washing machine", "sweeping", "toothbrush", "hair dryer"),
            "Water / shower" to listOf("shower", "bathtub", "water tap", "sink (filling", "toilet flush"),
            "Singing" to listOf("singing", "humming", "choir", "rapping"),
            "Music" to listOf("music", "musical instrument", "guitar", "piano", "drum", "tabla"),
            "TV / video" to listOf("television", "radio", "speech synthesizer"),
            "At the computer" to listOf("typing", "computer keyboard", "mouse click", "writing"),
            "Walking" to listOf("walk, footsteps", "run", "footsteps"),
            "Busy place" to listOf("crowd", "chatter", "hubbub", "children playing", "applause", "cheering"),
            "Kids around" to listOf("baby cry", "children shouting", "baby laughter", "child speech"),
            "Pets / animals" to listOf("dog", "cat", "bark", "meow", "bird", "rooster", "cattle"),
            "Outdoors" to listOf("wind", "rain", "thunder", "outside, urban", "outside, rural", "insect", "cricket"),
            "Talking" to listOf("speech", "conversation", "narration"),
            "Resting / quiet" to listOf("silence", "snoring", "breathing"),
        )

        /** The surroundings win over talk when they're clearly there (talking in traffic is "on the road"). */
        fun activity(sl: Slice): String? {
            for (speechToo in listOf(false, true)) for ((name, p) in sl.events) {
                if (p < (if (speechToo) 0.2f else 0.25f) || (!speechToo && name in SPEECH)) continue
                val n = name.lowercase()
                activities.firstOrNull { (_, keys) -> keys.any { n.contains(it) } }?.let { return it.first }
            }
            return null
        }
    }
}
