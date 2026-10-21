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
import app.echomind.util.bool
import app.echomind.util.str
import app.echomind.util.strings
import com.k2fsa.sherpa.onnx.AudioTagging
import com.k2fsa.sherpa.onnx.AudioTaggingConfig
import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
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
    private val context: android.content.Context,
    private val db: Db,
    private val settings: SettingsStore,
    private val models: ModelManager,
    private val recordings: Recordings,
    private val voice: VoiceId,
    private val ai: Ai,
    private val web: () -> Web,
    private val cloud: app.echomind.core.Cloud,
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
        /** Quiet singing next to a clearly sung slice: enough to carry a song on, not to start one. */
        fun songContinues() = events.firstOrNull()?.first !in NOT_SONG && (singing() >= 0.15f || p(MUSIC) >= 0.3f)
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

    /** Songs in [file]; returns how many were saved. */
    suspend fun findSongs(file: File, item: AudioItem, list: List<Slice>): Int {
        var saved = 0
        for ((a, b, run) in songSpans(list)) {
            val singing = run.map { it.singing().toDouble() }.average()
            val music = run.map { it.p(MUSIC).toDouble() }.average()
            // Already found (a re-scan of a recording that was analysed before).
            if (db.sounds().range(item.startedAt + a, item.startedAt + b).any { it.kind == "song" }) continue
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
            saved++
        }
        return saved
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
        var found = false
        for (song in db.sounds().range(item.startedAt, item.endedAt).filter { it.kind == "song" && it.lyrics == null }) {
            val lines = db.utterances().range(song.startAt - 1_000, song.endAt + 1_000)
            val text = lines.joinToString(" ") { it.text }.trim()
            db.sounds().update(song.copy(lyrics = text.ifEmpty { "" }))
            // The lines are lyrics, not talk: labelled so notes and tasks ignore them.
            for (u in lines) if (u.speaker != "EchoMind" && u.source != "checkin" && u.speaker != Sessionizer.SONG) db.utterances().update(u.copy(speaker = Sessionizer.SONG, conversationId = Sessionizer.SONG_BUCKET))
            found = true
        }
        // Name it right away, in the background.
        if (found && settings.value.songsAutoIdentify) app.echomind.App.graph.scope.launch { runCatching { identifyPending() } }
    }

    private val guessSchema = S.obj(
        "title" to S.str("the song's title, or empty if you don't recognise it"),
        "artist" to S.str("singer or composer, or empty"),
        "film" to S.str("film or album, or empty"),
        "latin" to S.str("the heard lyrics written in Latin letters, the usual way for this language"),
        "phrases" to S.arr(S.str(), "2-4 short phrases from the lyrics most likely to be (part of) the title, in Latin letters"),
        "sure" to S.bool("true only if you clearly know this song"),
    )

    /**
     * Names a song from the words that were sung. On the phone, the AI romanises the lyrics and
     * guesses. Online (if allowed: only lyric phrases and titles are sent), Apple's music catalogue
     * gives candidates, and each candidate's real lyrics (LRCLIB, an open lyrics database) are
     * compared with what was heard — the closest match wins, with its poster, film, year and links.
     */
    suspend fun identify(song: SoundRow, online: Boolean = settings.value.songsOnline): SoundRow? {
        val heard = song.lyrics.orEmpty().trim()
        db.sounds().update(song.copy(lookupAt = System.currentTimeMillis()))
        if (heard.split(Regex("\\s+")).size < 3) return null
        val guess = if (ai.ready(Feature.ASK)) runCatching {
            ai.json(LlmRequest(Feature.ASK, "Recognising a song", "You recognise songs from their lyrics, especially Hindi film songs. The lyrics come from automatic speech recognition, so expect misheard words.",
                "Heard lyrics: ${heard.take(800)}", guessSchema, maxTokens = 300, deep = true))
        }.getOrNull() else null
        // A "title" the model hedged on ("… (likely misheard)") is not a title.
        val guessTitle = guess?.str("title")?.trim()?.takeIf { it.isNotBlank() && !Regex("likely|misheard|unknown|not sure|\\?", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        Log.i(TAG, "song guess: ${guessTitle ?: "-"} / phrases ${guess?.strings("phrases")}")
        val latin = guess?.str("latin")?.takeIf { it.isNotBlank() } ?: TextNorm.transliterate(heard)
        var best: SongMatch? = null
        if (online) {
            val terms = (listOfNotNull(guessTitle?.let { t -> listOfNotNull(t, guess?.str("film")?.takeIf { it.isNotBlank() }).joinToString(" ") }) +
                guess?.strings("phrases").orEmpty() + titlePhrases(latin) + distinctWords(latin)).map { it.trim() }.filter { it.length >= 3 }.distinctBy { it.lowercase() }.take(6)
            val candidates = terms.flatMap { t -> runCatching { itunes(t) }.onFailure { Log.w(TAG, "catalogue lookup failed for $t", it) }.getOrDefault(emptyList()) }.distinctBy { norm(it.title) + "|" + it.artist.take(12).lowercase() }.take(8)
            best = candidates.map { c ->
                val lyrics = runCatching { lrclib(c.title, c.artist) }.onFailure { Log.w(TAG, "lyrics lookup failed for ${c.title}", it) }.getOrNull()
                val overlap = lyrics?.let { lyricOverlap(heard + " " + latin, it) } ?: 0f
                val titleIn = titleInLyrics(c.title, heard + " " + latin)
                val agrees = guessTitle?.let { g -> norm(g) == norm(c.title) } == true
                c.copy(score = overlap * 0.7f + titleIn * 0.3f + if (agrees) 0.2f else 0f, overlap = overlap)
            }.also { list -> list.forEach { Log.i(TAG, "candidate ${it.title} — ${it.artist}: overlap ${"%.2f".format(it.overlap)} score ${"%.2f".format(it.score)}") } }
                .let { pickSong(it) }
            Log.i(TAG, "song terms $terms → ${candidates.size} candidates → ${best?.title ?: "no match"}")
            // The same song appears in many releases: the original soundtrack's poster and year.
            best = best?.let { b -> candidates.filter { norm(it.title) == norm(b.title) }.minWithOrNull(compareBy({ !it.album.contains("Motion Picture", true) && !it.album.contains("Soundtrack", true) }, { it.year ?: 9999 }))?.copy(score = b.score, overlap = b.overlap) ?: b }
        }
        val updated = when {
            best != null -> {
                val art = best.artwork?.let { url -> runCatching { saveArt(song.id, url) }.getOrNull() }
                song.copy(title = best.title, artist = best.artist, album = best.album.replace(Regex("\\s*\\((Original Motion Picture Soundtrack|Original Soundtrack)\\)", RegexOption.IGNORE_CASE), ""),
                    year = best.year, artPath = art ?: song.artPath, previewUrl = best.preview, links = links(best.title, best.artist, best.appleUrl),
                    identifiedBy = "web", nameConfidence = best.score.coerceAtMost(1f), lookupAt = System.currentTimeMillis())
            }
            guessTitle != null -> song.copy(title = guessTitle, artist = guess.str("artist")?.takeIf { it.isNotBlank() },
                album = guess?.str("film")?.takeIf { it.isNotBlank() }, links = links(guessTitle, guess?.str("artist").orEmpty(), null), identifiedBy = "ai",
                nameConfidence = if (guess?.bool("sure") == true) 0.6f else 0.3f, lookupAt = System.currentTimeMillis())
            else -> return null
        }
        db.sounds().update(updated)
        return updated
    }

    /** You named the song: kept as yours, with the poster and links looked up for that title (if online is on). */
    suspend fun rename(song: SoundRow, title: String, artist: String) {
        var s = song.copy(title = title, artist = artist.ifBlank { null }, identifiedBy = "you", nameConfidence = 1f, links = links(title, artist, null))
        if (settings.value.songsOnline) runCatching { itunes(listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")) }.getOrNull()
            ?.filter { norm(it.title) == norm(title) || norm(it.title).contains(norm(title)) }
            ?.minWithOrNull(compareBy({ !it.album.contains("Soundtrack", true) }, { it.year ?: 9999 }))?.let { m ->
                s = s.copy(artist = artist.ifBlank { m.artist }, album = m.album.replace(Regex("\\s*\\((Original Motion Picture Soundtrack|Original Soundtrack)\\)", RegexOption.IGNORE_CASE), ""), year = m.year,
                    previewUrl = m.preview, artPath = m.artwork?.let { runCatching { saveArt(song.id, it) }.getOrNull() } ?: song.artPath, links = links(title, artist.ifBlank { m.artist }, m.appleUrl))
            }
        db.sounds().update(s)
    }

    data class SongMatch(val title: String, val artist: String, val album: String, val year: Int?, val artwork: String?, val preview: String?, val appleUrl: String?,
                         val score: Float = 0f, val overlap: Float = 0f)

    private suspend fun itunes(term: String): List<SongMatch> = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search?media=music&entity=song&country=IN&limit=4&term=" + java.net.URLEncoder.encode(term, "UTF-8")
        val body = cloud.sendPublic("Apple Music catalogue", term.length.toLong(), "Song lookup: $term", allowed = settings.value.songsOnline) { web().fetch(url, appAgent = true) } ?: return@withContext emptyList()
        (app.echomind.util.Js.parseObject(body)["results"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? kotlinx.serialization.json.JsonObject }.map { r ->
            SongMatch(r.str("trackName").orEmpty(), r.str("artistName").orEmpty(), r.str("collectionName").orEmpty(), r.str("releaseDate")?.take(4)?.toIntOrNull(),
                r.str("artworkUrl100")?.replace("100x100bb", "600x600bb"), r.str("previewUrl"), r.str("trackViewUrl"))
        }.filter { it.title.isNotBlank() }
    }

    private suspend fun lrclib(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val q = norm(title) + " " + artist.split(',', '&').first().trim()
        val url = "https://lrclib.net/api/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
        // LRCLIB asks apps to identify themselves (it refuses browser-like requests).
        val body = cloud.sendPublic("LRCLIB lyrics", q.length.toLong(), "Lyrics for: ${norm(title)}", allowed = settings.value.songsOnline) { web().fetch(url, appAgent = true) } ?: return@withContext null
        val arr = runCatching { app.echomind.util.Js.json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonArray }.getOrNull() ?: return@withContext null
        arr.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.firstNotNullOfOrNull { it.str("plainLyrics")?.takeIf { l -> l.isNotBlank() } }
    }

    private suspend fun saveArt(id: Long, url: String): String? = withContext(Dispatchers.IO) {
        val bytes = cloud.sendPublic("Apple Music catalogue", 0, "Song poster", allowed = settings.value.songsOnline) { web().fetchBytes(url) } ?: return@withContext null
        File(songsDir, "art_$id.jpg").apply { writeBytes(bytes) }.absolutePath
    }

    private fun links(title: String, artist: String, apple: String?): String {
        val q = java.net.URLEncoder.encode("$title ${artist.split(',', '&').first().trim()}".trim(), "UTF-8")
        val m = linkedMapOf("YouTube" to "https://www.youtube.com/results?search_query=$q", "Spotify" to "https://open.spotify.com/search/$q", "JioSaavn" to "https://www.jiosaavn.com/search/song/$q")
        apple?.let { m["Apple Music"] = it }
        return app.echomind.util.Js.json.encodeToString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()), m)
    }

    /** New songs get a name by themselves (once; you can always ask again). */
    suspend fun identifyPending() {
        if (!settings.value.songs || !settings.value.songsAutoIdentify) return
        db.sounds().range(System.currentTimeMillis() - 7 * 86_400_000L, Long.MAX_VALUE)
            .filter { it.kind == "song" && it.title == null && !it.lyrics.isNullOrBlank() && it.lookupAt == null }.take(3)
            .forEach { runCatching { identify(it) } }
    }

    /**
     * Looks through the recordings still kept on the phone for songs that were missed
     * (e.g. found before detection improved). Returns how many songs were added.
     */
    private val _scan = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Progress of a scan or lookup started from the Songs screen (it keeps going if you leave). */
    val scan: kotlinx.coroutines.flow.StateFlow<String?> = _scan

    fun startRescan() {
        if (_scan.value?.startsWith("Scanning") == true) return
        app.echomind.App.graph.scope.launch {
            _scan.value = "Scanning recordings…"
            val n = runCatching { rescan(7) }.getOrDefault(0)
            _scan.value = if (n == 0) "No new songs in the kept recordings." else "Found $n song${if (n == 1) "" else "s"} — naming them…"
            runCatching { identifyPending() }
            if (n > 0) _scan.value = "Found $n song${if (n == 1) "" else "s"}."
        }
    }

    fun startIdentify(song: SoundRow) { app.echomind.App.graph.scope.launch { runCatching { identify(song) } } }

    suspend fun rescan(days: Int = 7): Int {
        if (!ready()) return 0
        var found = 0
        val since = System.currentTimeMillis() - days * 86_400_000L
        for (a in db.audio().overlapping(since, Long.MAX_VALUE).filter { it.keptPath != null && it.source == "mic" }) {
            val kept = File(a.keptPath!!).takeIf { it.exists() } ?: continue
            val tmp = File(context.cacheDir, "rescan_${a.id}.wav")
            try {
                val ms = withContext(Dispatchers.IO) { app.echomind.capture.AudioDecoder.toWav(context, android.net.Uri.fromFile(kept), tmp) }
                if (ms < 5_000) continue
                val n = findSongs(tmp, a, slices(tmp, ms))
                if (n > 0) { found += n; fillLyrics(a) }
            } catch (e: Exception) {
                Log.w(TAG, "rescan ${a.id} failed", e)
            } finally { tmp.delete() }
        }
        return found
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

        /**
         * Where the songs are in a recording, as (start ms, end ms, slices). A song starts at a
         * clearly sung slice and carries on through quieter singing on either side (people sing
         * softly, and phone mics are far away); a slice that is only partly singing adds its half.
         * At least 20 s, or 8 s when the singing is unmistakable. Pure, so it is unit-tested.
         */
        fun songSpans(list: List<Slice>): List<Triple<Long, Long, List<Slice>>> {
            val out = ArrayList<Triple<Long, Long, List<Slice>>>()
            var i = 0
            while (i < list.size) {
                if (!list[i].songish()) { i++; continue }
                var lo = i; var hi = i
                while (lo > 0 && list[lo - 1].songContinues() && (out.isEmpty() || list[lo - 1].startMs >= out.last().second)) lo--
                while (hi < list.lastIndex && (list[hi + 1].songish() || list[hi + 1].songContinues())) hi++
                val run = list.subList(lo, hi + 1)
                val before = list.getOrNull(lo - 1)?.takeIf { it.singing() >= 0.1f || it.p(MUSIC) >= 0.25f }
                val after = list.getOrNull(hi + 1)?.takeIf { it.singing() >= 0.1f || it.p(MUSIC) >= 0.25f }
                val a = before?.let { (it.startMs + it.endMs) / 2 } ?: run.first().startMs
                val b = after?.let { (it.startMs + it.endMs) / 2 } ?: run.last().endMs
                val strong = run.maxOf { it.singing() }
                if (b - a >= 20_000 || (b - a >= 8_000 && strong >= 0.6f)) out.add(Triple(a, b, run))
                i = hi + 1
            }
            return out
        }

        private fun norm(t: String) = t.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]|- .*$|from \".*\""), "").replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

        /** Words every Hindi/English song shares — they say nothing about which song it is (as sound-alike keys). */
        private val FILLER = setOf("men", "me", "he", "ho", "hi", "ko", "ka", "ki", "ke", "se", "tere", "tera", "teri", "mera", "meri", "mere", "tu", "tum", "mein", "man",
            "ye", "yeh", "vo", "voh", "na", "nahi", "nai", "to", "bhi", "jo", "kya", "ek", "or", "the", "and", "you", "yu", "my", "me", "is", "it", "of", "in", "on", "oh", "o",
            "dil", "pyar", "ab", "kar", "kuch", "bas", "hum", "ham", "apne", "apni", "sab", "jab", "tab", "yaha", "vaha", "ja", "re", "de", "le", "la", "ha", "haye", "hae")

        /**
         * How much of what was heard appears in a song's real lyrics (sound-alike words, any script).
         * Shared two-word phrases count most; filler words that every song has don't count.
         */
        fun lyricOverlap(heard: String, lyrics: String): Float {
            val hk = TextNorm.searchable(heard).split(' ').filter { it.isNotEmpty() }
            val lk = TextNorm.searchable(lyrics).split(' ').filter { it.isNotEmpty() }
            val hw = hk.filter { it.length >= 3 && it !in FILLER }.toSet()
            if (hw.isEmpty()) return 0f
            val lw = lk.toSet()
            val words = hw.count { it in lw }.toFloat() / hw.size
            fun bigrams(k: List<String>) = k.zipWithNext().filter { (a, b) -> a !in FILLER || b !in FILLER }.map { "${it.first} ${it.second}" }.toSet()
            val hb = bigrams(hk)
            val pairs = if (hb.isEmpty()) words else hb.count { it in bigrams(lk) }.toFloat() / hb.size
            return 0.4f * words + 0.6f * pairs
        }

        /** Share of a title's words that were sung (titles usually come from the hook). */
        fun titleInLyrics(title: String, heard: String): Float {
            val t = TextNorm.searchable(norm(title)).split(' ').filter { it.length >= 2 }
            if (t.isEmpty()) return 0f
            val h = TextNorm.searchable(heard).split(' ').toSet()
            return t.count { it in h }.toFloat() / t.size
        }

        /**
         * The winner among candidates: the closest real lyrics, only when clearly closer than the
         * next song (a misheard line that fits several songs names none, rather than a wrong one).
         * Without lyrics to compare, a title sung outright with the AI agreeing still counts.
         */
        fun pickSong(c: List<SongMatch>): SongMatch? {
            val byLyrics = c.filter { it.overlap > 0f }.sortedByDescending { it.overlap }
            val first = byLyrics.firstOrNull()
            val second = byLyrics.drop(1).firstOrNull { norm(it.title) != norm(first?.title ?: "") }?.overlap ?: 0f
            if (first != null && first.overlap >= 0.18f && first.overlap >= second * 2f) return first
            return c.filter { it.overlap == 0f && it.score >= 0.5f }.maxByOrNull { it.score }
        }

        /** Long, unusual words of the lyrics (names like "rangrez" make good catalogue searches). */
        fun distinctWords(latin: String): List<String> = latin.lowercase().split(Regex("[^\\p{L}]+"))
            .filter { it.length >= 6 && TextNorm.phonetic(it) !in FILLER }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value * 10 + it.key.length }.map { it.key }.take(2)

        /** Phrases of the lyrics that repeat (the hook, usually the title), most repeated first. */
        fun titlePhrases(latin: String): List<String> {
            val words = latin.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length >= 2 }
            val grams = (2..3).flatMap { n -> words.windowed(n).map { it.joinToString(" ") } }.filter { g -> g.split(' ').any { it.length >= 4 } }
            return grams.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length }).map { it.key }.take(3)
        }

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
