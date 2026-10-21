package app.echomind.models

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/** Something the app can download once and then run without the network. */
data class ModelSpec(
    val id: String,
    val label: String,
    val kind: Kind,
    val url: String,
    val bytes: Long,
    val detail: String,
    /** tar.bz2 packs are extracted into a folder; single files are used as they are. */
    val archive: Boolean = false,
    val recommended: Boolean = false,
) {
    enum class Kind { LLM, EMBEDDING, SPEECH, VAD, SPEAKER }
    val fileName get() = url.substringAfterLast('/')
}

object ModelCatalog {
    private const val HF = "https://huggingface.co"
    private const val SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    val all = listOf(
        ModelSpec("gemma-4-e2b", "Gemma 4 E2B", ModelSpec.Kind.LLM,
            "$HF/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", 2_588_147_712,
            "Best balance for this phone: 140+ languages incl. Hindi, good at notes and tasks.", recommended = true),
        ModelSpec("qwen3.5-2b", "Qwen 3.5 2B", ModelSpec.Kind.LLM,
            "$HF/litert-community/Qwen3.5-2B/resolve/main/Qwen3.5-2B_int8.litertlm", 2_119_744_416,
            "Alternative with strong multilingual reasoning."),
        ModelSpec("qwen3.5-0.8b", "Qwen 3.5 0.8B (fast)", ModelSpec.Kind.LLM,
            "$HF/litert-community/Qwen3.5-0.8B/resolve/main/Qwen3.5-0.8B_int8.litertlm", 964_566_992,
            "Small and quick; simpler notes. For phones that run hot."),
        ModelSpec("gemma-4-e4b", "Gemma 4 E4B (best, slow)", ModelSpec.Kind.LLM,
            "$HF/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm", 3_659_530_240,
            "Highest quality on device; best used overnight while charging."),
        ModelSpec("embeddinggemma-2", "EmbeddingGemma 2 (search)", ModelSpec.Kind.EMBEDDING,
            "$HF/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm", 164_626_432,
            "Finds things by meaning, not just words, in any language.", recommended = true),
        ModelSpec("whisper-small", "Whisper small", ModelSpec.Kind.SPEECH,
            "$SHERPA/asr-models/sherpa-onnx-whisper-small.tar.bz2", 639_387_718,
            "99 languages incl. Hindi; handles mixed speech.", archive = true),
        ModelSpec("whisper-turbo", "Whisper large-v3 turbo", ModelSpec.Kind.SPEECH,
            "$SHERPA/asr-models/sherpa-onnx-whisper-turbo.tar.bz2", 563_790_207,
            "Most accurate Whisper; slow on this phone — best for calls overnight.", archive = true),
        ModelSpec("parakeet-v2", "Parakeet TDT 0.6B (English)", ModelSpec.Kind.SPEECH,
            "$SHERPA/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2", 482_468_385,
            "Very fast and accurate English with word timings.", archive = true),
        ModelSpec("omnilingual-300m", "Omnilingual 300M (all Indian languages)", ModelSpec.Kind.SPEECH,
            "$SHERPA/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2", 292_313_120,
            "Meta's 1,600-language model: Hindi, Marathi, Tamil, Bengali and more.", archive = true),
        ModelSpec("silero-vad", "Silero voice detector", ModelSpec.Kind.VAD,
            "$SHERPA/asr-models/silero_vad.onnx", 643_854,
            "Precise speech detection, so only speech is transcribed.", recommended = true),
        ModelSpec("voice-id", "Voice ID (who is speaking)", ModelSpec.Kind.SPEAKER,
            "$SHERPA/speaker-recongition-models/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx", 26_485_263,
            "Tells your voice apart from others, so notes know what you said.", recommended = true),
    )

    fun get(id: String) = all.firstOrNull { it.id == id }
}

/**
 * Downloads with Android's DownloadManager (resumable, survives the app being
 * killed, shows its own progress), then unpacks archives.
 */
class ModelManager(private val context: Context, private val scope: CoroutineScope) {
    data class State(
        val installed: Boolean = false,
        val downloading: Boolean = false,
        val extracting: Boolean = false,
        val progress: Float = 0f,
        val error: String? = null,
    )

    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    private val _states = MutableStateFlow(ModelCatalog.all.associate { it.id to State(installed = isInstalled(it)) })
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()
    private var poller: Job? = null

    init {
        if (ModelCatalog.all.any { prefs.contains(it.id) }) startPolling()
    }

