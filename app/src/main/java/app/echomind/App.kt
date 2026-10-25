package app.echomind

import app.echomind.capture.Playback
import app.echomind.capture.Recordings

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
import app.echomind.brain.Actions
import app.echomind.brain.Analyzer
import app.echomind.brain.Book
import app.echomind.brain.Travel
import app.echomind.brain.Places
import app.echomind.telemetry.UsageReporter
import app.echomind.telemetry.UsageWorker
import kotlinx.coroutines.flow.first
import app.echomind.brain.DayReview
import app.echomind.brain.Agenda
import app.echomind.brain.Ask
import app.echomind.brain.Assistant
import app.echomind.brain.Briefing
import app.echomind.brain.Organizer
import app.echomind.brain.People
import app.echomind.brain.Web
import app.echomind.brain.CheckIns
import app.echomind.brain.Stats
import app.echomind.brain.Plans
import app.echomind.brain.VoicePrints
import app.echomind.brain.Sounds
import app.echomind.brain.Brain
import app.echomind.brain.Embedder
import app.echomind.brain.Enricher
import app.echomind.brain.Journal
import app.echomind.brain.Memory
import app.echomind.brain.Notes
import app.echomind.brain.Nudges
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
import app.echomind.stt.SpeechPacks
import app.echomind.tts.Speaker
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
    val usage = UsageReporter(app, cloud)
    val models = ModelManager(app, scope)
    val governor = Governor(app, settings)
    val notifier = Notifier(app)
    val reminders = ReminderAlarms(app)

    private val feedSpeed = { when (settings.value.speechSpeed) { "accurate" -> 1.0; "fast" -> 3.0; else -> 1.5 } }
    private val singleLanguage = { settings.value.languageMode == "single" }
    val androidSpeech = AndroidSpeech(app, speed = feedSpeed, singleLanguage = singleLanguage)
    val speechPacks = SpeechPacks(androidSpeech, scope)
    val sherpa = SherpaSpeech(models, { settings.value.sherpaModel }, { governor.threads() })
    val voice = VoiceId(app, models) { settings.value.voiceSensitivity }
    val voicePrints = VoicePrints(db)
    private val speechEngines = mapOf(
        "android" to androidSpeech,
        // Server-side: real time is the natural pace for it.
        "google_online" to AndroidSpeech(app, online = true, speed = { 1.0 }, singleLanguage = singleLanguage),
        "sherpa" to sherpa,
        "sarvam" to SarvamSpeech(cloud, secrets) { Feature.TRANSCRIBE },
        "elevenlabs" to ElevenLabsSpeech(cloud, secrets) { Feature.TRANSCRIBE },
        "deepgram" to DeepgramSpeech(cloud, secrets) { Feature.TRANSCRIBE },
        "groq_stt" to GroqSpeech(cloud, secrets) { Feature.TRANSCRIBE },
        "gemini_stt" to GeminiSpeech(cloud, secrets, { Feature.TRANSCRIBE }) { settings.value.geminiModel },
    )

    val localLlm = LocalLlm(app, models, scope, { settings.value.localModel }, { settings.value.localBackend }, { governor.threads() }, { settings.value.localContext })
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
    val analyzer = Analyzer(db, ai, settings)
    val enricher = Enricher(db, ai, settings, memory, notes, tasks, embedder, { nudges }, { people }, { agenda }, voicePrints, voice, analyzer)
    val journal = Journal(db, ai, settings, memory)
    val ask = Ask(db, ai, settings, embedder, { agenda }, { people }, { web }) { plans }
    val sessionizer = Sessionizer(db, settings)
    val recordings = Recordings(app, db, settings)
    val playback = Playback(db, recordings, scope)
    val transcriber = Transcriber(db, settings, governor, speechEngines, androidSpeech, sherpa, voice, recordings) { sounds }
    val calls = CallImporter(app, db, settings)
    val dayReview = DayReview(db, ai, settings, analyzer, enricher, journal)
    val book = Book(db, ai, settings)
    val places: Places by lazy { Places(db, settings, cloud, { travel }) { web } }
    val brain: Brain = Brain(app, db, settings, governor, transcriber, sessionizer, enricher, journal, tasks, notes, calls, ai, notifier, dayReview, book) {
        // Nightly extras after the journal and memory review: file new notes, refresh people's pages.
        if (settings.value.organizeNightly) runCatching { organizer.tidyNew() }
        if (settings.value.storiesNightly) runCatching { people.refreshStories(if (ai.llm(Feature.MEMORY).local) 3 else 12) }
    }
    val capture = CaptureController(app, db, settings, scope, { brain }) { sounds }
    val travel: Travel = Travel(app, settings,
        onStart = { keepRecording -> if (!keepRecording) capture.stop(); localLlm.release(); brain.poke() },
        onStop = { wasListening ->
            if (wasListening) capture.start()
            brain.poke()
            val waiting = db.audio().pendingCount().first()
            notifier.travelBack(if (waiting > 0) "$waiting recording${if (waiting == 1) "" else "s"} from your trip will be read as usual, then tonight's review catches up." else if (wasListening) "EchoMind is listening again." else "EchoMind is back to normal. Listening is off — turn it on from Today.")
        },
        notify = { on, title, text, auto -> notifier.travel(on, title, text, auto) },
    )
    val speaker = Speaker(app, models, settings, scope) { governor.threads() }
    val briefing = Briefing(db, settings, { agenda }) { plans }
    val assistant = Assistant(db, tasks, notes, briefing, speaker, { actions }, { agenda })
    val nudges = Nudges(db, settings, ai, notifier, { capture }, { speaker })
    val people = People(db, ai, settings, memory)
    val web = Web(cloud, settings, secrets, db, ai)
    val stats = Stats(db)
    val checkIns = CheckIns(app, db, ai, settings, tasks, memory, voicePrints, journal, notifier)
    val plans = Plans(db, ai, settings, memory) { agenda }
    val sounds = Sounds(app, db, settings, models, recordings, voice, ai, { web }, cloud)
    val agenda = Agenda(app, db, settings, people, notifier)
    val organizer = Organizer(db, ai, settings)
    val actions = Actions(db, ai, settings, tasks, notes, memory, people, agenda, { organizer }) { plans }
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
            runCatching { g.recordings.sweep() }
            g.reminders.scheduleDaily(s.morningBriefAt, s.eveningRecapAt)
            g.checkIns.schedule()
            reindexSearch(g)
            runCatching { g.tasks.acceptWaiting() }
            runCatching { cleanJunk(g) }
            runCatching { fixPeople(g) }
            runCatching { retryStuck(g) }
            runCatching { g.travel.showOngoing(); g.travel.checkPlans() }
            runCatching { g.tasks.mergeDuplicates() }
            logExitReasons()
        }
        app.echomind.surfaces.WidgetUpdater.start(this, g.scope)
        // Anonymous usage (telemetry/UsageReporter): every 6 hours, when online.
        g.usage.installCrashHandler()
        g.usage.once("app_installed")
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "usage", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UsageWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build(),
        )
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "watchdog", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.NONE).build(),
        )
    }

    /** Search keys follow the transliteration: when it improves, older lines are re-keyed once. */
    /** Once: drops speaker labels and prompt words that older readings saved as people or facts, and placeholder due dates. */
    private suspend fun cleanJunk(g: Graph) {
        if (g.db.memory().kv("junkClean") == "1") return
        for (e in g.db.memory().allEntities()) if (app.echomind.brain.Memory.isJunkName(e.name)) g.db.memory().deleteEntity(e.id)
        for (f in g.db.memory().activeFacts()) if (app.echomind.brain.Memory.isJunkName(f.subject)) g.db.memory().deleteFact(f.id)
        for (t in g.db.tasks().live()) if (t.dueText?.trim()?.lowercase()?.trimEnd('.') in app.echomind.brain.Tasks.PLACEHOLDERS) g.db.tasks().update(t.copy(dueText = null))
        g.db.memory().putKv(app.echomind.data.Kv("junkClean", "1", System.currentTimeMillis()))
    }

    /** Once: conversations that hit the retry limit because the app crashed (not their fault) get one fresh try. */
    private suspend fun retryStuck(g: Graph) {
        if (g.db.memory().kv("retryStuck") == "1") return
        g.db.conversations().retryExhausted()
        g.db.memory().putKv(app.echomind.data.Kv("retryStuck", "1", System.currentTimeMillis()))
    }

    /** Once: "people" that were never a speaker nor named as a person in any conversation were topics. */
    private suspend fun fixPeople(g: Graph) {
        if (g.db.memory().kv("peopleFix") == "1") return
        val named = g.db.analyses().conversationPeople().flatMap { j -> runCatching { app.echomind.util.Js.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), j) }.getOrDefault(emptyList()) }
            .map { it.trim().lowercase() }.toSet() + g.db.analyses().speakers().map { it.trim().lowercase() }
        for (e in g.db.memory().allEntities()) if (e.kind == "person" && e.name.trim().lowercase() !in named && !e.pinned) g.db.memory().updateEntity(e.copy(kind = "topic"))
        g.db.memory().putKv(app.echomind.data.Kv("peopleFix", "1", System.currentTimeMillis()))
    }

    private suspend fun reindexSearch(g: Graph) {
        if (g.db.memory().kv("searchKeys") == SEARCH_KEYS) return
        var after = 0L
        while (true) {
            val batch = g.db.utterances().after(after, 500)
            if (batch.isEmpty()) break
            batch.forEach { g.db.utterances().setSearchText(it.id, app.echomind.brain.TextNorm.searchable(it.text)) }
            after = batch.last().id
        }
        g.db.memory().putKv(app.echomind.data.Kv("searchKeys", SEARCH_KEYS, System.currentTimeMillis()))
    }

    /** Why Android last killed us — the main tool for diagnosing vivo's background killer. */
    private fun logExitReasons() {
        val am = getSystemService(android.app.ActivityManager::class.java)
        am.getHistoricalProcessExitReasons(packageName, 0, 3).forEach {
            Log.i("EchoApp", "previous exit: reason=${it.reason} importance=${it.importance} at=${it.timestamp} ${it.description ?: ""}")
        }
    }

    companion object {
        private const val SEARCH_KEYS = "2"
        lateinit var graph: Graph
            private set
    }
}
