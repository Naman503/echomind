package app.echomind.capture

import android.media.AudioAttributes
import android.media.MediaPlayer
import app.echomind.data.AudioItem
import app.echomind.data.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Listening back to recordings. Plays on into the next recording when one
 * ends, and keeps the microphone from recording its own playback.
 */
class Playback(private val db: Db, private val recordings: Recordings, private val scope: CoroutineScope) {
    data class State(
        val item: AudioItem? = null, val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val speed: Float = 1f,
        /** When playing one line: its id, and where in the recording it starts and stops. */
        val clipId: Long? = null, val clipFromMs: Long = 0, val clipToMs: Long? = null,
    )

    enum class Clip { PLAYING, NOT_KEPT, FAILED }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    /** The recordings shown on screen, oldest first, so playback can continue into the next one. */
    var queue: List<AudioItem> = emptyList()

    fun play(item: AudioItem, fromMs: Long = 0, clip: Triple<Long, Long, Long>? = null): Boolean {
        val file = recordings.file(item) ?: return false
        stopPlayer()
        val mp = MediaPlayer()
        runCatching {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(file.absolutePath)
            mp.prepare()
        }.onFailure { mp.release(); return false }
        mp.playbackParams = mp.playbackParams.setSpeed(_state.value.speed)
        if (fromMs > 0) mp.seekTo(fromMs.coerceAtMost(mp.duration - 200L), MediaPlayer.SEEK_CLOSEST)
        mp.setOnCompletionListener { if (clip != null) stop() else next(item) }
        mp.start()
        player = mp
        Recorder.assistantSpeaking = true
        _state.value = State(item, true, fromMs, mp.duration.toLong(), _state.value.speed, clip?.first, clip?.second ?: 0, clip?.third)
        ticker = scope.launch {
            while (isActive) {
                player?.let { p -> runCatching { _state.value = _state.value.copy(positionMs = p.currentPosition.toLong(), playing = p.isPlaying) } }
                // A single line stops at its end.
                _state.value.clipToMs?.let { end -> if (_state.value.positionMs >= end) { stop(); return@launch } }
                delay(if (clip != null) 100 else 250)
            }
        }
        return true
    }

    /** Plays just one transcript line (a little before to a little after), if its recording was kept. */
    suspend fun playLine(id: Long, startAt: Long, endAt: Long): Clip {
        val item = db.audio().keptAt(startAt) ?: db.audio().keptAt(endAt) ?: return Clip.NOT_KEPT
        val from = (startAt - item.startedAt - 300).coerceAtLeast(0); val to = endAt - item.startedAt + 400
        return if (play(item, from, Triple(id, from, to))) Clip.PLAYING else Clip.FAILED
    }

    /** Plays the kept recording that covers this moment (e.g. a transcript line), if there is one. */
    suspend fun playAt(at: Long): Boolean {
        val item = db.audio().keptAt(at) ?: return false
        play(item, at - item.startedAt - 1_000)
        return true
    }

    fun toggle() {
        val p = player ?: run { _state.value.item?.let { play(it, _state.value.positionMs) }; return }
        if (p.isPlaying) { p.pause(); Recorder.assistantSpeaking = false } else { p.start(); Recorder.assistantSpeaking = true }
        _state.value = _state.value.copy(playing = p.isPlaying)
    }

    fun seek(ms: Long) { player?.seekTo(ms, MediaPlayer.SEEK_CLOSEST); _state.value = _state.value.copy(positionMs = ms) }

    fun skip(deltaMs: Long) = seek((_state.value.positionMs + deltaMs).coerceIn(0, _state.value.durationMs))

    fun setSpeed(speed: Float) {
        player?.let { p -> val was = p.isPlaying; p.playbackParams = p.playbackParams.setSpeed(speed); if (!was) p.pause() }
        _state.value = _state.value.copy(speed = speed)
    }

    private fun next(done: AudioItem) {
        val following = queue.filter { it.startedAt > done.startedAt && recordings.file(it) != null }.minByOrNull { it.startedAt }
        // Only straight on into a recording that continues the same stretch of listening.
        if (following != null && following.startedAt - done.endedAt < 5 * 60_000L) play(following) else stop()
    }

    fun stop() {
        stopPlayer()
        _state.value = State(speed = _state.value.speed)
    }

    private fun stopPlayer() {
        ticker?.cancel(); ticker = null
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        Recorder.assistantSpeaking = false
    }
}
