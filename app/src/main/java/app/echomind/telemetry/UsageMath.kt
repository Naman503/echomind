package app.echomind.telemetry

import app.echomind.core.Feature
import app.echomind.core.Privacy
import app.echomind.core.Providers
import app.echomind.core.SessionMode
import app.echomind.core.Settings
import app.echomind.ui.*

/**
 * The pure side of anonymous usage: names, buckets, fixed enums and the daily settings
 * snapshot. Everything here turns app state into values from lists we define — never text
 * the user typed or said, never names, places, keys, addresses or model names they entered.
 */
object UsageMath {
    private val NAME = Regex("^[a-z][a-z0-9_]{0,47}$")
    private val KEY = Regex("^[a-z][a-z0-9_]{0,31}$")

    fun validName(n: String) = NAME.matches(n)

    /** ≤8 props, valid keys, values a short string, a finite number or a boolean (the API's rules). */
    fun cleanProps(p: Map<String, Any?>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((k, v) in p) {
            if (out.size >= 8) break
            if (v == null || !KEY.matches(k)) continue
            out[k] = when (v) {
                is Boolean -> v
                is Int, is Long, is Short, is Byte -> (v as Number).toLong()
                is Number -> v.toDouble().takeIf { it.isFinite() } ?: continue
                is Enum<*> -> v.name.lowercase().take(64)
                else -> v.toString().take(64)
            }
        }
        return out
    }

    // --- Buckets ---

    /** How long something took (answers, transcription): lt1s … gt60s. */
    fun latencyBucket(ms: Long): String = when {
        ms < 1_000 -> "lt1s"
        ms < 3_000 -> "1_3s"
        ms < 10_000 -> "3_10s"
        ms < 30_000 -> "10_30s"
        ms < 60_000 -> "30_60s"
        else -> "gt60s"
    }

    /** Time in the app, from coming to the foreground to leaving it. */
    fun sessionBucket(ms: Long): String = when {
        ms < 10_000 -> "lt10s"
        ms < 30_000 -> "10_30s"
        ms < 120_000 -> "30s_2m"
        ms < 300_000 -> "2_5m"
        ms < 900_000 -> "5_15m"
        ms < 3_600_000 -> "15_60m"
        else -> "gt60m"
    }

    /** How long a recording or a stretch of work was (same steps as [sessionBucket]). */
    fun durationBucket(ms: Long) = sessionBucket(ms)

    /** How many of something there are. */
    fun countBucket(n: Long): String = when {
        n <= 0 -> "0"
        n == 1L -> "1"
        n <= 5 -> "2_5"
        n <= 20 -> "6_20"
        n <= 100 -> "21_100"
        n <= 500 -> "101_500"
        n <= 2_000 -> "501_2k"
        n <= 10_000 -> "2k_10k"
        else -> "gt10k"
    }

    fun countBucket(n: Int) = countBucket(n.toLong())

    fun sizeBucket(bytes: Long): String = when {
        bytes <= 0 -> "0"
        bytes < 1_000_000 -> "lt1mb"
        bytes < 10_000_000 -> "1_10mb"
        bytes < 100_000_000 -> "10_100mb"
        bytes < 1_000_000_000 -> "100mb_1gb"
        bytes < 5_000_000_000 -> "1_5gb"
        else -> "gt5gb"
    }

    // --- Errors ---

