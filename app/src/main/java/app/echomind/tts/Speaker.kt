package app.echomind.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.echomind.capture.Recorder
import app.echomind.core.SettingsStore
import app.echomind.models.ModelManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Reads things aloud: notes, tasks, briefs, answers.
 *
 * Open-source voices (Kokoro for natural English + Hindi, Piper as a light
 * option) run on the phone through sherpa-onnx; Android's own voices are the
 * fallback. Each sentence is generated while the previous one plays, so speech
 * starts in about a second and never waits between sentences.
 *
 * Speed goes far past 3×: up to ~1.5× is done by the voice model itself
 * (natural rhythm), the rest by Android's pitch-preserving time-stretch, so
 * 4–6× stays intelligible rather than chipmunk-like. Listening is muted
 * while it speaks so EchoMind never transcribes itself.
 */
class Speaker(
    private val context: Context,
    private val models: ModelManager,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    threads: () -> Int,
) {
    data class State(
        val active: Boolean = false,
        val paused: Boolean = false,
        val title: String = "",
        val items: List<String> = emptyList(),
        val index: Int = 0,
        val sentence: String = "",
        val speed: Float = 1f,
        val engine: String = "",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val tts = TtsModels(models, threads)
    private val am = context.getSystemService(AudioManager::class.java)
    private var job: Job? = null
    private var track: AudioTrack? = null
    private var trackRate = 0
    @Volatile private var paused = false
    private var focus: AudioFocusRequest? = null
    private var system: TextToSpeech? = null
    private var systemReady = CompletableDeferred<Boolean>()

    private data class Chunk(val item: Int, val text: String, val samples: FloatArray?, val rate: Int, val lang: String)

    fun speak(title: String, items: List<String>, startIndex: Int = 0) {
        val clean = items.map { Speakable.clean(it) }.filter { it.isNotBlank() }
        if (clean.isEmpty()) return
        val speed = settings.value.readSpeed
        _state.value = State(true, false, title, clean, startIndex.coerceIn(0, clean.lastIndex), speed = speed)
        restart(startIndex.coerceIn(0, clean.lastIndex))
        PlaybackService.start(context)
    }

    fun speak(title: String, text: String) = speak(title, listOf(text))

    fun pause() {
        paused = true
        track?.pause()
        system?.stop()
        _state.update { it.copy(paused = true) }
    }

    fun resume() {
        if (!_state.value.active) return
        paused = false
        track?.play()
        _state.update { it.copy(paused = false) }
        if (engineIsSystem()) restart(_state.value.index)
    }

    fun toggle() = if (_state.value.paused) resume() else pause()

    fun next() { val s = _state.value; if (s.active && s.index < s.items.lastIndex) restart(s.index + 1) }
    fun previous() { val s = _state.value; if (s.active) restart((s.index - 1).coerceAtLeast(0)) }
    fun jumpTo(i: Int) { if (_state.value.active) restart(i.coerceIn(0, _state.value.items.lastIndex)) }

    fun setSpeed(speed: Float) {
        val x = speed.coerceIn(0.5f, MAX_SPEED)
        _state.update { it.copy(speed = x) }
        scope.launch { settings.update { it.copy(readSpeed = x) } }
        applyStretch(x)
        system?.setSpeechRate(x)
    }

    fun stop() {
        job?.cancel()
        job = null
        finish()
    }

    private fun engineIsSystem() = _state.value.engine == "system"

    private fun restart(from: Int) {
        val old = job
        paused = false
        job = scope.launch(Dispatchers.Default) {
            old?.cancelAndJoin()
            track?.let { runCatching { it.pause(); it.flush() } }
            _state.update { it.copy(index = from, paused = false) }
            requestFocus()
            Recorder.assistantSpeaking = true
            try {
                play(from)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reading failed", e)
                _state.update { it.copy(error = e.message) }
            }
            if (isActive) finish()
        }
    }

    private fun finish() {
        previewVoice = null
        Recorder.assistantSpeaking = false
        track?.let { runCatching { it.pause(); it.flush() } }
        system?.stop()
        abandonFocus()
        _state.value = State(speed = _state.value.speed)
        PlaybackService.stop(context)
        scope.launch { delay(60_000); if (!_state.value.active) tts.release() }
    }

    /** Sentences of item [from] onwards, with the voice for each one's language. */
    private suspend fun play(from: Int) = kotlinx.coroutines.coroutineScope {
        val s = settings.value
        val items = _state.value.items
        val pv = previewVoice?.let { VoiceCatalog.get(it) }
        val en = pv?.takeIf { it.lang == "en" } ?: VoiceCatalog.get(s.voiceEnglish)
        val hi = pv?.takeIf { it.lang == "hi" } ?: VoiceCatalog.get(s.voiceHindi)
        val canOpen = (en != null && models.isInstalled(en.pack)) || (hi != null && models.isInstalled(hi.pack))
        if (!canOpen || s.readEngine == "system") { playSystem(from); return@coroutineScope }
        _state.update { it.copy(engine = "open") }

        val queue = Channel<Chunk>(capacity = 2)
        launch {
            for (i in from until items.size) {
                for (sentence in Speakable.sentences(items[i])) {
                    ensureActive()
                    val lang = Speakable.language(sentence)
                    val voice = (if (lang == "hi") hi else en)?.takeIf { models.isInstalled(it.pack) } ?: (if (lang == "hi") en else hi)?.takeIf { models.isInstalled(it.pack) }
                    val engine = voice?.let { tts.get(it.pack) }
                    if (voice == null || engine == null) { queue.send(Chunk(i, sentence, null, 0, lang)); continue }
                    val speed = _state.value.speed
                    val modelSpeed = minOf(speed, NATURAL_SPEED)
                    val sid = voice.sid.coerceIn(0, maxOf(0, engine.numSpeakers() - 1))
                    // Kokoro must be told the language per call, or espeak reads Hindi with English rules.
                    val audio = runCatching {
                        if (voice.engine == "kokoro") engine.generateWithConfig(sentence, com.k2fsa.sherpa.onnx.GenerationConfig(speed = modelSpeed, sid = sid, extra = mapOf("lang" to (if (lang == "hi") "hi" else "en-us"))))
                        else engine.generate(sentence, sid = sid, speed = modelSpeed)
                    }.getOrNull()
                    queue.send(Chunk(i, sentence, audio?.samples, audio?.sampleRate ?: 0, lang))
                }
            }
            queue.close()
        }
        for (chunk in queue) {
            ensureActive()
            _state.update { it.copy(index = chunk.item, sentence = chunk.text) }
            if (chunk.samples == null || chunk.samples.isEmpty()) { speakOneSystem(chunk.text, chunk.lang); continue }
            val t = trackFor(chunk.rate)
            applyStretch(_state.value.speed)
            var off = 0
            val step = chunk.rate / 10
            while (off < chunk.samples.size) {
                ensureActive()
                while (paused) { delay(100); ensureActive() }
                val n = minOf(step, chunk.samples.size - off)
                t.write(chunk.samples, off, n, AudioTrack.WRITE_BLOCKING)
                off += n
            }
            // A short breath between sentences.
            t.write(FloatArray(chunk.rate / 12), 0, chunk.rate / 12, AudioTrack.WRITE_BLOCKING)
        }
        // Let the last sentence play out.
        track?.let { t ->
            val endFrames = t.playbackHeadPosition + t.bufferSizeInFrames
            var waited = 0
            while (t.playbackHeadPosition < endFrames && waited < 4_000) { delay(50); waited += 50 }
        }
    }

    private fun trackFor(rate: Int): AudioTrack {
        track?.let { if (trackRate == rate) { if (it.playState != AudioTrack.PLAYSTATE_PLAYING && !paused) it.play(); return it } }
        track?.release()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            // Time-stretch needs headroom: about a second of audio, more than min × the top speed.
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * MAX_SPEED.toInt(), rate * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        trackRate = rate
        applyStretch(_state.value.speed)
        return t
    }

    /** The part of the speed the voice model doesn't do, as pitch-preserving time-stretch. */
    private fun applyStretch(speed: Float) {
        val t = track ?: return
        val stretch = if (speed <= NATURAL_SPEED) 1f else speed / NATURAL_SPEED
        var x = stretch
        while (x >= 1f) {
            if (runCatching { t.playbackParams = PlaybackParams().allowDefaults().setSpeed(x).setPitch(1f) }.isSuccess) return
            x -= 0.25f // above the device's time-stretch limit: use the highest it accepts
        }
    }

    // ── Android's built-in voices, the fallback ──

    private suspend fun ensureSystem(): TextToSpeech? {
        system?.let { if (systemReady.await()) return it }
        val ready = CompletableDeferred<Boolean>()
        systemReady = ready
        val engine = withContext(Dispatchers.Main) { TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) } }
        system = engine
        return if (ready.await()) engine else null
    }

    private suspend fun playSystem(from: Int) {
        _state.update { it.copy(engine = "system") }
        val items = _state.value.items
        for (i in from until items.size) {
            for (sentence in Speakable.sentences(items[i])) {
                while (paused) { delay(100) }
                _state.update { it.copy(index = i, sentence = sentence) }
                speakOneSystem(sentence, Speakable.language(sentence))
            }
        }
    }

    private suspend fun speakOneSystem(text: String, lang: String) {
        val engine = ensureSystem() ?: return
        engine.language = if (lang == "hi") Locale("hi", "IN") else Locale("en", "IN")
        engine.setSpeechRate(_state.value.speed.coerceAtMost(6f))
        val done = CompletableDeferred<Unit>()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.complete(Unit) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { done.complete(Unit) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { done.complete(Unit) }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u${System.nanoTime()}")
        done.await()
    }

    // ── audio focus: other audio ducks while we speak; a call or another player pauses us ──

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_GAIN -> if (_state.value.paused && _state.value.active) resume()
        }
    }

    private fun requestFocus() {
        // Short announcements duck other audio; longer reads pause it, so music doesn't play under speech.
        val long = _state.value.items.sumOf { it.length } > 280
        val req = AudioFocusRequest.Builder(if (long) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focus = req
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() { focus?.let { am.abandonAudioFocusRequest(it) }; focus = null }

    /**
     * How much faster than real time [voiceId] speaks on this phone (audio seconds per
     * second of work). Above the listening speed means smooth playback.
     */
    suspend fun measure(voiceId: String): Double? = withContext(Dispatchers.Default) {
        val v = VoiceCatalog.get(voiceId) ?: return@withContext null
        val engine = tts.get(v.pack) ?: return@withContext null
        val text = if (v.lang == "hi") "नमस्ते, आज आपके तीन काम बाकी हैं और शाम पाँच बजे एक मीटिंग है।" else "Good morning. You have three tasks today, and a meeting with Priya at five in the evening."
        engine.generate("Warm up.", v.sid, 1f)
        val t0 = System.nanoTime()
        val audio = if (v.engine == "kokoro") engine.generateWithConfig(text, com.k2fsa.sherpa.onnx.GenerationConfig(speed = 1f, sid = v.sid, extra = mapOf("lang" to (if (v.lang == "hi") "hi" else "en-us"))))
        else engine.generate(text, v.sid, 1f)
        val secs = (System.nanoTime() - t0) / 1e9
        (audio.samples.size.toDouble() / audio.sampleRate) / secs.coerceAtLeast(0.01)
    }

    /** Says a short sample in [voiceId]. */
    fun preview(voiceId: String) {
        val v = VoiceCatalog.get(voiceId) ?: return
        previewVoice = voiceId
        speak("Voice preview", if (v.lang == "hi") "नमस्ते! मैं आपकी याददाश्त हूँ। आज के काम पढ़कर सुनाऊँ?" else "Hi! I'm your second brain. Want me to read today's tasks?")
    }

    /** A voice being previewed, used instead of the chosen one for that language until reading ends. */
    @Volatile private var previewVoice: String? = null

    fun headphonesConnected(): Boolean = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type in setOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_HEADSET)
    }

    companion object {
        private const val TAG = "EchoSpeaker"
        /** Up to this, the voice model speeds up itself; beyond it, time-stretch takes over. */
        const val NATURAL_SPEED = 1.5f
        const val MAX_SPEED = 6f
    }
}

