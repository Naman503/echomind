package app.echomind.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the AI and speech layers are allowed to do, and how hard the phone may work. */
@Serializable
data class Settings(
    val onboarded: Boolean = false,
    val userName: String = "",

    /** Master switch: in LOCAL_ONLY nothing personal ever leaves the phone, whatever a feature is set to. */
    val privacy: Privacy = Privacy.LOCAL_ONLY,
    /** Provider per feature (see [Feature]); ids from [Providers]. */
    val providers: Map<String, String> = emptyMap(),
    /** Features whose cloud use the user has explicitly agreed to (first-use consent). */
    val cloudConsent: Set<String> = emptySet(),

    // Capture
    /** The user wants EchoMind listening; used to offer "tap to resume" after a reboot or kill. */
    val captureWanted: Boolean = false,
    val inputDeviceId: Int = 0,
    val windowMinutes: Int = 2,
    /** Paused until this time (0 = not paused, Long.MAX_VALUE = until resumed). */
    val pausedUntil: Long = 0,
    /** Do not record between these minutes of the day (-1 = off), e.g. sleep. */
    val quietFrom: Int = -1,
    val quietTo: Int = -1,
    /** Keep a compressed copy of each recording this many days after transcription (0 = delete at once, -1 = forever). */
    val keepRecordingsDays: Int = 7,
    /** small (16 kbps, ~1 MB per 8 min) | clear (32 kbps) */
    val recordingQuality: String = "small",

    // Speech
    val speechLanguages: List<String> = listOf("en-IN", "hi-IN"),
    /** accurate (1×) | balanced (1.5×) | fast (3×): how fast audio is fed to Google's recogniser. */
    val speechSpeed: String = "balanced",
    /** switch = detect and switch between the chosen languages; single = only the first one (most accurate). */
    val languageMode: String = "switch",
    /** Automatic gain control on the microphone, so quiet or distant speech is heard. */
    val boostQuietSpeech: Boolean = true,
    /** Which open model the on-device "sherpa" engine uses. */
    val sherpaModel: String = "whisper-small",
    /** Enrolled "me" voice is used to label lines (needs the speaker pack). */
    val labelSpeakers: Boolean = true,
    /** strict | normal | lenient — how sure it must be that a line was you. */
    val voiceSensitivity: String = "normal",
    /** Keeps learning your voice from lines it is very sure about. */
    val voiceLearn: Boolean = true,
    /** Remembers the voices of people you talk to and labels their lines by name. */
    val recognisePeople: Boolean = true,

    // Calls
    val importCalls: Boolean = false,
    /** Only recordings made after this are imported (set when the feature is turned on, or a chosen backfill date). */
    val callsSince: Long = 0,

    // AI
    val localModel: String = "gemma-4-e2b",
    /** auto | cpu | gpu */
    val localBackend: String = "auto",
    val geminiModel: String = "gemini-3.5-flash-lite",
    val anthropicModel: String = "claude-haiku-5-5",
    val openaiBaseUrl: String = "https://api.openai.com/v1",
    val openaiModel: String = "gpt-6-luna",
    val notesLanguage: String = "English",
    /** fast | balanced | best — how much the AI thinks before it writes (see Ai.resolve). */
    val aiQuality: String = "balanced",
    val session: AiSession = AiSession(),
    /** Suggested tasks the AI is very sure of (yours, with a time) go straight to the to-do list. */
    val autoAcceptConfident: Boolean = false,
    /** Tasks you type without a date go on Today's list. */
    val undatedToToday: Boolean = true,
    val semanticSearch: Boolean = true,

    // Schedules (minutes of the day)
    val morningBriefAt: Int = 8 * 60,
    val eveningRecapAt: Int = 22 * 60,
    val notifyMorning: Boolean = true,
    val notifyEvening: Boolean = true,
    val notifySuggestions: Boolean = true,
    /** The listening notification: minimal | status (running, queue, phone heat) | full (+ today's numbers, next reminder). */
    val statusDetail: String = "full",

    // Work limits
    /** 10..100: how much of the time the phone may spend on transcription/AI. */
    val utilization: Int = 60,
    val onlyWhileCharging: Boolean = false,
    val maxBatteryTempC: Int = 40,
    val minBatteryPct: Int = 20,
    /** After pausing for heat, resume only once this many °C below the limit. */
    val coolDownC: Int = 2,
    /** Android's own heat level that pauses work: 1 light · 2 moderate · 3 severe · 4 critical. */
    val thermalPauseAt: Int = 2,
    /** Android's heat forecast (1.0 = it starts slowing the phone); pause at this level. Above 1.2 = ignore. */
    val headroomLimit: Float = 0.85f,
    /** A break after this many minutes of near-continuous work (0 = never), lasting [breakMin]. */
    val breakAfterMin: Int = 20,
    val breakMin: Int = 5,
    /** How much of the time it may work while charging. */
    val chargingUtilization: Int = 80,
    /** CPU threads for speech and AI, on battery and while charging. */
    val threadsBattery: Int = 2,
    val threadsCharging: Int = 4,
    /** Pause transcription and AI while Battery Saver is on. */
    val pauseOnBatterySaver: Boolean = false,
    /** You paused processing until this time (listening continues). */
    val processingPausedUntil: Long = 0,

    // Reading aloud
    /** open (Kokoro / Piper on the phone) | system (Android's voices) */
    val readEngine: String = "open",
    val voiceEnglish: String = "kokoro-heart",
    val voiceHindi: String = "kokoro-hf-alpha",
    /** 0.5…6× */
    val readSpeed: Float = 1.25f,
    /** Speak Ask answers automatically. */
    val autoReadAnswers: Boolean = false,
    /** Silence between sentences, ms. */
    val readPauseMs: Int = 80,
    /** Extra loudness for quiet earbuds, dB (0–12). */
    val readBoostDb: Int = 0,
    /** Pitch for phone voices (open voices keep their natural pitch). */
    val readPitch: Float = 1f,
    /** Other audio while reading: auto (duck short, pause long) | duck | pause */
    val readFocus: String = "auto",
    /** With no earphones connected: ask | allow (just use the speaker) | never (don't read aloud). */
    val speakerPolicy: String = "ask",

    // Proactive nudges
    val nudges: Boolean = true,
    val nudgesPerDay: Int = 4,
    /** Read nudges aloud — only when headphones are connected. */
    val speakNudges: Boolean = false,

    /** Transcript order: newest line at the top. */
    val transcriptNewestFirst: Boolean = true,

    // Sounds
    /** Keep songs you sing or that play near you, as their own clips. */
    val songs: Boolean = true,
    /** Look up which song it was on the web by itself (only a few words of lyrics are sent). */
    val songsAutoIdentify: Boolean = false,
    /** Note what's going on around you (traffic, kitchen, TV…) for an activity timeline. Uses more battery. */
    val ambient: Boolean = false,
    /** Days to keep the activity timeline. */
    val ambientDays: Int = 30,

    // Web
    /** off | ask (show each search first) | auto */
    val webSearch: String = "ask",
    /** duckduckgo | wikipedia | searxng | brave | google */
    val webProvider: String = "duckduckgo",
    /** Your own SearXNG instance (most private), e.g. https://search.example.com */
    val searxngUrl: String = "",
    val googleCx: String = "",
    /** Read the top pages, not just the result snippets (better answers, a few more requests). */
    val webReadPages: Boolean = true,

    // Organising (what the AI does by itself)
    /** File new notes into folders and tag them every night. */
    val organizeNightly: Boolean = true,
    /** Rewrite people's pages at night when there's more to say. */
    val storiesNightly: Boolean = true,
    /** Write today's / this week's / this month's plan each morning. */
    val autoPlans: Boolean = true,
    /** Conversations update tasks already on your list (done, moved, stuck). */
    val taskFollowUps: Boolean = true,
    /** Days a newly met person stays in "Names to remember". */
    val namesWindowDays: Int = 30,

    // Check-ins: the assistant asks about your day
    val checkIns: List<CheckInSlot> = listOf(
        CheckInSlot("morning", 8 * 60 + 30, kind = "morning", enabled = false, maxQuestions = 4),
        CheckInSlot("midday", 13 * 60 + 30, kind = "midday", enabled = false, maxQuestions = 4),
        CheckInSlot("evening", 21 * 60 + 30, kind = "evening", enabled = true, maxQuestions = 8),
    ),
    /** tasks | people | gaps | day | feelings | plans | learning | custom */
    val checkInTopics: Set<String> = setOf("tasks", "people", "gaps", "day", "feelings", "plans", "custom"),
    /** Your own questions, asked in evening check-ins ("Did you exercise?", "What did you learn?"). */
    val checkInCustom: List<String> = listOf("What went well today?"),
    val checkInSpeak: Boolean = true,
    val checkInAutoListen: Boolean = true,
    /** A pause this long ends an answer, ms. */
    val checkInPauseMs: Int = 2_500,
    val checkInSkipWords: String = "skip, next, pass, chhodo, pata nahi, don't know, rehne do",
    /** Rewrite today's journal with your answers after an evening check-in. */
    val checkInJournal: Boolean = true,
    /** Single questions through the day as notifications you can answer inline (who was that, did you finish…). */
    val quickQuestions: Boolean = true,
    val quickQuestionsPerDay: Int = 3,

    // Calendar
    /** Plans heard in conversations are offered as events. */
    val eventsFromConversations: Boolean = true,
    /** Default heads-up before your own events, minutes (-1 = none). */
    val eventRemindDefault: Int = 15,
    /** Show the phone's own calendars next to EchoMind's events (needs calendar permission). */
    val showPhoneCalendar: Boolean = false,
    /** Confirmed events are also written to the phone's main calendar. */
    val writeToPhoneCalendar: Boolean = false,
    /** A heads-up before events: who you're meeting and what's open with them. */
    val eventPrep: Boolean = true,

    // Look
    /** system | light | dark */
    val theme: String = "system",
    val dynamicColor: Boolean = false,
) {
    fun provider(f: Feature): String {
        val chosen = providers[f.id] ?: f.defaultProvider
        return if (privacy == Privacy.LOCAL_ONLY && !Providers.isLocal(chosen)) f.defaultProvider else chosen
    }

    fun isPaused(now: Long = System.currentTimeMillis()) = pausedUntil > now

    fun inQuietHours(minuteOfDay: Int): Boolean {
        if (quietFrom < 0 || quietTo < 0 || quietFrom == quietTo) return false
        return if (quietFrom < quietTo) minuteOfDay in quietFrom until quietTo
        else minuteOfDay >= quietFrom || minuteOfDay < quietTo
    }
}

