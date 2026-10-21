package app.echomind.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import app.echomind.models.ModelManager
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * A voice: which model pack and speaker in it, which languages it reads, and
 * what it sounds like. System voices (Google's, Samsung's, any installed
 * text-to-speech app) have ids "sys:<engine>:<voice>" and no pack.
 */
data class VoiceSpec(
    val id: String,
    val label: String,
    val pack: String,
    /** kokoro | supertonic | kitten | piper | system */
    val engine: String,
    val sid: Int,
    val langs: Set<String>,
    /** f | m | "" */
    val gender: String,
    val accent: String,
    /** 1–5: how natural it sounds, from published grades and listening. */
    val stars: Int,
    val note: String = "",
    /** System voices that need internet (the engine sends the text to its servers). */
    val online: Boolean = false,
) {
    val lang get() = if ("en" in langs) "en" else langs.first()
    fun reads(lang: String) = lang in langs
}

object VoiceCatalog {
    private const val NC = "Personal, non-commercial use"

    private fun kokoro(name: String, label: String, sid: Int, lang: String, g: String, accent: String, stars: Int) =
        VoiceSpec("kokoro-$name", label, "kokoro-multi", "kokoro", sid, setOf(lang), g, accent, stars, "Apache 2.0")

    private fun piper(id: String, label: String, pack: String, g: String, accent: String, stars: Int, sid: Int = 0, lang: String = "en", note: String = "") =
        VoiceSpec(id, label, pack, "piper", sid, setOf(lang), g, accent, stars, note)

    /** Kokoro multi-lang v1.0 speaker ids (checked against the pack); Hindi voices exist only in v1.0. Grades from Kokoro's VOICES.md. */
    private val kokoroVoices = listOf(
        kokoro("heart", "Heart", 3, "en", "f", "US", 5), kokoro("bella", "Bella", 2, "en", "f", "US", 5),
        kokoro("nicole", "Nicole (soft)", 6, "en", "f", "US", 4), kokoro("aoede", "Aoede", 1, "en", "f", "US", 3),
        kokoro("kore", "Kore", 5, "en", "f", "US", 3), kokoro("sarah", "Sarah", 9, "en", "f", "US", 3),
        kokoro("nova", "Nova", 7, "en", "f", "US", 3), kokoro("alloy", "Alloy", 0, "en", "f", "US", 3),
        kokoro("sky", "Sky", 10, "en", "f", "US", 2), kokoro("jessica", "Jessica", 4, "en", "f", "US", 2), kokoro("river", "River", 8, "en", "f", "US", 2),
        kokoro("michael", "Michael", 16, "en", "m", "US", 4), kokoro("fenrir", "Fenrir", 14, "en", "m", "US", 4), kokoro("puck", "Puck", 18, "en", "m", "US", 4),
        kokoro("echo", "Echo", 12, "en", "m", "US", 2), kokoro("eric", "Eric", 13, "en", "m", "US", 2), kokoro("liam", "Liam", 15, "en", "m", "US", 2),
        kokoro("onyx", "Onyx (deep)", 17, "en", "m", "US", 2), kokoro("adam", "Adam", 11, "en", "m", "US", 1), kokoro("santa", "Santa", 19, "en", "m", "US", 1),
        kokoro("emma", "Emma", 21, "en", "f", "British", 4), kokoro("isabella", "Isabella", 22, "en", "f", "British", 3),
        kokoro("alice", "Alice", 20, "en", "f", "British", 2), kokoro("lily", "Lily", 23, "en", "f", "British", 2),
        kokoro("fable", "Fable", 25, "en", "m", "British", 3), kokoro("george", "George", 26, "en", "m", "British", 3),
        kokoro("lewis", "Lewis", 27, "en", "m", "British", 2), kokoro("daniel", "Daniel", 24, "en", "m", "British", 2),
        kokoro("hf-alpha", "Alpha", 31, "hi", "f", "Hindi", 3), kokoro("hf-beta", "Beta", 32, "hi", "f", "Hindi", 3),
        kokoro("hm-omega", "Omega", 33, "hi", "m", "Hindi", 3), kokoro("hm-psi", "Psi", 34, "hi", "m", "Hindi", 3),
    )