/** Turns notes and lists into things worth hearing. */
object Speakable {
    fun clean(s: String): String = s.lines().joinToString("\n") { raw ->
        var l = raw.trim()
        l = when {
            l.startsWith("- [x]", true) -> "Done: " + l.substring(5).trim()
            l.startsWith("- [ ]") -> "To do: " + l.substring(5).trim()
            else -> l
        }
        l.trimStart('#', '-', '*', '>', ' ')
            .replace("**", "")
            .replace(Regex("\\[(\\d+)]"), "")
            .replace(Regex("https?://\\S+"), "a link")
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            .replace("---", "")
            .trim()
    }.replace(Regex("\n{2,}"), "\n").trim()

    /** Sentences short enough to start fast and to pace well. */
    fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val parts = line.split(Regex("(?<=[.!?।])\\s+"))
            for (p in parts) {
                var rest = p.trim()
                while (rest.length > 220) {
                    val cut = rest.lastIndexOfAny(charArrayOf(',', ';', ':', ' '), 200).takeIf { it > 60 } ?: 200
                    out.add(rest.substring(0, cut + 1).trim()); rest = rest.substring(cut + 1).trim()
                }
                if (rest.isNotEmpty()) out.add(if (rest.last().isLetterOrDigit()) "$rest." else rest)
            }
        }
        return out
    }

    /** "hi" when a good part of the sentence is Devanagari. */
    fun language(s: String): String {
        val letters = s.count { it.isLetter() }
        if (letters == 0) return "en"
        val deva = s.count { it in 'ऀ'..'ॿ' }
        return if (deva * 3 >= letters) "hi" else "en"
    }
}
