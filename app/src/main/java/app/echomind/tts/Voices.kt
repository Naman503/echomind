package app.echomind.tts

import app.echomind.models.ModelManager
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/** A voice: which model pack, which speaker in it, which language it reads. */
data class VoiceSpec(val id: String, val label: String, val pack: String, val engine: String, val sid: Int, val lang: String)

object VoiceCatalog {
    // Kokoro multi-lang v1.0 speaker ids (checked against the pack). Hindi voices exist only in v1.0.
    val all = listOf(
        VoiceSpec("kokoro-heart", "Heart (warm, US)", "kokoro-multi", "kokoro", 3, "en"),
        VoiceSpec("kokoro-bella", "Bella (bright, US)", "kokoro-multi", "kokoro", 2, "en"),
        VoiceSpec("kokoro-michael", "Michael (calm, US)", "kokoro-multi", "kokoro", 16, "en"),
        VoiceSpec("kokoro-emma", "Emma (British)", "kokoro-multi", "kokoro", 21, "en"),
        VoiceSpec("kokoro-hf-alpha", "Alpha (Hindi, female)", "kokoro-multi", "kokoro", 31, "hi"),
        VoiceSpec("kokoro-hf-beta", "Beta (Hindi, female)", "kokoro-multi", "kokoro", 32, "hi"),
        VoiceSpec("kokoro-hm-omega", "Omega (Hindi, male)", "kokoro-multi", "kokoro", 33, "hi"),
        VoiceSpec("kokoro-hm-psi", "Psi (Hindi, male)", "kokoro-multi", "kokoro", 34, "hi"),
        VoiceSpec("piper-lessac", "Lessac (US, light)", "piper-en-lessac", "piper", 0, "en"),
        VoiceSpec("piper-priyamvada", "Priyamvada (Hindi, light)", "piper-hi-priyamvada", "piper", 0, "hi"),
        VoiceSpec("piper-rohan", "Rohan (Hindi male, light)", "piper-hi-rohan", "piper", 0, "hi"),
    )
    fun get(id: String) = all.firstOrNull { it.id == id }
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
        val espeak = dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }?.absolutePath.orEmpty()
        val model = if (pack.startsWith("kokoro")) {
            OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = file { it.name.endsWith(".onnx") },
                    voices = file { it.name == "voices.bin" },
                    tokens = file { it.name == "tokens.txt" },
                    dataDir = espeak,
                    lexicon = files.filter { it.name.startsWith("lexicon") && it.name.endsWith(".txt") }.joinToString(",") { it.absolutePath },
                ),
                numThreads = threads(),
            )
        } else {
            OfflineTtsModelConfig(
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