    fun state(id: String) = _states.value[id] ?: State()
    fun isInstalled(id: String) = ModelCatalog.get(id)?.let { isInstalled(it) } ?: false

    private fun target(spec: ModelSpec) = if (spec.archive) File(root, spec.id) else File(root, spec.fileName)
    private fun isInstalled(spec: ModelSpec): Boolean {
        val t = target(spec)
        return if (spec.archive) File(t, ".complete").exists() else t.exists() && t.length() > 100_000
    }

    /** Path to a single-file model, or the folder of an unpacked pack; null if not installed. */
    fun path(id: String): File? {
        val spec = ModelCatalog.get(id) ?: return null
        return if (isInstalled(spec)) target(spec) else null
    }

    fun download(id: String) {
        val spec = ModelCatalog.get(id) ?: return
        if (isInstalled(spec) || prefs.contains(id)) return
        val tmp = File(root, "${spec.fileName}.part")
        tmp.delete()
        val request = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle("EchoMind · ${spec.label}")
            .setDescription("On-device model, downloaded once")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setDestinationUri(Uri.fromFile(tmp))
        val downloadId = dm.enqueue(request)
        prefs.edit().putLong(id, downloadId).apply()
        set(id) { it.copy(downloading = true, progress = 0f, error = null) }
        startPolling()
    }

    fun cancel(id: String) {
        val d = prefs.getLong(id, -1)
        if (d >= 0) dm.remove(d)
        prefs.edit().remove(id).apply()
        set(id) { State(installed = isInstalled(id)) }
    }

    fun delete(id: String) {
        val spec = ModelCatalog.get(id) ?: return
        cancel(id)
        target(spec).deleteRecursively()
        set(id) { State() }
    }

    private fun set(id: String, change: (State) -> State) = _states.update { it + (id to change(it[id] ?: State())) }

    private fun startPolling() {
        if (poller?.isActive == true) return
        poller = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pending = ModelCatalog.all.filter { prefs.contains(it.id) }
                if (pending.isEmpty()) break
                for (spec in pending) poll(spec)
                delay(1_000)
            }
        }
    }

    private fun poll(spec: ModelSpec) {
        val downloadId = prefs.getLong(spec.id, -1)
        val c = dm.query(DownloadManager.Query().setFilterById(downloadId)) ?: return
        c.use {
            if (!it.moveToFirst()) {
                prefs.edit().remove(spec.id).apply()
                set(spec.id) { s -> s.copy(downloading = false, error = "Download was cancelled") }
                return
            }
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { t -> t > 0 } ?: spec.bytes
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().remove(spec.id).apply()
                    finish(spec)
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove(spec.id).apply()
                    val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    set(spec.id) { s -> s.copy(downloading = false, error = "Download failed ($reason). Check space and connection.") }
                }
                else -> set(spec.id) { s -> s.copy(downloading = true, progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
            }
        }
    }

    private fun finish(spec: ModelSpec) {
        val tmp = File(root, "${spec.fileName}.part")
        try {
            if (spec.archive) {
                set(spec.id) { it.copy(downloading = false, extracting = true, progress = 1f) }
                val dir = target(spec)
                dir.deleteRecursively()
                dir.mkdirs()
                untarBz2(tmp, dir)
                File(dir, ".complete").writeText(System.currentTimeMillis().toString())
                tmp.delete()
            } else {
                val dest = target(spec)
                dest.delete()
                if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true).also { tmp.delete() }
            }
            set(spec.id) { State(installed = isInstalled(spec)) }
        } catch (e: Exception) {
            Log.w("EchoModels", "install ${spec.id} failed", e)
            tmp.delete()
            set(spec.id) { State(error = "Could not unpack: ${e.message}") }
        }
    }

    /** Extracts files flat into [dir] (packs have one top-level folder; we drop it). */
    private fun untarBz2(archive: File, dir: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 16))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfter('/', entry.name)
                val out = File(dir, name)
                if (!out.canonicalPath.startsWith(dir.canonicalPath)) continue // no path tricks
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it, 1 shl 16) }
            }
        }
    }

    /** Finds a file in an unpacked pack by a name test, preferring int8 builds. */
    fun find(id: String, test: (String) -> Boolean): File? {
        val dir = path(id) ?: return null
        val matches = dir.walkTopDown().filter { it.isFile && test(it.name) }.toList()
        return matches.firstOrNull { "int8" in it.name } ?: matches.firstOrNull()
    }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