    /**
     * A fixed category for a failure. The message is only *read* here, on the phone, to tell
     * a missing key from a rate limit; it is never sent.
     */
    fun errorCategory(t: Throwable?): String {
        if (t == null) return "none"
        val chain = generateSequence(t) { it.cause }.take(8).toList()
        if (chain.any { it is kotlinx.coroutines.CancellationException }) return "cancelled"
        if (chain.any { it is OutOfMemoryError }) return "oom"
        if (chain.any { it.javaClass.simpleName == "Blocked" }) return "blocked"
        val msg = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        if (chain.any { it is java.net.SocketTimeoutException || it is java.io.InterruptedIOException } || "timeout" in msg || "timed out" in msg) return "timeout"
        return when {
            "api key" in msg || "no_key" in msg || "http 401" in msg || "http 403" in msg || "unauthorized" in msg -> "auth"
            "http 429" in msg || "rate limit" in msg || "quota" in msg -> "rate_limit"
            Regex("http 5\\d\\d").containsMatchIn(msg) || "overloaded" in msg -> "server"
            Regex("http 4\\d\\d").containsMatchIn(msg) -> "bad_request"
            "no usable output" in msg || "returned no" in msg -> "bad_output"
            "model is downloaded" in msg || "no on-device" in msg || "not installed" in msg -> "no_model"
            "audio file missing" in msg -> "missing_audio"
            chain.any { it is java.net.UnknownHostException || it is java.net.ConnectException || it is javax.net.ssl.SSLException } -> "network"
            chain.any { it is java.io.IOException } -> "io"
            chain.any { it is IllegalStateException || it is IllegalArgumentException } -> "state"
            else -> "other"
        }
    }

    /** Android SpeechRecognizer error codes → fixed names. */
    fun speechError(code: Int): String = when (code) {
        1, 2 -> "network"            // NETWORK_TIMEOUT, NETWORK
        3 -> "audio"                 // AUDIO
        4 -> "server"                // SERVER
        5 -> "client"                // CLIENT
        6 -> "no_speech"             // SPEECH_TIMEOUT
        7 -> "no_match"              // NO_MATCH
        8 -> "busy"                  // RECOGNIZER_BUSY
        9 -> "permission"            // INSUFFICIENT_PERMISSIONS
        10 -> "too_many_requests"
        11 -> "server_disconnected"
        12, 13 -> "language"         // LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE
        14 -> "cannot_check"
        15 -> "cannot_listen"
        else -> "other"
    }

    // --- Where things happen ---

    /** Shortcut/notification/widget routes (MainActivity.EXTRA_ROUTE) → their fixed target, never ids. */
    private val LINK_TARGETS = setOf(
        "ask", "tasks", "reminders", "journal", "calendar", "plan", "dashboard", "notes", "songs", "checkin", "health",
        "checkins", "note", "settings", "travel", "map", "things", "voice", "conversation", "today",
    )

    fun linkTarget(route: String): String = route.substringBefore(':').takeIf { it in LINK_TARGETS } ?: "other"

    /** Every screen by a fixed name (class names can't be used: R8 renames them). */
    fun screen(r: Any?): String = when (r) {
        is TodayRoute -> "today"
        is TimelineRoute -> "timeline"
        is AskRoute -> "ask"
        is TasksRoute -> "tasks"
        is LibraryRoute -> "library"
        is TaskRoute -> "task"
        is RemindersRoute -> "reminders"
        is ListsRoute -> "lists"
        is ConversationRoute -> "conversation"
        is NoteRoute -> "note"
        is NewNoteRoute -> "note_new"
        is NotesRoute -> "notes"
        is MemoryRoute -> "memory"
        is PeopleRoute -> "people"
        is PersonRoute -> "person"
        is JournalRoute -> "journal"
        is DayRoute -> "day"
        is ChatsRoute -> "ask_history"
        is ProcessingRoute -> "processing"
        is SettingsRoute -> "settings"
        is ProvidersRoute -> "settings_providers"
        is ModelsRoute -> "settings_models"
        is RecordingRoute -> "settings_recording"
        is CallsRoute -> "settings_calls"
        is SessionsRoute -> "settings_sessions"
        is PowerRoute -> "settings_power"
        is NotificationsRoute -> "settings_notifications"
        is LedgerRoute -> "ledger"
        is UsageSharingRoute -> "usage_sharing"
        is DataRoute -> "settings_data"
        is VoiceRoute -> "settings_voice_id"
        is ReadingRoute -> "settings_reading"
        is RecordingsRoute -> "recordings"
        is CalendarRoute -> "calendar"
        is EventRoute -> "event"
        is NewEventRoute -> "event_new"
        is CirclesRoute -> "circles"
        is OrganizeRoute -> "organize"
        is CheckInRoute -> "check_in"
        is CheckInsRoute -> "check_ins"
        is DeveloperRoute -> "developer"
        is HealthRoute -> "health"
        is WidgetSetupRoute -> "widget_setup"
        is DashboardRoute -> "dashboard"
        is PlanRoute -> "plan"
        is SongsRoute -> "songs"
        is WebSettingsRoute -> "settings_web"
        is SoundsSettingsRoute -> "settings_sounds"
        is AiSettingsRoute -> "settings_ai"
        is PeopleSettingsRoute -> "settings_people"
        is CalendarSettingsRoute -> "settings_calendar"
        is TasksSettingsRoute -> "settings_tasks"
        is VoicesRoute -> "settings_voices"
        is InsightsRoute -> "insights"
        is DayReviewRoute -> "day_review"
        is CoachRoute -> "coach"
        is TopicsRoute -> "topics"
        is ReviewSettingsRoute -> "settings_review"
        is BookRoute -> "book"
        is DayNumbersRoute -> "day_numbers"
        is TravelRoute -> "travel"
        is MapRoute -> "map"
        is ThingsRoute -> "things"
        is ThingRoute -> "thing"
        is VoiceCheckRoute -> "voice_check"
        is ChapterRoute -> "chapter"
        else -> "other"
    }