    /** Supertonic 3: voice.bin rows are the sorted style files F1–F5, M1–M5; every voice reads English and Hindi. */
    private val supertonicVoices = (1..5).map { VoiceSpec("supertonic-f$it", "Supertonic F$it", "supertonic-3", "supertonic", it - 1, setOf("en", "hi"), "f", "Multilingual", 5, "OpenRAIL-M") } +
        (1..5).map { VoiceSpec("supertonic-m$it", "Supertonic M$it", "supertonic-3", "supertonic", it + 4, setOf("en", "hi"), "m", "Multilingual", 5, "OpenRAIL-M") }

    private val kittenVoices = (0..7).map { VoiceSpec("kitten-$it", "Kitten ${it + 1}", "kitten-nano", "kitten", it, setOf("en"), "", "US", 3, "Apache 2.0") }

    private val piperVoices = listOf(
        piper("piper-lessac", "Lessac", "piper-en-lessac", "f", "US", 3, note = "Blizzard licence: $NC"),
        piper("piper-lessac-high", "Lessac HQ", "piper-en-lessac-high", "f", "US", 4, note = "Blizzard licence: $NC"),
        piper("piper-amy", "Amy", "piper-en-amy", "f", "US", 3),
        piper("piper-kristin", "Kristin", "piper-en-kristin", "f", "US", 3),
        piper("piper-hfc-female", "HFC female", "piper-en-hfc-female", "f", "US", 3),
        piper("piper-ljspeech", "LJ (audiobook)", "piper-en-ljspeech", "f", "US", 4),
        piper("piper-kathleen", "Kathleen", "piper-en-kathleen", "f", "US", 2),
        piper("piper-ryan", "Ryan HQ", "piper-en-ryan", "m", "US", 4),
        piper("piper-joe", "Joe", "piper-en-joe", "m", "US", 3),
        piper("piper-john", "John", "piper-en-john", "m", "US", 3),
        piper("piper-bryce", "Bryce", "piper-en-bryce", "m", "US", 3),
        piper("piper-norman", "Norman", "piper-en-norman", "m", "US", 3),
        piper("piper-hfc-male", "HFC male", "piper-en-hfc-male", "m", "US", 3),
        piper("piper-sam", "Sam", "piper-en-sam", "m", "US", 3),
        piper("piper-kusal", "Kusal", "piper-en-kusal", "m", "South Asian", 3),
        piper("piper-reza", "Reza", "piper-en-reza", "m", "English", 3),
        // L2-ARCTIC speaker ids from the pack's speaker_id_map: SVBI, TNI, ASI, RRBI are the Hindi-speaking readers.
        piper("piper-l2-svbi", "SVBI", "piper-en-l2arctic", "f", "Indian", 3, sid = 2, note = "CC BY-NC 4.0: $NC"),
        piper("piper-l2-tni", "TNI", "piper-en-l2arctic", "f", "Indian", 3, sid = 9, note = "CC BY-NC 4.0: $NC"),
        piper("piper-l2-asi", "ASI", "piper-en-l2arctic", "m", "Indian", 3, sid = 10, note = "CC BY-NC 4.0: $NC"),
        piper("piper-l2-rrbi", "RRBI", "piper-en-l2arctic", "m", "Indian", 3, sid = 19, note = "CC BY-NC 4.0: $NC"),
        // CMU ARCTIC: ksp, aup, gka, slp and axb are Indian-English readers.
        piper("piper-arctic-ksp", "KSP", "piper-en-arctic", "m", "Indian", 3, sid = 3),
        piper("piper-arctic-aup", "AUP", "piper-en-arctic", "m", "Indian", 2, sid = 13),
        piper("piper-arctic-gka", "GKA", "piper-en-arctic", "m", "Indian", 2, sid = 17),
        piper("piper-arctic-slp", "SLP", "piper-en-arctic", "f", "Indian", 2, sid = 12),
        piper("piper-arctic-axb", "AXB", "piper-en-arctic", "f", "Indian", 2, sid = 15),
        piper("piper-arctic-slt", "SLT", "piper-en-arctic", "f", "US", 3, sid = 2),
        piper("piper-arctic-rms", "RMS", "piper-en-arctic", "m", "US", 3, sid = 1),
        piper("piper-arctic-awb", "AWB", "piper-en-arctic", "m", "Scottish", 2, sid = 0),
        piper("piper-cori", "Cori HQ", "piper-gb-cori", "f", "British", 4),
        piper("piper-jenny", "Jenny", "piper-gb-jenny", "f", "British", 4),
        piper("piper-alba", "Alba", "piper-gb-alba", "f", "Scottish", 3),
        piper("piper-southern-female", "Southern female", "piper-gb-southern-female", "f", "British", 3),
        piper("piper-alan", "Alan", "piper-gb-alan", "m", "British", 3),
        piper("piper-northern-male", "Northern male", "piper-gb-northern-male", "m", "British", 3),
        piper("piper-southern-male", "Southern male", "piper-gb-southern-male", "m", "British", 3),
        piper("piper-semaine-prudence", "Prudence", "piper-gb-semaine", "f", "British", 3, sid = 0),
        piper("piper-semaine-poppy", "Poppy", "piper-gb-semaine", "f", "British", 3, sid = 3),
        piper("piper-semaine-spike", "Spike", "piper-gb-semaine", "m", "British", 3, sid = 1),
        piper("piper-semaine-obadiah", "Obadiah", "piper-gb-semaine", "m", "British", 3, sid = 2),
        piper("piper-priyamvada", "Priyamvada", "piper-hi-priyamvada", "f", "Hindi", 3, lang = "hi", note = "CC BY-NC-SA: $NC"),
        piper("piper-rohan", "Rohan", "piper-hi-rohan", "m", "Hindi", 3, lang = "hi", note = "CC BY-NC-SA: $NC"),
        piper("piper-pratham", "Pratham", "piper-hi-pratham", "m", "Hindi", 3, lang = "hi", note = "CC BY-NC-SA: $NC"),
    )

