package app.echomind.stt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** App-wide status of offline speech-pack downloads, so progress survives leaving the screen. */
class SpeechPacks(private val speech: AndroidSpeech, private val scope: CoroutineScope) {
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    /** language → "42%", "Installed", "Will download on Wi-Fi", or "Failed: reason". */
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()
    private val _failed = MutableStateFlow(false)
    /** True once a request failed: the UI then offers Google's own language screen. */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()
    private var job: Job? = null

    fun download(languages: List<String>) {
        if (job?.isActive == true) return
        _failed.value = false
        _status.update { it + languages.associateWith { "Starting…" } }
        job = scope.launch {
            speech.downloadPacks(languages) { e ->
                when (e) {
                    is AndroidSpeech.PackEvent.Progress -> _status.update { it + (e.language to "${e.percent}%") }
                    is AndroidSpeech.PackEvent.Done -> { app.echomind.telemetry.Usage.count("speech_pack", mapOf("outcome" to "installed")); _status.update { it + (e.language to "Installed") } }
                    is AndroidSpeech.PackEvent.Scheduled -> _status.update { it + (e.language to "Queued by Google — downloads on Wi-Fi") }
                    is AndroidSpeech.PackEvent.Failed -> { app.echomind.telemetry.Usage.count("speech_pack", mapOf("outcome" to "failed")); _failed.value = true; _status.update { it + (e.language to "Couldn't download (${e.reason})") } }
                }
            }
        }
    }

    fun openGoogleSettings() = speech.openLanguageSettings()
}