@Serializable
enum class Privacy { LOCAL_ONLY, CLOUD_ALLOWED }

/** A scheduled check-in: when, on which days (1 = Monday … 7 = Sunday), what kind, how many questions. */
@Serializable
data class CheckInSlot(val id: String, val minute: Int, val days: Set<Int> = (1..7).toSet(), val kind: String = "evening", val enabled: Boolean = true, val maxQuestions: Int = 6)

/** When the AI reads the transcript. */
@Serializable
data class AiSession(
    val mode: SessionMode = SessionMode.LIVE,
    /** INTERVAL: run every N minutes. */
    val intervalMinutes: Int = 60,
    /** WORDS: run once this many unprocessed words have piled up. */
    val words: Int = 1500,
    /** A conversation ends after this much silence. */
    val gapMinutes: Int = 3,
    /** Split very long conversations at this length. */
    val maxConversationMinutes: Int = 45,
)

@Serializable
enum class SessionMode { LIVE, INTERVAL, WORDS, CHARGING, MANUAL }

/** Features that can each use a different provider. */
enum class Feature(val id: String, val label: String, val defaultProvider: String, val kind: Kind, val sends: String) {
    TRANSCRIBE("transcribe", "Speech to text", "android", Kind.SPEECH, "Audio clips of speech (silence is never sent)"),
    CALLS("calls", "Call recordings", "android", Kind.SPEECH, "Call recording audio"),
    NOTES("notes", "Notes, tasks & reminders", "local", Kind.LLM, "The transcript of each conversation, with your memory profile"),
    MEMORY("memory", "Memory & people", "local", Kind.LLM, "Facts the AI picked up today and what it already remembers"),
    ASK("ask", "Ask", "local", Kind.LLM, "Your question and the transcript lines that match it"),
    JOURNAL("journal", "Diary & insights", "local", Kind.LLM, "Summaries of the day's conversations and tasks"),
    ;

    enum class Kind { SPEECH, LLM }
}

private val Context.store by preferencesDataStore("settings")

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private val key = stringPreferencesKey("settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mutex = Mutex()
    private val _flow = MutableStateFlow(Settings())
    val flow: StateFlow<Settings> = _flow.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val value: Settings get() = _flow.value

    init {
        scope.launch {
            val raw = context.store.data.first()[key]
            _flow.value = raw?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings()
            _loaded.value = true
        }
    }

    suspend fun awaitLoaded(): Settings {
        loaded.first { it }
        return value
    }

    suspend fun update(change: (Settings) -> Settings) = mutex.withLock {
        val next = change(_flow.value)
        if (next == _flow.value) return@withLock
        _flow.value = next
        context.store.edit { it[key] = json.encodeToString(Settings.serializer(), next) }
    }
}