    val open: List<VoiceSpec> = supertonicVoices + kokoroVoices + kittenVoices + piperVoices

    /** Voices of the phone's text-to-speech apps, filled in by [SystemVoices.load]. */
    @Volatile var system: List<VoiceSpec> = emptyList()

    val all get() = open + system

    fun get(id: String): VoiceSpec? = open.firstOrNull { it.id == id } ?: system.firstOrNull { it.id == id } ?: systemPlaceholder(id)

    /** A system voice chosen earlier but not listed yet this run (the engine loads lazily). */
    private fun systemPlaceholder(id: String): VoiceSpec? {
        if (!id.startsWith("sys:")) return null
        val (_, engine, name) = id.split(':', limit = 3).let { if (it.size == 3) it else return null }
        return VoiceSpec(id, name, "", "system", 0, setOf(if (name.startsWith("hi")) "hi" else "en"), "", engine, 3)
    }

    fun engineName(engine: String) = when (engine) {
        "supertonic" -> "Supertonic"; "kokoro" -> "Kokoro"; "kitten" -> "Kitten"; "piper" -> "Piper"; else -> "Phone"
    }
}

/**
 * Lists the voices of every text-to-speech app on the phone: Google's speech
 * services (natural offline voices for English India, Hindi and more),
 * Samsung's, and any third-party engine the user installs. Network-only
 * voices are marked, since those engines send the text to their servers.
 */
object SystemVoices {
    data class Engine(val pkg: String, val label: String)

