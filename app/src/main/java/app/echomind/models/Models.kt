package app.echomind.models

import android.app.DownloadManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * Installs models: a resumable parallel download ([Downloader]) run as a
 * WorkManager job, so it keeps going when the app is closed and picks up
 * where it stopped after a restart, then unpacks archives.
 */
class ModelManager(private val context: Context, private val scope: CoroutineScope) {
    data class State(
        val installed: Boolean = false,
        val downloading: Boolean = false,
        val extracting: Boolean = false,
        val progress: Float = 0f,
        val error: String? = null,
        /** Bytes per second while downloading. */
        val speed: Long = 0,
    )

    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    private val _states = MutableStateFlow(ModelCatalog.all.associate { spec ->
        val active = prefs.getBoolean("dl:${spec.id}", false)
        val installed = isInstalled(spec)
        spec.id to State(installed = installed, downloading = active && !installed, progress = if (active) Downloader.savedProgress(part(spec)) ?: 0f else 0f)
    })
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { migrateFromDownloadManager() }
    }

    /** Earlier builds used DownloadManager (one slow connection): cancel those and restart with the fast downloader. */
    private fun migrateFromDownloadManager() {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        for (spec in ModelCatalog.all) {
            val old = prefs.getLong(spec.id, -1L)
            if (old < 0) continue
            runCatching { dm.remove(old) }
            prefs.edit().remove(spec.id).apply()
            File(root, "${spec.fileName}.part").delete()
            if (!isInstalled(spec)) download(spec.id)
        }
    }

    fun state(id: String) = _states.value[id] ?: State()
    fun isInstalled(id: String) = ModelCatalog.get(id)?.let { isInstalled(it) } ?: false

    private fun target(spec: ModelSpec) = if (spec.archive) File(root, spec.id) else File(root, spec.fileName)
    internal fun part(spec: ModelSpec) = File(root, "${spec.fileName}.download")
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
        if (isInstalled(spec)) return
        prefs.edit().putBoolean("dl:$id", true).apply()
        set(id) { it.copy(downloading = true, error = null, progress = Downloader.savedProgress(part(spec)) ?: 0f) }
        val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf("id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("model-$id", ExistingWorkPolicy.KEEP, req)
    }

    fun cancel(id: String) {
        val spec = ModelCatalog.get(id) ?: return
        WorkManager.getInstance(context).cancelUniqueWork("model-$id")
        prefs.edit().remove("dl:$id").apply()
        Downloader.discard(part(spec))
        set(id) { State(installed = isInstalled(spec)) }
    }

    fun delete(id: String) {
        val spec = ModelCatalog.get(id) ?: return
        cancel(id)
        target(spec).deleteRecursively()
        set(id) { State() }
    }

    internal fun set(id: String, change: (State) -> State) = _states.update { it + (id to change(it[id] ?: State())) }

    /** Runs inside the worker: download, verify, install. */
    internal suspend fun install(spec: ModelSpec) {
        val tmp = part(spec)
        var lastBytes = 0L
        var lastAt = System.currentTimeMillis()
        var speed = 0L
        Downloader.fetch(spec.url, tmp) { done, total ->
            val now = System.currentTimeMillis()
            if (now - lastAt >= 1_000) {
                speed = (speed * 0.6 + (done - lastBytes) * 1000.0 / (now - lastAt) * 0.4).toLong()
                lastBytes = done; lastAt = now
            }
            set(spec.id) { it.copy(downloading = true, progress = if (total > 0) (done.toDouble() / total).toFloat() else 0f, speed = speed) }
        }
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
                if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            }
            prefs.edit().remove("dl:${spec.id}").apply()
            set(spec.id) { State(installed = isInstalled(spec)) }
        } catch (e: Exception) {
            Log.w("EchoModels", "install ${spec.id} failed", e)
            Downloader.discard(tmp)
            throw e
        }
    }

    internal fun markInstalled(spec: ModelSpec) {
        prefs.edit().remove("dl:${spec.id}").apply()
        Downloader.discard(part(spec))
        set(spec.id) { State(installed = true) }
    }

    /** Re-checks the disk, e.g. after a model file was copied in by hand. */
    fun refresh() {
        for (spec in ModelCatalog.all) if (isInstalled(spec) && state(spec.id).installed.not()) markInstalled(spec)
    }

    internal fun failed(spec: ModelSpec, message: String, final: Boolean) {
        if (final) prefs.edit().remove("dl:${spec.id}").apply()
        set(spec.id) { it.copy(downloading = !final, extracting = false, error = message) }
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

/** Downloads one model as a foreground job with its own progress notification. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val spec = ModelCatalog.get(id) ?: return Result.failure()
        val models = app.echomind.App.graph.models
        // Already there (copied in over USB, or finished before a restart): just record it.
        if (models.isInstalled(id)) { models.markInstalled(spec); return Result.success() }
        runCatching { setForeground(info(spec, 0)) }
        val progressJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            models.states.collect { m ->
                val s = m[id] ?: return@collect
                val pct = (s.progress * 100).toInt()
                if (s.downloading && pct != lastPct) runCatching { setForeground(info(spec, pct)) }
            }
        }
        return try {
            models.install(spec)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val final = runAttemptCount >= 6
            models.failed(spec, if (final) "Download failed: ${e.message}. Tap Get to try again." else "Connection problem — retrying…", final)
            if (final) Result.failure() else Result.retry()
        } finally {
            progressJob.cancel()
        }
    }

    private var lastPct = -1

    private fun info(spec: ModelSpec, pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        if (nm.getNotificationChannel("downloads") == null) {
            nm.createNotificationChannel(android.app.NotificationChannel("downloads", "Model downloads", android.app.NotificationManager.IMPORTANCE_LOW))
        }
        lastPct = pct
        val n = androidx.core.app.NotificationCompat.Builder(applicationContext, "downloads")
            .setSmallIcon(app.echomind.R.drawable.ic_stat_idle)
            .setContentTitle("Downloading ${spec.label}")
            .setContentText("$pct% · on-device model, downloaded once")
            .setProgress(100, pct, pct <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(5000 + Math.floorMod(spec.id.hashCode(), 1000), n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