    // --- Settings ---

    /** A provider id from our own list (an unknown one becomes "custom"). */
    fun provider(id: String) = if (Providers.get(id) != null) id else "custom"

    /** Which key was saved: the secret's fixed name from our code, never the key. */
    fun keyName(n: String) = pick(n, "gemini", "anthropic", "openai", "sarvam", "elevenlabs", "deepgram", "groq", "brave", "google_search")

    fun checkInKind(k: String) = pick(k, "morning", "midday", "evening", "quick")

    private fun pick(v: String, vararg allowed: String) = if (v in allowed) v else "other"

    /**
     * Once a day: how the app is set up, as enums, booleans and numbers only. Free-text
     * settings (name, about me, corrections, custom questions, server addresses, model names
     * you type) are never read here, except as "is it set".
     */
    fun settingsSnapshot(s: Settings): Map<String, Map<String, Any?>> = linkedMapOf(
        "settings_privacy" to mapOf(
            "privacy" to if (s.privacy == Privacy.LOCAL_ONLY) "local_only" else "cloud",
            "p_transcribe" to provider(s.provider(Feature.TRANSCRIBE)), "p_calls" to provider(s.provider(Feature.CALLS)),
            "p_notes" to provider(s.provider(Feature.NOTES)), "p_memory" to provider(s.provider(Feature.MEMORY)),
            "p_ask" to provider(s.provider(Feature.ASK)), "p_journal" to provider(s.provider(Feature.JOURNAL)),
            "ai_quality" to pick(s.aiQuality, "fast", "balanced", "best"),
        ),
        "settings_capture" to mapOf(
            "window_min" to s.windowMinutes, "quiet_hours" to (s.quietFrom >= 0 && s.quietTo >= 0 && s.quietFrom != s.quietTo),
            "keep_rec_days" to s.keepRecordingsDays, "rec_quality" to pick(s.recordingQuality, "small", "clear"),
            "import_calls" to s.importCalls, "boost_quiet" to s.boostQuietSpeech,
            "session_mode" to when (s.session.mode) { SessionMode.LIVE -> "live"; SessionMode.INTERVAL -> "interval"; SessionMode.WORDS -> "words"; SessionMode.CHARGING -> "charging"; SessionMode.MANUAL -> "manual" },
            "travel_auto" to s.travelAuto,
        ),
        "settings_speech" to mapOf(
            "lang_count" to s.speechLanguages.size, "lang_mode" to pick(s.languageMode, "switch", "single"),
            "speech_speed" to pick(s.speechSpeed, "accurate", "balanced", "fast"), "label_speakers" to s.labelSpeakers,
            "voice_sens" to pick(s.voiceSensitivity, "strict", "normal", "lenient"), "voice_learn" to s.voiceLearn,
            "recognise_people" to s.recognisePeople, "local_backend" to pick(s.localBackend, "auto", "cpu", "gpu"),
        ),
        "settings_review" to mapOf(
            "night_review" to s.nightReview, "night_charging" to s.nightNeedsCharging, "re_read_old" to s.reReadOld,
            "coach_on" to listOf(s.coachEnglish, s.coachBetter, s.coachNervous, s.coachSlips).count { it },
            "coach_strict" to pick(s.coachStrictness, "gentle", "normal", "strict"),
            "journal_style" to pick(s.journalStyle, "cinematic", "reflective", "simple"),
            "journal_voice" to pick(s.journalVoice, "first", "second"), "journal_len" to pick(s.journalLength, "short", "medium", "long"),
        ),
        "settings_tasks" to mapOf(
            "auto_accept" to s.autoAcceptConfident, "review_ai_tasks" to s.reviewAiTasks, "smart_reminders" to s.smartReminders,
            "undated_today" to s.undatedToToday, "semantic_search" to s.semanticSearch, "task_followups" to s.taskFollowUps,
            "auto_plans" to s.autoPlans, "organize_nightly" to s.organizeNightly,
        ),
        "settings_notify" to mapOf(
            "morning" to s.notifyMorning, "evening" to s.notifyEvening, "suggestions" to s.notifySuggestions,
            "status_detail" to pick(s.statusDetail, "minimal", "status", "full"), "nudges" to s.nudges, "nudges_per_day" to s.nudgesPerDay,
            "speak_nudges" to s.speakNudges, "quick_questions" to s.quickQuestions,
        ),
        "settings_read" to mapOf(
            "read_engine" to pick(s.readEngine, "open", "system"), "read_speed" to (Math.round(s.readSpeed * 4) / 4.0),
            "auto_read" to s.autoReadAnswers, "speaker_policy" to pick(s.speakerPolicy, "ask", "allow", "never"),
            "read_focus" to pick(s.readFocus, "auto", "duck", "pause"), "checkins_on" to s.checkIns.count { it.enabled },
            "checkin_speak" to s.checkInSpeak, "checkin_listen" to s.checkInAutoListen,
        ),
        "settings_power" to mapOf(
            "utilization" to s.utilization, "only_charging" to s.onlyWhileCharging, "threads_battery" to s.threadsBattery,
            "pause_saver" to s.pauseOnBatterySaver, "max_temp_c" to s.maxBatteryTempC, "min_battery" to s.minBatteryPct,
            "break_after_min" to s.breakAfterMin, "thermal_pause" to s.thermalPauseAt,
        ),
        "settings_web" to mapOf(
            "web_search" to pick(s.webSearch, "off", "ask", "auto"), "web_provider" to pick(s.webProvider, "duckduckgo", "wikipedia", "searxng", "brave", "google"),
            "web_read_pages" to s.webReadPages, "songs" to s.songs, "songs_online" to s.songsOnline, "ambient" to s.ambient,
            "things" to s.extractThings, "things_online" to s.thingsOnline,
        ),
        "settings_places" to mapOf(
            "location_history" to s.locationHistory, "location_every" to s.locationEveryMin, "places_online" to s.placesOnline,
            "map_tiles" to s.mapTiles, "map_style" to pick(s.mapStyle, "auto", "light", "dark", "terrain"), "travel_learn" to s.travelLearn,
            "travel_profile" to pick(s.travelProfile, "rest", "places", "record"), "events_from_talk" to s.eventsFromConversations,
        ),
        "settings_look" to mapOf(
            "theme" to pick(s.theme, "system", "light", "dark"), "dynamic_color" to s.dynamicColor, "today_hidden" to s.todayHidden.size,
            "newest_first" to s.transcriptNewestFirst, "phone_calendar" to s.showPhoneCalendar, "write_calendar" to s.writeToPhoneCalendar,
            "event_prep" to s.eventPrep, "about_me_set" to s.aboutMe.isNotBlank(),
        ),
    )
}