    suspend fun load(context: Context): List<VoiceSpec> = withContext(Dispatchers.Main) {
        val probe = open(context, null) ?: return@withContext emptyList()
        val engines = probe.engines.map { Engine(it.name, it.label) }
        probe.shutdown()
        val out = ArrayList<VoiceSpec>()
        for (e in engines) {
            val tts = open(context, e.pkg) ?: continue
            runCatching {
                tts.voices.orEmpty().filter { v ->
                    val l = v.locale.language
                    (l == "en" || l == "hi") && !v.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                }.sortedWith(compareBy({ it.locale.language != "en" }, { it.locale.country != "IN" }, { -it.quality })).forEach { v ->
                    val country = v.locale.displayCountry.ifBlank { v.locale.country }
                    out.add(VoiceSpec(
                        id = "sys:${e.pkg}:${v.name}",
                        label = "${v.locale.displayLanguage}${if (country.isNotBlank()) " ($country)" else ""} · ${v.name.substringAfterLast('-').substringBefore('#')}",
                        pack = "", engine = "system", sid = 0, langs = setOf(v.locale.language),
                        gender = when { "female" in v.name.lowercase() -> "f"; "male" in v.name.lowercase() -> "m"; else -> "" },
                        accent = e.label, stars = if (v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH) 4 else if (v.quality >= android.speech.tts.Voice.QUALITY_HIGH) 3 else 2,
                        note = if (v.isNetworkConnectionRequired) "Online: ${e.label} sends the text to its servers" else "Offline, ${e.label}",
                        online = v.isNetworkConnectionRequired,
                    ))
                }
            }
            tts.shutdown()
        }
        VoiceCatalog.system = out
        out
    }

    private suspend fun open(context: Context, pkg: String?): TextToSpeech? {
        val ready = CompletableDeferred<Boolean>()
        val tts = if (pkg == null) TextToSpeech(context) { ready.complete(it == TextToSpeech.SUCCESS) } else TextToSpeech(context, { ready.complete(it == TextToSpeech.SUCCESS) }, pkg)
        val ok = withTimeoutOrNull(8_000) { ready.await() } == true
        if (!ok) { tts.shutdown(); return null }
        return tts
    }
}

/**
 * Loads sherpa-onnx TTS models on demand (one per pack) and frees them after
 * use. Files are found by pattern, so pack layouts can change between
 * releases without breaking the app.
 */
class TtsModels(private val models: ModelManager, private val threads: () -> Int) {
    private val loaded = HashMap<String, OfflineTts>()

    @Synchronized
    fun get(pack: String): OfflineTts? {
        loaded[pack]?.let { return it }
        val dir = models.path(pack) ?: return null
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        fun file(test: (File) -> Boolean) = files.filter(test).let { l -> l.firstOrNull { "int8" in it.name } ?: l.firstOrNull() }?.absolutePath.orEmpty()
        fun named(prefix: String) = file { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
        val espeak = dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }?.absolutePath.orEmpty()
        val model = when {
            pack.startsWith("kokoro") -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = file { it.name.endsWith(".onnx") },
                    voices = file { it.name == "voices.bin" },
                    tokens = file { it.name == "tokens.txt" },
                    dataDir = espeak,
                    lexicon = files.filter { it.name.startsWith("lexicon") && it.name.endsWith(".txt") }.joinToString(",") { it.absolutePath },
                ),
                numThreads = threads(),
            )
            pack.startsWith("supertonic") -> OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = named("duration_predictor"),
                    textEncoder = named("text_encoder"),
                    vectorEstimator = named("vector_estimator"),
                    vocoder = named("vocoder"),
                    ttsJson = file { it.name == "tts.json" },
                    unicodeIndexer = file { it.name == "unicode_indexer.bin" },
                    voiceStyle = file { it.name == "voice.bin" },
                ),
                numThreads = threads(),
            )
            pack.startsWith("kitten") -> OfflineTtsModelConfig(
                kitten = OfflineTtsKittenModelConfig(
                    model = file { it.name.endsWith(".onnx") },
                    voices = file { it.name == "voices.bin" },
                    tokens = file { it.name == "tokens.txt" },
                    dataDir = espeak,
                ),
                numThreads = threads(),
            )
            else -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = file { it.name.endsWith(".onnx") },
                    tokens = file { it.name == "tokens.txt" },
                    dataDir = espeak,
                ),
                numThreads = threads(),
            )
        }
        return runCatching { OfflineTts(config = OfflineTtsConfig(model = model)) }.getOrNull()?.also { loaded[pack] = it }
    }

    @Synchronized
    fun release() {
        loaded.values.forEach { runCatching { it.free() } }
        loaded.clear()
    }
}
