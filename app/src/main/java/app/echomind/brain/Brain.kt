package app.echomind.brain

import android.content.Context
import android.util.Log
import app.echomind.ai.Ai
import app.echomind.ai.LlmRequest
import app.echomind.ai.Prompts
import app.echomind.ai.Schemas
import app.echomind.calls.CallImporter
import app.echomind.core.Feature
import app.echomind.core.SessionMode
import app.echomind.core.SettingsStore
import app.echomind.data.Db
import app.echomind.notify.Notifier
import app.echomind.util.bool
import app.echomind.util.objects
import app.echomind.util.str
import app.echomind.work.Governor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime

/**
 * The scheduler for everything after recording. One loop, one thing at a time
 * (speech and language models never run together on an 8 GB phone):
 *
 *   1. transcribe waiting audio (always first — audio is the only thing that can be lost);
 *   2. group lines into conversations;
 *   3. act on spoken commands ("remind me…") right away;
 *   4. run the AI over finished conversations, when the session mode says so;
 *   5. at the end of the day, write the journal and review memory.
 *
 * Every unit of work waits for the [Governor], so heat and battery rules apply
 * to all of it.
 */
class Brain(
    private val context: Context,
    private val db: Db,
    private val settings: SettingsStore,
    private val governor: Governor,
    private val transcriber: Transcriber,
    private val sessionizer: Sessionizer,
    private val enricher: Enricher,
    private val journal: Journal,
    private val tasks: Tasks,
    private val notes: Notes,
    private val calls: CallImporter,
    private val ai: Ai,
    private val notifier: Notifier,
    private val dayReview: DayReview,
    private val book: Book,
    private val nightlyExtras: suspend () -> Unit,
) {
    enum class Phase { IDLE, TRANSCRIBING, THINKING, JOURNAL, REVIEW, WAITING }

    data class State(
        val phase: Phase = Phase.IDLE,
        val detail: String = "",
        val waitingReason: String? = null,
        val aiBlocked: String? = null,
        val lastRunAt: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val running = Mutex()
    @Volatile private var forceAi = false
    private val prefs = context.getSharedPreferences("brain", Context.MODE_PRIVATE)

    /** Wake the loop: new audio, a settings change, the app opened. */
    fun poke() { wake.trySend(Unit) }

    /** "Process now": runs the AI regardless of the session mode. */
    fun processNow() { forceAi = true; poke() }

    @Volatile private var reviewDay: LocalDate? = null

    /** "Review now": the night review for [day], straight away (still under the heat and battery rules). */
    fun reviewNow(day: LocalDate) {
        reviewDay = day; forceAi = true; poke()
        // Without the listening service (e.g. the phone is locked), a background job runs it; the
        // watchdog keeps re-queuing it every 15 minutes until the review is done.
        if (!app.echomind.capture.CaptureService.running) app.echomind.work.ProcessWorker.enqueue(context)
    }

    /**
     * Runs until [keepGoing] returns false. Only one loop runs at a time: with
     * [wait] a second caller (the service) waits for the first (a worker) to
     * finish, otherwise it returns immediately.
     */
    suspend fun run(wait: Boolean = false, keepGoing: suspend () -> Boolean) {
        if (wait) running.lock() else if (!running.tryLock()) return
        try {
            settings.awaitLoaded()
            db.audio().resetWorking()
            db.conversations().resetWorking()
            while (keepGoing()) {
                val didWork = runCatching { cycle() }.onFailure { Log.w(TAG, "cycle failed", it) }.getOrDefault(false)
                if (!didWork) {
                    _state.value = _state.value.copy(phase = Phase.IDLE, detail = "")
                    withTimeoutOrNull(60_000) { wake.receive() }
                }
            }
        } finally {
            transcriber.idle()
            running.unlock()
        }
    }

    /** Runs until nothing is left to do (for a background worker). */
    suspend fun drain(deadline: Long) {
        run { System.currentTimeMillis() < deadline && hasWork() }
    }

    /** Work that can actually run now: due audio, or conversations the AI is set up and scheduled to read. */
    suspend fun hasWork(): Boolean =
        db.audio().dueCount() > 0 || (ai.ready(Feature.NOTES) && aiDue() && db.conversations().readyCount().first() > 0) || reviewDay != null ||
            (settings.value.nightReview && LocalTime.now().let { it.hour * 60 + it.minute }.let { it >= settings.value.nightReviewAt || it < 7 * 60 } &&
                (1..3).any { val d = LocalDate.now().minusDays(it.toLong()); dayReview.hasConversations(d) && !dayReview.isComplete(d) })

    private suspend fun cycle(): Boolean {
        // 1. Audio first.
        when (transcriber.step()) {
            Transcriber.Outcome.DONE -> {
                _state.value = _state.value.copy(phase = Phase.TRANSCRIBING)
                sessionizer.run()
                scanCommands()
                return true
            }
            Transcriber.Outcome.FAILED -> {
                // The item now waits out its backoff (retryAt); others continue.
                _state.value = _state.value.copy(phase = Phase.WAITING, waitingReason = transcriber.status.value.lastError)
                return true
            }
            Transcriber.Outcome.EMPTY -> {}
        }
        sessionizer.run()
        scanCommands()

        // 2. AI over finished conversations.
        val blocked = ai.readiness(Feature.NOTES)
        _state.value = _state.value.copy(aiBlocked = blocked)
        if (blocked == null && aiDue()) {
            val next = db.conversations().ready(1).firstOrNull()
            if (next != null) {
                governor.awaitTurn()
                _state.value = _state.value.copy(phase = Phase.THINKING, detail = next.title ?: Prompts.time(next.startAt), waitingReason = null)
                val began = System.currentTimeMillis()
                try {
                    val r = enricher.enrich(next)
                    if (r.tasks > 0) announceSuggestions()
                } catch (e: Exception) {
                    Log.w(TAG, "enrich ${next.id} failed", e)
                    enricher.markFailed(next, e)
                    _state.value = _state.value.copy(waitingReason = e.message)
                }
                governor.workDone(System.currentTimeMillis() - began)
                if (db.conversations().readyCount().first() == 0) {
                    forceAi = false
                    prefs.edit().putLong("lastAiRun", System.currentTimeMillis()).apply()
                    _state.value = _state.value.copy(lastRunAt = System.currentTimeMillis())
                }
                return true
            }
            forceAi = false
        }

        // 3. End of day, then the careful night review.
        if (endOfDay()) return true
        return nightReview()
    }

    /**
     * The slow, careful pass: from the chosen hour until 7 AM (or any time you ask for it),
     * after all conversations are read. One step per cycle.
     */
    private suspend fun nightReview(): Boolean {
        val s = settings.value
        if (!ai.ready(Feature.JOURNAL) || !ai.ready(Feature.NOTES)) return false
        if (db.audio().dueCount() > 0 || db.conversations().readyCount().first() > 0) return false
        reviewDay?.let { d ->
            governor.awaitTurn()
            _state.value = _state.value.copy(phase = Phase.REVIEW, detail = "Reviewing ${Prompts.day(journal.bounds(d).first)}")
            val began = System.currentTimeMillis()
            val more = runCatching { dayReview.step(d) }.onFailure { Log.w(TAG, "review step failed", it) }.getOrDefault(false)
            governor.workDone(System.currentTimeMillis() - began)
            if (!more) reviewDay = null
            return true
        }
        if (!s.nightReview) return false
        val minute = LocalTime.now().let { it.hour * 60 + it.minute }
        val inNight = minute >= s.nightReviewAt || minute < 7 * 60
        if (!inNight) return false
        if (s.nightNeedsCharging && !governor.read(record = false).charging) return false
        val today = LocalDate.now()
        // Earlier days first (oldest unfinished), then today once its evening has come.
        val days = (3 downTo 1).map { today.minusDays(it.toLong()) } + if (minute >= s.nightReviewAt) listOf(today) else emptyList()
        for (d in days) {
            if (!dayReview.hasConversations(d) || dayReview.isComplete(d)) continue
            governor.awaitTurn()
            _state.value = _state.value.copy(phase = Phase.REVIEW, detail = "Reviewing ${Prompts.day(journal.bounds(d).first)}")
            val began = System.currentTimeMillis()
            val did = runCatching { dayReview.step(d) }.onFailure { Log.w(TAG, "review step failed", it) }.getOrDefault(false)
            governor.workDone(System.currentTimeMillis() - began)
            if (did) return true
        }
        // Days are done: a finished week becomes a chapter of the book.
        governor.awaitTurn()
        _state.value = _state.value.copy(phase = Phase.REVIEW, detail = "Writing the book")
        val began = System.currentTimeMillis()
        val wrote = runCatching { book.step() }.onFailure { Log.w(TAG, "book step failed", it) }.getOrDefault(false)
        governor.workDone(System.currentTimeMillis() - began)
        return wrote
    }

    /** Whether the session mode wants the AI to run now. */
    private suspend fun aiDue(): Boolean {
        if (forceAi) return true
        val s = settings.value.session
        val last = prefs.getLong("lastAiRun", 0)
        val now = System.currentTimeMillis()
        return when (s.mode) {
            SessionMode.LIVE -> true
            SessionMode.INTERVAL -> now - last >= s.intervalMinutes * 60_000L
            SessionMode.WORDS -> db.conversations().readyWords() >= s.words || now - last > 6 * 3_600_000L
            SessionMode.CHARGING -> governor.read().charging
            SessionMode.MANUAL -> false
        }
    }

    /** Journal + memory review once the evening time has passed, and catch-up for missed days. */
    private suspend fun endOfDay(): Boolean {
        val s = settings.value
        val today = LocalDate.now()
        val nowMinute = LocalTime.now().let { it.hour * 60 + it.minute }
        if (!ai.ready(Feature.JOURNAL)) return false
        // Earlier days that never got their pass (phone off, no model yet).
        for (back in 3 downTo 1) {
            val d = today.minusDays(back.toLong())
            val e = db.days().get(d.toString())
            if (e?.generatedAt != null && e.memoryReviewedAt != null) continue
            if (!attemptAllowed(d)) continue
            val (from, to) = journal.bounds(d)
            if (db.conversations().inRangeOnce(from, to).none { it.state == "done" }) continue
            if (db.conversations().readyCount().first() > 0 && s.session.mode != SessionMode.MANUAL) return false
            governor.awaitTurn()
            _state.value = _state.value.copy(phase = Phase.JOURNAL, detail = "Journal for ${Prompts.day(from)}")
            markAttempt(d)
            journal.nightly(d)
            return true
        }
        if (nowMinute < s.eveningRecapAt) return false
        val e = db.days().get(today.toString())
        if (e?.generatedAt != null && e.memoryReviewedAt != null) return false
        if (!attemptAllowed(today)) return false
        if (db.audio().dueCount() > 0 || db.conversations().readyCount().first() > 0) return false
        governor.awaitTurn()
        _state.value = _state.value.copy(phase = Phase.JOURNAL, detail = "Writing today's journal")
        markAttempt(today)
        if (journal.nightly(today)) recap(today)
        tasks.expireOld()
        runCatching { tasks.mergeDuplicates() }
        nightlyExtras()
        return true
    }

    /** At most one journal attempt per day every two hours, so a failing model never loops. */
    private fun attemptAllowed(d: LocalDate) = System.currentTimeMillis() - prefs.getLong("journalAttempt:$d", 0) > 2 * 3_600_000L
    private fun markAttempt(d: LocalDate) = prefs.edit().putLong("journalAttempt:$d", System.currentTimeMillis()).apply()

    suspend fun recap(day: LocalDate) {
        if (!settings.value.notifyEvening) return
        val e = db.days().get(day.toString()) ?: return
        if (prefs.getString("recapSent", null) == day.toString()) return
        prefs.edit().putString("recapSent", day.toString()).apply()
        notifier.recap(e.headline ?: "Your day", e.diary?.take(300) ?: "", db.tasks().suggestedCount().first())
    }

    private suspend fun announceSuggestions() {
        if (!settings.value.notifySuggestions) return
        val hour = LocalTime.now().hour
        if (hour < 8 || hour >= 22) return
        val last = prefs.getLong("suggestNotified", 0)
        if (System.currentTimeMillis() - last < 2 * 3_600_000L) return
        val pending = db.tasks().observeByStatus("suggested").first()
        if (pending.isEmpty()) return
        prefs.edit().putLong("suggestNotified", System.currentTimeMillis()).apply()
        notifier.suggestions(pending.size, pending.take(4).map { it.title })
    }

    private val commandRe = Regex(
        "remind me|reminder|don'?t let me forget|note (this|that|down)|make a note|yaad (dila|dilana|rakhna)|note kar|याद दिला|नोट कर",
        RegexOption.IGNORE_CASE,
    )

    /** Spoken commands become tasks within a minute, without waiting for the conversation to end. */
    private suspend fun scanCommands() {
        if (!ai.ready(Feature.NOTES)) return // the cursor stays put; recent commands are caught once the AI is set up
        var last = prefs.getLong("lastCommandScan", -1)
        if (last < 0) { prefs.edit().putLong("lastCommandScan", db.utterances().maxId() ?: 0).apply(); return }
        while (true) {
            val batch = db.utterances().after(last, 200)
            if (batch.isEmpty()) return
            for (u in batch) {
                last = u.id
                if (u.speaker != null && u.speaker != "me") continue // includes lyrics ("Song"): a sung "remind me" is not a request
                // Old lines (an imported call, a long backlog) are left to the normal notes pass.
                if (System.currentTimeMillis() - u.startAt > 3 * 3_600_000L) continue
                if (!commandRe.containsMatchIn(u.text)) continue
                val context = db.utterances().range(u.startAt, u.startAt + 20_000).take(3).joinToString(" ") { it.text }
                val user = "Date: ${Prompts.day(u.startAt)}\nTime: ${Prompts.time(u.startAt)}\n\nThey said: \"$context\""
                val r = runCatching {
                    ai.json(LlmRequest(Feature.NOTES, "Your spoken request", Prompts.commandSystem(settings.value.notesLanguage), user, Schemas.command, maxTokens = 300))
                }.getOrNull() ?: continue
                val found = r.objects("tasks").map { t ->
                    Tasks.Found(t.str("title").orEmpty(), "me", t.str("due_text"), When.resolve(t.str("due_iso"), t.str("due_text"), u.startAt),
                        t.bool("is_reminder") != false, 0.95f, u.text, listOf(u.id),
                        remind = t.str("remind_iso")?.let { When.iso(it) }?.takeIf { it.hasTime })
                }
                tasks.addFound(found, u.conversationId ?: 0, u.startAt, origin = "voice")
                r.str("note")?.trim()?.takeIf { it.length > 12 && it.split(' ').size >= 3 }?.let { notes.apply(listOf(Notes.Update("Voice notes", listOf(it, "Said at ${Prompts.time(u.startAt)}"), listOf(u.id))), 0, "a voice note", u.startAt) }
            }
            prefs.edit().putLong("lastCommandScan", last).apply()
        }
    }

    suspend fun importCalls(): Int {
        val n = runCatching { calls.scan() }.getOrDefault(0)
        if (n > 0) poke()
        return n
    }

    companion object {
        private const val TAG = "EchoBrain"
    }
}
