package app.echomind.stt

import kotlinx.coroutines.sync.withLock
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionPart
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import app.echomind.capture.Wav
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Android's own on-device recogniser (Google Speech Services — the engine
 * behind voice typing). It is a streaming model built for phones: far lighter
 * than running Whisper on the CPU, and good with Indian English and Hindi.
 *
 * It never opens the microphone. `EXTRA_AUDIO_SOURCE` reads PCM from a pipe we
 * fill from our own recording, so recording stays independent of
 * transcription and a failed piece can simply be retried. Proven on the
 * iQOO Z7 Pro: feeding faster than ~3× real time makes the service drop words.
 */
class AndroidSpeech(
    private val context: Context,
    /** Use Google's server recogniser (what Gboard uses online) instead of the offline one. */
    private val online: Boolean = false,
    private val speed: () -> Double = { SPEED },
    private val singleLanguage: () -> Boolean = { false },
) : SpeechEngine {
    override val id = if (online) "google_online" else "android"
    override val maxPieceMs = 60_000L

    private val main = Handler(Looper.getMainLooper())

    fun available(): Boolean = if (online) SpeechRecognizer.isRecognitionAvailable(context) else SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun create(): SpeechRecognizer = if (online) SpeechRecognizer.createSpeechRecognizer(context) else SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

    private fun intentFor(languages: List<String>, hints: List<String> = emptyList()): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languages.firstOrNull() ?: "en-IN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !online)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Names it would otherwise mishear ("Priya", "Eazeebox").
            if (hints.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints.take(100)))
            if (Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                // Switching costs accuracy; with one language the model never second-guesses itself.
                if (languages.size > 1 && !singleLanguage()) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ArrayList(languages))
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                    putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, ArrayList(languages))
                }
            }
        }

    data class Support(val available: Boolean, val installed: List<String> = emptyList(), val pending: List<String> = emptyList(), val supported: List<String> = emptyList())

    /** Which languages are installed, downloading, or downloadable on this phone. */
    private val supportLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var lastSupport: Triple<List<String>, Long, Support>? = null

    /**
     * Which languages the phone can recognise offline. Asked at most every 10 minutes (screens ask on every
     * resume), one question at a time, and the recogniser made for it is always closed — even when the
     * answer never comes or the screen goes away. Open recognisers left behind can jam the speech service.
     */
    suspend fun support(languages: List<String>, force: Boolean = false): Support = supportLock.withLock {
        lastSupport?.let { (langs, at, sup) -> if (!force && langs == languages && System.currentTimeMillis() - at < 10 * 60_000L) return@withLock sup }
        val sup = kotlinx.coroutines.withTimeoutOrNull(8_000) { askSupport(languages) } ?: return@withLock lastSupport?.third ?: Support(available())
        lastSupport = Triple(languages, System.currentTimeMillis(), sup)
        sup
    }

    private suspend fun askSupport(languages: List<String>): Support = suspendCancellableCoroutine { cont ->
        if (!available()) {
            cont.resume(Support(false)); return@suspendCancellableCoroutine
        }
        var recognizer: SpeechRecognizer? = null
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun close() { if (closed.compareAndSet(false, true)) main.post { runCatching { recognizer?.destroy() } } }
        cont.invokeOnCancellation { close() }
        main.post {
            if (!cont.isActive) return@post
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
            r.checkRecognitionSupport(
                intentFor(languages),
                Executors.newSingleThreadExecutor(),
                object : RecognitionSupportCallback {
                    override fun onSupportResult(s: RecognitionSupport) {
                        close()
                        installed = s.installedOnDeviceLanguages.toList()
                        if (cont.isActive) cont.resume(Support(true, s.installedOnDeviceLanguages, s.pendingOnDeviceLanguages, s.supportedOnDeviceLanguages))
                    }

                    override fun onError(error: Int) {
                        close()
                        if (cont.isActive) cont.resume(Support(true))
                    }
                },
            )
        }
    }

    sealed interface PackEvent {
        data class Progress(val language: String, val percent: Int) : PackEvent
        data class Done(val language: String) : PackEvent
        data class Scheduled(val language: String) : PackEvent
        data class Failed(val language: String, val reason: String) : PackEvent
    }

    /**
     * Downloads offline packs one language at a time, reporting progress. The
     * recogniser is kept alive until the service answers — destroying it early
     * (as a fire-and-forget call does) silently cancels the request.
     */
    suspend fun downloadPacks(languages: List<String>, onEvent: (PackEvent) -> Unit) {
        if (!available()) { languages.forEach { onEvent(PackEvent.Failed(it, "on-device recognition unavailable")) }; return }
        for (lang in languages) {
            val outcome = suspendCancellableCoroutine<PackEvent> { cont ->
                main.post {
                    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    fun finish(e: PackEvent) {
                        main.post { runCatching { recognizer.destroy() } }
                        if (cont.isActive) cont.resume(e)
                    }
                    try {
                        recognizer.triggerModelDownload(intentFor(listOf(lang)), Executors.newSingleThreadExecutor(), object : android.speech.ModelDownloadListener {
                            override fun onProgress(completedPercent: Int) = onEvent(PackEvent.Progress(lang, completedPercent))
                            override fun onSuccess() = finish(PackEvent.Done(lang))
                            override fun onScheduled() = finish(PackEvent.Scheduled(lang))
                            override fun onError(error: Int) = finish(PackEvent.Failed(lang, errorName(error)))
                        })
                    } catch (e: Exception) {
                        finish(PackEvent.Failed(lang, e.message ?: "request refused"))
                    }
                    // Some services never answer; don't wait forever.
                    main.postDelayed({ finish(PackEvent.Failed(lang, "no answer from the speech service")) }, 10 * 60_000L)
                }
            }
            onEvent(outcome)
        }
    }

    /** Google's own screen for adding offline speech languages, as a fallback. */
    fun openLanguageSettings(): Boolean {
        val pkg = "com.google.android.tts"
        val actions = listOf(
            "com.google.android.libraries.speech.modelmanager.languagepack.settings.AddLanguagesActivity",
            "com.google.android.libraries.speech.modelmanager.languagepack.settings.SettingsActivity",
        )
        for (a in actions) {
            val i = Intent(a).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(i); true }.getOrDefault(false)) return true
        }
        return runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    /** Languages with an installed offline pack, learned from the last support check. */
    @Volatile private var installed: List<String>? = null

    override suspend fun transcribe(req: SpeechRequest): List<SpokenLine> {
        if (!available()) throw SpeechException("unavailable", "On-device speech recognition is not available on this phone", retryable = false)
        // The language check can go unanswered by the speech service; never wait on it for long.
        if (!online && installed == null) runCatching { support(req.languages) }
        // Only ask for languages the phone can actually recognise offline.
        val usable = (if (online) req.languages else installed?.let { inst -> req.languages.filter { l -> inst.any { it.equals(l, true) } } }.orEmpty().ifEmpty { req.languages })
            .let { if (singleLanguage()) it.take(1) else it }
        return attempt(req.copy(languages = usable, hints = cleanHints(req.hints))).getOrThrow()
    }

    private suspend fun attempt(req: SpeechRequest): Result<List<SpokenLine>> {
        val r = once(req)
        // "client" / "busy" right after another session closed is a hiccup: one more try after a pause.
        val code = (r.exceptionOrNull() as? SpeechException)?.code
        if (code == "client" || code == "busy") { kotlinx.coroutines.delay(1_500); return once(req) }
        return r
    }

    private suspend fun once(req: SpeechRequest): Result<List<SpokenLine>> = suspendCancellableCoroutine { cont ->
        val stop = run(req) { r -> if (cont.isActive) cont.resume(r) }
        // A cancelled transcription must not leave its session open in the speech service.
        cont.invokeOnCancellation { stop() }
    }

    /** Starts a session; the returned function ends it early (from any thread). */
    private fun run(req: SpeechRequest, done: (Result<List<SpokenLine>>) -> Unit): () -> Unit {
        val stopper = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) {
            done(Result.failure(SpeechException("pipe", e.message ?: "pipe failed"))); return {}
        }
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val lines = ArrayList<SpokenLine>()
        val pieceMs = req.endMs - req.startMs
        val fedMs = java.util.concurrent.atomic.AtomicLong(0)
        var lastEnd = 0L
        var finished = false

        main.post {
            val recognizer = create()
            var timeout: Runnable? = null

            fun complete(result: Result<List<SpokenLine>>) {
                if (finished) return
                stopper.set(null)
                finished = true
                timeout?.let { main.removeCallbacks(it) }
                try { recognizer.destroy() } catch (_: Exception) {}
                try { readEnd.close() } catch (_: Exception) {}
                // Unblocks a feeder still writing into a pipe nobody reads.
                try { writeEnd.close() } catch (_: Exception) {}
                done(result)
            }

            fun collect(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (text.isNullOrEmpty()) return
                val lang = if (Build.VERSION.SDK_INT >= 34) results.getString(SpeechRecognizer.DETECTED_LANGUAGE) else null
                // Word timings when the service provides them; otherwise the
                // line ends where the audio fed so far ends.
                val parts: List<RecognitionPart>? = if (Build.VERSION.SDK_INT >= 34) {
                    @Suppress("DEPRECATION")
                    results.getParcelableArrayList<RecognitionPart>(SpeechRecognizer.RECOGNITION_PARTS)
                } else null
                val timed = parts?.filter { it.timestampMillis > 0 }
                val start: Long
                val end: Long
                if (!timed.isNullOrEmpty()) {
                    start = timed.first().timestampMillis.coerceIn(0, pieceMs)
                    end = (timed.last().timestampMillis + 400).coerceIn(start, pieceMs)
                } else {
                    start = lastEnd
                    end = fedMs.get().coerceIn(start, pieceMs)
                }
                lastEnd = end
                lines.add(SpokenLine(req.startMs + start, req.startMs + end, text, lang = lang))
            }

            stopper.set { main.post { complete(Result.failure(SpeechException("cancelled", "transcription cancelled"))) } }
            if (cancelled.get()) { complete(Result.failure(SpeechException("cancelled", "transcription cancelled"))); return@post }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onSegmentResults(segmentResults: Bundle) = collect(segmentResults)
                override fun onEndOfSegmentedSession() = complete(Result.success(lines.toList()))
                override fun onResults(results: Bundle?) {
                    collect(results)
                    complete(Result.success(lines.toList()))
                }
                override fun onError(error: Int) {
                    // "No match" on a piece is silence or noise, not a failure.
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        complete(Result.success(lines.toList()))
                    } else {
                        val name = errorName(error)
                        val retryable = error != SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED && error != SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                        complete(Result.failure(SpeechException(name, "speech recogniser error $name", retryable)))
                    }
                }
            })

            val intent = intentFor(req.languages, req.hints).apply {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Wav.SAMPLE_RATE)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
            // No end-of-session signal in time: keep what was heard if the whole piece was fed, else fail.
            timeout = Runnable {
                if (lines.isNotEmpty() && fedMs.get() >= pieceMs - 250) { Log.w(TAG, "no end-of-session; keeping ${lines.size} lines"); complete(Result.success(lines.toList())) }
                else complete(Result.failure(SpeechException("timeout", "recogniser did not finish in time")))
            }
            val feed = speed()
            main.postDelayed(timeout!!, (pieceMs / feed).toLong() + pieceMs + 30_000)
            try {
                recognizer.startListening(intent)
            } catch (e: Exception) {
                complete(Result.failure(SpeechException("start", e.message ?: "startListening failed")))
                return@post
            }

            // Feed the range at SPEED× real time, then close: end of input ends the session.
            Thread({
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                        FileInputStream(req.file).use { input ->
                            val from = Wav.HEADER_BYTES + req.startMs * Wav.BYTES_PER_MS
                            val to = Wav.HEADER_BYTES + req.endMs * Wav.BYTES_PER_MS
                            input.channel.position(from)
                            val buf = ByteArray(6_400) // 200 ms
                            var left = to - from
                            val began = System.nanoTime()
                            var sentMs = 0.0
                            while (left > 0) {
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                left -= n
                                sentMs += n / Wav.BYTES_PER_MS.toDouble()
                                fedMs.set(sentMs.toLong())
                                val dueMs = sentMs / feed
                                val elapsedMs = (System.nanoTime() - began) / 1e6
                                if (dueMs > elapsedMs) Thread.sleep((dueMs - elapsedMs).toLong())
                            }
                            // Trailing silence lets the endpointer close the last utterance.
                            out.write(ByteArray(16_000))
                        }
                    }
                } catch (e: IOException) {
                    Log.d(TAG, "feed ended: ${e.message}")
                }
            }, "EchoMind-speech-feed").start()
        }
        return { cancelled.set(true); stopper.get()?.invoke() }
    }

    companion object {
        private const val TAG = "EchoSpeech"

        /** Name hints the recogniser can take: plain words (any script), short, de-duplicated, at most 60. */
        fun cleanHints(hints: List<String>): List<String> = hints.asSequence()
            .map { it.replace(Regex("[^\\p{L}\\p{M}\\p{N} '-]"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 2..30 && it.any(Char::isLetter) }
            .distinctBy { it.lowercase() }.take(60).toList()
        const val SPEED = 3.0

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "audio"
            SpeechRecognizer.ERROR_CLIENT -> "client"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permissions"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
            SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "server"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language_not_supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language_unavailable"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too_many_requests"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "cannot_check_support"
            else -> "error_$code"
        }
    }
}
