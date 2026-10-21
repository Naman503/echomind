package app.echomind.capture

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notices when Android stops giving us real audio.
 *
 * Since Android 10 only one app hears the microphone at a time: during a phone
 * or WhatsApp call, or while another app records, our AudioRecord keeps
 * running but receives zeros ("silenced"). We detect that through the
 * recording callback and the audio mode, so the gap is labelled honestly and,
 * for calls, the dialer's own call recording is imported afterwards.
 */
class MicGuard(private val context: Context, private val onCallEnded: () -> Unit) {
    enum class Blocker { NONE, CALL, OTHER_APP }

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val _blocker = MutableStateFlow(Blocker.NONE)
    val blocker: StateFlow<Blocker> = _blocker.asStateFlow()
    private var started = false
    private var wasInCall = false

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) = evaluate(configs)
    }

    private val modeListener: Any? = if (Build.VERSION.SDK_INT >= 31) {
        AudioManager.OnModeChangedListener { evaluate(am.activeRecordingConfigurations) }
    } else null

    fun start() {
        if (started) return
        started = true
        am.registerAudioRecordingCallback(recordingCallback, main)
        if (Build.VERSION.SDK_INT >= 31) {
            am.addOnModeChangedListener(context.mainExecutor, modeListener as AudioManager.OnModeChangedListener)
        }
        evaluate(am.activeRecordingConfigurations)
    }

    fun stop() {
        if (!started) return
        started = false
        am.unregisterAudioRecordingCallback(recordingCallback)
        if (Build.VERSION.SDK_INT >= 31) am.removeOnModeChangedListener(modeListener as AudioManager.OnModeChangedListener)
        set(Blocker.NONE)
    }

    private fun inCall(): Boolean = when (am.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> true
        else -> Build.VERSION.SDK_INT >= 30 && am.mode == AudioManager.MODE_CALL_SCREENING
    }

    private fun evaluate(configs: List<AudioRecordingConfiguration>) {
        val session = Recorder.sessionId
        val ours = configs.firstOrNull { it.clientAudioSessionId == session }
        val silenced = Build.VERSION.SDK_INT >= 29 && ours?.isClientSilenced == true
        val call = inCall()
        val next = when {
            call && (silenced || Recorder.isRunning()) -> Blocker.CALL
            silenced -> Blocker.OTHER_APP
            else -> Blocker.NONE
        }
        set(next)
        if (wasInCall && !call) onCallEnded()
        wasInCall = call
    }

    private fun set(b: Blocker) {
        if (_blocker.value == b) return
        _blocker.value = b
        Recorder.setSilenced(b != Blocker.NONE)
    }
}
