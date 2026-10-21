package app.echomind.ui.components

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.echomind.App

/**
 * Push-to-talk for commands and questions: on-device recognition straight
 * from the microphone, with live partial text. Nothing leaves the phone.
 */
class VoiceInput(
    private val context: android.content.Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    /** Nothing was said before the recogniser gave up. */
    private val onNothing: () -> Unit = {},
    /** How long a pause ends the answer (longer for check-in answers). */
    private val pauseMs: () -> Long = { 0L },
) {
    var listening by mutableStateOf(false)
        private set
    private var recognizer: SpeechRecognizer? = null

    fun start() {
        if (listening) return
        App.graph.speaker.stop()
        val r = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                listening = false
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onFinal)
                r.destroy(); recognizer = null
            }
            override fun onError(error: Int) {
                listening = false; r.destroy(); recognizer = null
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) onNothing()
            }
        })
        val langs = App.graph.settings.value.speechLanguages
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langs.firstOrNull() ?: "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            pauseMs().takeIf { it > 0 }?.let {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, it)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, it)
            }
            if (langs.size > 1 && App.graph.settings.value.languageMode != "single") {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, ArrayList(langs))
            }
        })
        listening = true
    }

    fun stop() { recognizer?.stopListening() }
    fun release() { recognizer?.destroy(); recognizer = null; listening = false }
}

@Composable
fun rememberVoiceInput(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onNothing: () -> Unit = {}, pauseMs: () -> Long = { 0L }): VoiceInput {
    val ctx = LocalContext.current
    val v = remember { VoiceInput(ctx, onPartial, onFinal, onNothing, pauseMs) }
    DisposableEffect(Unit) { onDispose { v.release() } }
    return v
}
