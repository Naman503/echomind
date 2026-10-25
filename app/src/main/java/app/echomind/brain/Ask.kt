package app.echomind.brain

import app.echomind.ai.Ai
import app.echomind.ai.LlmRequest
import app.echomind.ai.Prompts
import app.echomind.core.Feature
import app.echomind.core.SettingsStore
import app.echomind.data.ChatMessage
import app.echomind.data.ChatThread
import app.echomind.data.Db
import app.echomind.data.Utterance
import app.echomind.util.Js
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A numbered source an answer can cite; tapping it opens the exact moment. */
@Serializable
data class Citation(
    val n: Int,
    /** line | conversation | fact | note | task */
    val kind: String,
    val id: Long,
    val conversationId: Long? = null,
    val at: Long,
    val label: String,
    val quote: String,
    /** Web results: the page it came from. */
    val url: String? = null,
)

/**
 * "What did Rahul say about the invoice last week?" — finds the moments,
 * then has the model answer only from them, citing each one.
 *
 * Retrieval is hybrid and spelling-tolerant: full-text search on transliterated
 * phonetic keys (Rahul / Raahul / राहुल meet), meaning-based vectors when the
 * embedding model is installed, plus memory facts and notes — fused with
 * reciprocal-rank fusion and a mild preference for recent things.
 */
class Ask(private val db: Db, private val ai: Ai, private val settings: SettingsStore, private val embedder: Embedder, private val agenda: () -> Agenda, private val people: () -> People, private val web: () -> Web, private val plans: () -> Plans) {

    sealed interface Event {
        data class Searching(val text: String) : Event
        data class Sources(val citations: List<Citation>) : Event
        data class Delta(val text: String) : Event
        /** [outcome]: answered | not_ready (no AI set up: matching moments only). */
        data class Done(val messageId: Long, val threadId: Long, val outcome: String = "answered") : Event
        /** [category]: a fixed error kind for anonymous usage (see UsageMath.errorCategory). */
        data class Failed(val message: String, val category: String = "other") : Event
    }

    private val zone get() = ZoneId.systemDefault()

