package app.echomind

import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.echomind.ai.Ai
import app.echomind.ai.AnthropicLlm
import app.echomind.ai.GeminiLlm
import app.echomind.ai.LocalLlm
import app.echomind.ai.OpenAiLlm
import app.echomind.brain.Ask
import app.echomind.brain.Brain
import app.echomind.brain.Embedder
import app.echomind.brain.Enricher
import app.echomind.brain.Journal
import app.echomind.brain.Memory
import app.echomind.brain.Notes
import app.echomind.brain.Sessionizer
import app.echomind.brain.Tasks
import app.echomind.brain.Transcriber
import app.echomind.calls.CallImporter
import app.echomind.capture.CaptureController
import app.echomind.core.Cloud
import app.echomind.core.Feature
import app.echomind.core.Secrets
import app.echomind.core.SettingsStore
import app.echomind.data.Db
import app.echomind.models.ModelManager
import app.echomind.notify.Notifier
import app.echomind.notify.ReminderAlarms
import app.echomind.stt.AndroidSpeech
import app.echomind.stt.DeepgramSpeech
import app.echomind.stt.ElevenLabsSpeech
import app.echomind.stt.GeminiSpeech
import app.echomind.stt.GroqSpeech
import app.echomind.stt.SarvamSpeech
import app.echomind.stt.SherpaSpeech
import app.echomind.stt.VoiceId
import app.echomind.work.Governor
import app.echomind.work.WatchdogWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Builds every component once. Plain constructor wiring: fast startup, nothing hidden. */
class Graph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = Db.open(app)
    val settings = SettingsStore(app, scope)
    val secrets = Secrets(app)
    val cloud = Cloud(db.egress(), settings)
    val models = ModelManager(app, scope)
    val governor = Governor(app, settings)
    val notifier = Notifier(app)
    val reminders = ReminderAlarms(app)

    val androidSpeech = AndroidSpeech(app)
    val sherpa = SherpaSpeech(models, { settings.value.sherpaModel }, { governor.threads() })
    val voice = VoiceId(app, models)
    private val speechEngines = mapOf(
        "android" to androidSpeech,
        "sherpa" to sherpa,
        "sarvam" to SarvamSpeech(cloud, secrets) { Feature.TRANSCRIBE },
        "elevenlabs" to ElevenLabsSpeech(cloud, secrets) { Feature.TRANSCRIBE },
        "deepgram" to DeepgramSpeech(cloud, secrets) { Feature.TRANSCRIBE },
        "groq_stt" to GroqSpeech(cloud, secrets) { Feature.TRANSCRIBE },
        "gemini_stt" to GeminiSpeech(cloud, secrets, { Feature.TRANSCRIBE }) { settings.value.geminiModel },
    )

    val localLlm = LocalLlm(app, models, scope, { settings.value.localModel }, { settings.value.localBackend }, { governor.threads() })
    val ai = Ai(
        settings, secrets, localLlm,
        GeminiLlm(cloud, secrets) { settings.value.geminiModel },
        AnthropicLlm(cloud, secrets) { settings.value.anthropicModel },
        OpenAiLlm(cloud, secrets, { settings.value.openaiBaseUrl }) { settings.value.openaiModel },
    )
    val embedder = Embedder(app, models, db, scope)
    val tasks = Tasks(db, settings, reminders)
    val memory = Memory(db, ai, settings, embedder)
    val notes = Notes(db, ai, settings, embedder)
    val enricher = Enricher(db, ai, settings, memory, notes, tasks, embedder)
    val journal = Journal(db, ai, settings, memory)
    val ask = Ask(db, ai, settings, embedder)
    val sessionizer = Sessionizer(db, settings)
    val transcriber = Transcriber(db, settings, governor, speechEngines, androidSpeech, sherpa, voice)
    val calls = CallImporter(app, db, settings)
    val brain: Brain = Brain(app, db, settings, governor, transcriber, sessionizer, enricher, journal, tasks, notes, calls, ai, notifier)
    val capture = CaptureController(app, db, settings, scope) { brain }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        val g = graph
        g.notifier.ensureChannels()
        g.scope.launch {
            val s = g.settings.awaitLoaded()
            runCatching { g.capture.recoverOrphans() }.onFailure { Log.w("EchoApp", "recover failed", it) }
            g.reminders.scheduleDaily(s.morningBriefAt, s.eveningRecapAt)
            logExitReasons()
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "watchdog", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.NONE).build(),
        )
    }

    /** Why Android last killed us — the main tool for diagnosing vivo's background killer. */
    private fun logExitReasons() {
        val am = getSystemService(android.app.ActivityManager::class.java)
        am.getHistoricalProcessExitReasons(packageName, 0, 3).forEach {
            Log.i("EchoApp", "previous exit: reason=${it.reason} importance=${it.importance} at=${it.timestamp} ${it.description ?: ""}")
        }
    }

    companion object {
        lateinit var graph: Graph
            private set
    }
}
