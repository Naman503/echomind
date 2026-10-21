package app.echomind.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.util.Log
import app.echomind.App
import app.echomind.MainActivity
import app.echomind.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps reading aloud with the screen off, and gives it lock-screen,
 * notification and headset (Bluetooth) controls through a media session.
 */
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSession
    private val speaker get() = App.graph.speaker

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reading aloud", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        session = MediaSession(this, "EchoMind").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = speaker.resume()
                override fun onPause() = speaker.pause()
                override fun onSkipToNext() = speaker.next()
                override fun onSkipToPrevious() = speaker.previous()
                override fun onStop() = speaker.stop()
            })
            isActive = true
        }
        scope.launch {
            speaker.state.map { Triple(it.active, it.paused, it.index to it.title) }.distinctUntilChanged().collect { (active, _, _) ->
                if (!active) { stopSelf(); return@collect }
                update()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SPEAK -> speaker.speak(intent.getStringExtra(EXTRA_TITLE).orEmpty(), listOfNotNull(intent.getStringExtra(EXTRA_TITLE)?.let { "$it." }, intent.getStringExtra(EXTRA_TEXT)))
            ACTION_BRIEF -> scope.launch { val b = App.graph.briefing.morning(); speaker.speak(b.title, b.parts) }
            ACTION_TOGGLE -> speaker.toggle()
            ACTION_NEXT -> speaker.next()
            ACTION_STOP -> { speaker.stop(); stopSelf() }
        }
        try {
            startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            Log.w("EchoPlayback", "no foreground: ${e.message}")
        }
        return START_NOT_STICKY
    }

    private fun update() {
        val s = speaker.state.value
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, s.title).putString(MediaMetadata.METADATA_KEY_ARTIST, "EchoMind").build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(if (s.paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, s.index.toLong(), s.speed)
            .build())
        getSystemService(NotificationManager::class.java).notify(ID, notification())
    }

    private fun action(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, PlaybackService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(): Notification {
        val s = speaker.state.value
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_listen)
            .setContentTitle(s.title.ifBlank { "Reading aloud" })
            .setContentText(s.sentence)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, if (s.paused) "Play" else "Pause", action(ACTION_TOGGLE, 1)).build())
            .addAction(Notification.Action.Builder(null, "Next", action(ACTION_NEXT, 2)).build())
            .addAction(Notification.Action.Builder(null, "Stop", action(ACTION_STOP, 3)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "reading"
        private const val ID = 4400
        const val ACTION_TOGGLE = "app.echomind.READ_TOGGLE"
        const val ACTION_NEXT = "app.echomind.READ_NEXT"
        const val ACTION_STOP = "app.echomind.READ_STOP"
        const val ACTION_SPEAK = "app.echomind.READ_SPEAK"
        const val ACTION_BRIEF = "app.echomind.READ_BRIEF"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, PlaybackService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