    /** A time window named in the question, if any. */
    fun timeRange(q: String, now: LocalDate = LocalDate.now()): Pair<Long, Long>? {
        val t = " " + q.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ") + " "
        fun day(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        fun span(a: LocalDate, b: LocalDate) = a.atStartOfDay(zone).toInstant().toEpochMilli() to b.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return when {
            Regex(" (today|aaj|आज) ").containsMatchIn(t) -> day(now)
            Regex(" (yesterday|kal|कल) ").containsMatchIn(t) -> day(now.minusDays(1))
            Regex("last week|pichle hafte|पिछले हफ्ते").containsMatchIn(t) -> span(now.minusWeeks(1).with(DayOfWeek.MONDAY), now.minusWeeks(1).with(DayOfWeek.SUNDAY))
            Regex("this week|is hafte|इस हफ्ते").containsMatchIn(t) -> span(now.with(DayOfWeek.MONDAY), now)
            Regex("last month|pichle mahine").containsMatchIn(t) -> span(now.minusMonths(1).withDayOfMonth(1), now.withDayOfMonth(1).minusDays(1))
            else -> {
                val days = mapOf("monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
                    "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY)
                days.entries.firstOrNull { t.contains(" ${it.key} ") || t.contains(" on ${it.key}") }?.let { day(now.with(TemporalAdjusters.previousOrSame(it.value))) }
            }
        }
    }

    private data class Hit(val key: String, val citation: Citation, val text: String)

    suspend fun retrieve(question: String, budgetChars: Int): List<Citation> {
        val range = timeRange(question)
        val (from, to) = range ?: (0L to Long.MAX_VALUE)
        val lists = ArrayList<List<Hit>>()

        // 1. Words (spelling-tolerant).
        TextNorm.ftsQuery(question)?.let { q ->
            val lines = runCatching { db.utterances().search(q, from, to, 60) }.getOrDefault(emptyList())
            lists.add(rankByTermHits(lines, question).map { lineHit(it) })
        }
        if (range != null) {
            // A question about a day with no matching words still deserves that day's conversations.
            lists.add(db.conversations().inRangeOnce(from, to).filter { it.state == "done" }.take(20).map { c ->
                Hit("c${c.id}", Citation(0, "conversation", c.id, c.id, c.startAt, "${Prompts.day(c.startAt).substringBefore(' ')} ${Prompts.time(c.startAt)} · ${c.title}", c.summary.orEmpty()), c.summary.orEmpty())
            })
        }

        // 2. Meaning.
        if (settings.value.semanticSearch && embedder.ready()) {
            embedder.query(question)?.let { qv ->
                val scored = ArrayList<Pair<Float, Hit>>()
                for (kind in listOf("u", "c", "f", "n")) {
                    for (v in db.vectors().all(kind)) {
                        val s = Vectors.cosine(qv, v.vector)
                        if (s > 0.35f) scored.add(s to Hit("$kind${v.ownerId}", Citation(0, kind, v.ownerId, null, 0, "", ""), ""))
                    }
                }
                val top = scored.sortedByDescending { it.first }.take(25).mapNotNull { (_, h) -> hydrate(h, from, to) }
                lists.add(top)
            }
        }

        // 3. Memory and notes.
        val terms = TextNorm.keyTerms(question).toSet()
        if (terms.isNotEmpty()) {
            val facts = db.memory().activeFacts().map { f -> f to TextNorm.keyTerms("${f.subject} ${f.statement}").count { it in terms } }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(8)
            lists.add(facts.map { (f, _) -> Hit("f${f.id}", Citation(0, "fact", f.id, f.conversationId, f.updatedAt, "Memory · ${f.subject}", f.statement), f.statement) })
            // Notes are indexed as written, so search them with the plain words, not phonetic keys.
            val words = Regex("[\\p{L}\\p{N}]+").findAll(question.lowercase()).map { it.value }.filter { it.length > 2 && it !in setOf("what", "did", "the", "about", "kya", "who", "when", "how", "say", "said") }.distinct().take(8).toList()
            val q = words.joinToString(" OR ") { "$it*" }
            val notes = if (q.isBlank()) emptyList() else runCatching { db.notes().search(q, 6) }.getOrDefault(emptyList())
            lists.add(notes.map { n -> Hit("n${n.id}", Citation(0, "note", n.id, null, n.updatedAt, "Note · ${n.title}", n.body.take(600)), n.body.take(600)) })
            if (Regex("task|todo|remind|karna|yaad|reminder|pending|deadline").containsMatchIn(question.lowercase())) {
                lists.add(db.tasks().live().take(15).map { t -> Hit("t${t.id}", Citation(0, "task", t.id, t.conversationId, t.createdAt, "Task", t.title + (t.dueText?.let { " ($it)" } ?: "")), t.title) })
            }
        }

        // Reciprocal-rank fusion with a gentle recency preference.
        val now = System.currentTimeMillis()
        val score = HashMap<String, Double>()
        val byKey = HashMap<String, Hit>()
        for (list in lists) list.forEachIndexed { rank, h ->
            byKey.putIfAbsent(h.key, h)
            score[h.key] = (score[h.key] ?: 0.0) + 1.0 / (60 + rank)
        }
        val ranked = byKey.values.sortedByDescending { h ->
            val ageDays = ((now - h.citation.at).coerceAtLeast(0)) / 86_400_000.0
            (score[h.key] ?: 0.0) * (1.0 + 0.3 * Math.exp(-ageDays / 30))
        }

        // Expand line hits into small windows of context and pack to budget.
        val out = ArrayList<Citation>()
        var used = 0
        val seenLines = HashSet<Long>()
        for (h in ranked) {
            if (out.size >= 14) break
            val c = if (h.citation.kind == "line") expand(h.citation, seenLines) ?: continue else h.citation
            val cost = c.quote.length + c.label.length + 12
            if (used + cost > budgetChars) continue
            used += cost
            out.add(c.copy(n = out.size + 1))
        }
        return out
    }

    private fun rankByTermHits(lines: List<Utterance>, q: String): List<Utterance> {
        val terms = TextNorm.keyTerms(q)
        return lines.sortedByDescending { u -> terms.count { u.searchText.contains(it) } * 10 - (System.currentTimeMillis() - u.startAt) / 86_400_000.0 / 30 }
    }

    private fun lineHit(u: Utterance) = if (u.speaker == Sessionizer.SONG)
        Hit("l${u.id}", Citation(0, "song", u.id, null, u.startAt, "♪ Song lyrics · ${Prompts.day(u.startAt).substringBefore(',')} ${Prompts.time(u.startAt)}", u.text), u.text)
    else Hit("l${u.id}", Citation(0, "line", u.id, u.conversationId, u.startAt, "", u.text), u.text)

    private suspend fun hydrate(h: Hit, from: Long, to: Long): Hit? {
        val id = h.citation.id
        return when (h.citation.kind) {
            "u" -> db.utterances().get(id)?.takeIf { it.startAt in from until to }?.let { lineHit(it) }
            "c" -> db.conversations().get(id)?.takeIf { it.startAt in from until to && it.state == "done" }?.let { c ->
                Hit("c$id", Citation(0, "conversation", id, id, c.startAt, "${Prompts.day(c.startAt).substringBefore(' ')} ${Prompts.time(c.startAt)} · ${c.title}", c.summary.orEmpty()), c.summary.orEmpty())
            }
            "f" -> db.memory().fact(id)?.takeIf { it.status == "active" }?.let { f -> Hit("f$id", Citation(0, "fact", id, f.conversationId, f.updatedAt, "Memory · ${f.subject}", f.statement), f.statement) }
            "n" -> db.notes().get(id)?.let { n -> Hit("n$id", Citation(0, "note", id, null, n.updatedAt, "Note · ${n.title}", n.body.take(600)), n.body) }
            else -> null
        }
    }

    /** A matched line plus two lines either side, from the same conversation. */
    private suspend fun expand(c: Citation, seen: MutableSet<Long>): Citation? {
        if (!seen.add(c.id)) return null
        val u = db.utterances().get(c.id) ?: return null
        val conv = u.conversationId?.let { db.conversations().get(it) }
        val window = if (conv != null) {
            val all = db.utterances().around(conv.id, u.startAt - 90_000, u.endAt + 90_000)
            val i = all.indexOfFirst { it.id == u.id }.coerceAtLeast(0)
            all.subList((i - 2).coerceAtLeast(0), (i + 3).coerceAtMost(all.size))
        } else listOf(u)
        window.forEach { seen.add(it.id) }
        val text = window.joinToString("\n") { "${it.speaker ?: "?"}: ${it.text}" }
        val label = "${Prompts.day(u.startAt).substringBefore(' ').take(3)} ${Prompts.day(u.startAt).split(' ').drop(1).take(2).joinToString(" ")}, ${Prompts.time(u.startAt)}" +
            (conv?.title?.let { " · $it" } ?: conv?.contact?.let { " · Call with $it" } ?: "")
        return c.copy(label = label, quote = text, conversationId = u.conversationId)
    }

    /** Answers in a thread (a new one when [threadId] is null), streaming. */
    /** The night reviews of recent days, for questions about the day, topics, people or speaking. */
    private suspend fun reviews(days: Int): String = buildString {
        val today = java.time.LocalDate.now()
        for (back in 0 until days) {
            val d = today.minusDays(back.toLong())
            val r = db.analyses().get("day", d.toString())?.let { runCatching { app.echomind.util.Js.json.decodeFromString(DayReviewData.serializer(), it.json) }.getOrNull() } ?: continue
            val entry = db.days().get(d.toString())
            if (isEmpty()) append("\nDay reviews (can be cited as [review]):\n")
            append("- ${Prompts.day(d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())}: ${entry?.headline.orEmpty()}\n")
            if (r.people.isNotEmpty()) append("  People: ${r.people.take(10).joinToString(", ") { it.name }}${if (r.unnamedVoices > 0) " (+${r.unnamedVoices} unnamed voices)" else ""}\n")
            if (r.topics.isNotEmpty()) append("  Topics: ${r.topics.take(10).joinToString("; ") { it.name + if (it.detail.isNotBlank()) " — " + it.detail.take(100) else "" }}\n")
            if (r.lessons.isNotEmpty()) append("  Lessons: ${r.lessons.joinToString("; ") { it.title }}\n")
            append("  Speaking: ${r.speech.words} words, ${r.speech.wpm} wpm, ${"%.1f".format(r.speech.fillerPer100)} fillers/100 words, ${r.coachPoints} coach points\n")
            val convs = db.conversations().inRangeOnce(d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), d.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
            val fixes = db.analyses().many("conv", convs.map { it.id.toString() }).mapNotNull { runCatching { app.echomind.util.Js.json.decodeFromString(ConvAnalysis.serializer(), it.json) }.getOrNull()?.coach }
                .flatMap { it.englishFixes }.take(5)
            if (fixes.isNotEmpty()) append("  English fixes: ${fixes.joinToString("; ") { "\"${it.said.take(80)}\" → \"${it.better.take(80)}\"" }}\n")
        }
    }

    fun answer(threadId: Long?, question: String, useWeb: Boolean = false): Flow<Event> = flow {
        val now = System.currentTimeMillis()
        val thread = threadId?.let { db.chat().thread(it) } ?: ChatThread(title = question.take(60), createdAt = now, updatedAt = now).let { it.copy(id = db.chat().insertThread(it)) }
        val history = db.chat().messages(thread.id).takeLast(4)
        db.chat().insertMessage(ChatMessage(threadId = thread.id, role = "user", text = question, createdAt = now))

        val reason = ai.readiness(Feature.ASK)
        val llm = ai.llm(Feature.ASK)
        emit(Event.Searching("Searching your memory…"))
        val mine = retrieve(question, if (llm.local) 3_500 else 18_000)
        // The web, only when asked for (or set to automatic and memory had nothing): general queries, your OK first.
        val wantWeb = web().enabled() && (useWeb || (settings.value.webSearch == "auto" && mine.isEmpty()))
        val found = if (wantWeb && reason == null) {
            emit(Event.Searching("Looking it up on ${web().providerLabel()}…"))
            web().research(question, "To answer: “${question.take(80)}”")
        } else emptyList()
        val sources = mine + found.mapIndexed { i, r ->
            Citation(mine.size + i + 1, "web", 0, null, now, "Web · ${runCatching { java.net.URI(r.url).host.removePrefix("www.") }.getOrDefault(r.source)} · ${r.title.take(80)}",
                r.snippet.take(if (llm.local) 700 else 2_500), r.url)
        }
        emit(Event.Sources(sources))

        if (reason != null) {
            val text = if (sources.isEmpty()) "I couldn't find anything about that yet. ($reason to get written answers.)"
            else "Here are the moments that match. ($reason to get a written answer.)"
            val id = db.chat().insertMessage(ChatMessage(threadId = thread.id, role = "assistant", text = text, citations = encode(sources), createdAt = System.currentTimeMillis()))
            emit(Event.Delta(text)); emit(Event.Done(id, thread.id, "not_ready")); return@flow
        }

        val schedule = Regex("\\b(when|calendar|meeting|appointment|schedule|week|today|tomorrow|kal|aaj|monday|tuesday|wednesday|thursday|friday|saturday|sunday|next|upcoming|free|busy)\\b", RegexOption.IGNORE_CASE)
        val calendar = if (schedule.containsMatchIn(question)) runCatching { agenda().range(now - 3 * 86_400_000L, now + 14 * 86_400_000L) }.getOrDefault(emptyList()).filter { it.status == "confirmed" } else emptyList()
        // Who's who for anyone the question names, so "who is Ankit?" works even when no excerpt says it outright.
        val q = question.lowercase()
        val named = db.memory().allEntities().filter { it.kind == "person" && it.name.length > 2 && Regex("\\b${Regex.escape(it.name.lowercase().substringBefore(' '))}\\b").containsMatchIn(q) }.take(4)
        val user = buildString {
            append("Now: ").append(Prompts.day(now)).append(", ").append(Prompts.time(now)).append("\n")
            if (calendar.isNotEmpty()) {
                append("\nCalendar (can be cited as [cal]):\n")
                calendar.take(30).forEach { append("- ${Prompts.day(it.startAt)}${if (it.allDay) "" else " ${Prompts.time(it.startAt)}"}: ${it.title}${it.location?.let { l -> " at $l" } ?: ""}\n") }
            }
            if (Regex("\\b(plan|focus|priorit|missing|forget|should i|this week|this month|goal)", RegexOption.IGNORE_CASE).containsMatchIn(question)) {
                for (k in listOf("day", "week", "month")) plans().get(k)?.let { p ->
                    append("\n${if (k == "day") "Today's" else "This $k's"} plan (can be cited as [plan]): ${p.focus}. Priorities: ${p.priorities.joinToString("; ") { it.title }}. Might be missing: ${p.missing.joinToString("; ") { it.title }}\n")
                }
            }
            if (Regex("\\b(where|place|went|go|visit|travel|trip|weather|rain|hot|cold|kahan|kaha)", RegexOption.IGNORE_CASE).containsMatchIn(question)) {
                val today = java.time.LocalDate.now()
                val lines = (0..6).mapNotNull { b -> val d = today.minusDays(b.toLong()); runCatching { app.echomind.App.graph.places.dayNotes(d) }.getOrNull()?.let { "- ${Prompts.day(d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())}: ${it.replace("\n", " · ")}" } }
                if (lines.isNotEmpty()) { append("\nPlaces and weather (can be cited as [places]):\n"); lines.forEach { append(it).append('\n') } }
            }
            if (Regex("\\b(day|yesterday|today|week|topic|discuss|talk|english|grammar|mistake|wrong|nervous|coach|speak|said|people|met|lesson|learn|improve|kal|aaj)", RegexOption.IGNORE_CASE).containsMatchIn(question)) {
                append(reviews(if (Regex("\\bweek\\b", RegexOption.IGNORE_CASE).containsMatchIn(question)) 7 else 3))
            }
            // Named things: the ones the question names, or recent ones of the kind it asks about.
            val things = db.memory().allEntities().filter { it.kind in Things.KEYS }
            val qk = Things.key(question)
            val kindWords = mapOf("movie" to "movie|film|picture", "show" to "show|series|serial|season|episode|web series|netflix|prime|hotstar", "book" to "book|novel|read",
                "music" to "song|album|music|singer|band|gaana", "tech" to "app|tool|tech|software|framework|library|language|laptop|phone|gadget|model|ai\\b",
                "place" to "place|restaurant|cafe|where|visit|trip|jagah", "food" to "food|dish|eat|khana|restaurant", "org" to "company|startup|brand|firm", "game" to "game", "product" to "product|buy|bought|brand")
            val askedKinds = kindWords.filter { (_, w) -> Regex("\\b($w)", RegexOption.IGNORE_CASE).containsMatchIn(question) }.keys
            val hitThings = (things.filter { t -> (listOf(t.name) + Things.aliases(t)).any { n -> Things.key(n).let { k -> k.length >= 3 && qk.contains(k) } } } +
                things.filter { it.kind in askedKinds }.sortedByDescending { it.lastSeenAt }.take(15)).distinctBy { it.id }.take(20)
            if (hitThings.isNotEmpty()) {
                append("\nThings mentioned in conversations (can be cited as [things]):\n")
                for (t in hitThings) {
                    val m = db.mentions().inRange(t.firstSeenAt, t.lastSeenAt + 1).filter { it.entityId == t.id }.takeLast(2)
                    append("- ${t.name} (${Things.Kind.of(t.kind)?.label ?: t.kind}${Things.statusLabel(t.kind, t.status)?.let { ", $it" } ?: ""})${t.detail?.let { ": $it" } ?: ""}")
                    m.forEach { append(" | ${Prompts.day(it.at)}, ${if (it.speaker == "me") "you" else it.speaker ?: "someone"}: \"${it.quote.take(140)}\"") }
                    append('\n')
                }
            }
            if (named.isNotEmpty()) {
                append("\nPeople mentioned (can be cited as [people]):\n")
                for (p in named) append("- ${p.name}: ${people().clue(p)}${p.story?.let { " — ${it.take(300)}" } ?: ""}\n")
            }
            if (history.isNotEmpty()) {
                append("\nEarlier in this chat:\n")
                history.forEach { append(if (it.role == "user") "Q: " else "A: ").append(it.text.take(300)).append('\n') }
            }
            append("\nQuestion: ").append(question).append("\n\nExcerpts:\n")
            if (sources.isEmpty()) append("(nothing matched)\n")
            sources.forEach { append("[${it.n}] ${it.label}\n${it.quote}\n\n") }
        }
        val sb = StringBuilder()
        try {
            val req = ai.resolve(LlmRequest(Feature.ASK, "Answering your question", Prompts.askSystem(settings.value.notesLanguage), user, maxTokens = 600, temperature = 0.2))
            ai.track(req) { m -> m.stream(req).collect { sb.append(it); emit(Event.Delta(it)) } }
        } catch (e: Exception) {
            emit(Event.Failed(e.message ?: "The answer failed", app.echomind.telemetry.UsageMath.errorCategory(e)))
            if (sb.isEmpty()) sb.append("Sorry — I couldn't answer that just now (${e.message?.take(120)}).")
        }
        val cited = Regex("\\[(\\d+)]").findAll(sb).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
        val keep = sources.filter { it.n in cited }.ifEmpty { sources.take(3) }
        val id = db.chat().insertMessage(ChatMessage(threadId = thread.id, role = "assistant", text = sb.toString().trim(), citations = encode(keep), provider = llm.id, createdAt = System.currentTimeMillis()))
        db.chat().updateThread(thread.copy(updatedAt = System.currentTimeMillis()))
        emit(Event.Done(id, thread.id))
    }

    /** Records a command and the assistant's reply in a chat thread (creating one if needed); returns the thread id. */
    suspend fun record(threadId: Long?, question: String, reply: String): Long {
        val now = System.currentTimeMillis()
        val thread = threadId?.let { db.chat().thread(it) } ?: ChatThread(title = question.take(60), createdAt = now, updatedAt = now).let { it.copy(id = db.chat().insertThread(it)) }
        db.chat().insertMessage(ChatMessage(threadId = thread.id, role = "user", text = question, createdAt = now))
        db.chat().insertMessage(ChatMessage(threadId = thread.id, role = "assistant", text = reply, provider = "assistant", createdAt = now + 1))
        db.chat().updateThread(thread.copy(updatedAt = now))
        return thread.id
    }

    private fun encode(c: List<Citation>) = Js.json.encodeToString(ListSerializer(Citation.serializer()), c)

    companion object {
        fun decode(json: String): List<Citation> = runCatching { Js.json.decodeFromString(ListSerializer(Citation.serializer()), json) }.getOrDefault(emptyList())
    }
}
