package app.echomind.ai

import kotlinx.serialization.json.JsonObject

/**
 * Prompts and schemas for the careful pipeline. A small on-device model does far
 * better with one clear job per request than with one prompt that asks for
 * everything, so a conversation is read in several short passes:
 * understand → commitments → knowledge, and at night the speaking coach and
 * the day review.
 */
object Deep {
    private val extract get() = Schemas.extract["properties"] as JsonObject

    /** A schema made of some of the full extraction's fields, so every pass speaks the same format. */
    private fun pick(vararg keys: String, extra: List<Pair<String, JsonObject>> = emptyList()) =
        S.obj(*(keys.map { it to (extract[it] as JsonObject) } + extra).toTypedArray())

    private const val ASR = "The transcript comes from automatic speech recognition: it mishears words, merges voices and drops endings. Ignore garbled fragments; never treat a recognition error as something a person said."

    // ── 1. Understand ─────────────────────────────────────────────

    val understand = pick(
        "title", "summary", "category", "mood", "is_media", "key_points", "speakers",
        extra = listOf(
            "topics" to S.arr(S.obj("name" to S.str("2-4 words, specific: 'Cab fare to Bel Road', 'Server setup'"), "detail" to S.str("one line: what was said about it"), "lines" to S.lines), "What was actually discussed, most important first, max 5"),
            "decisions" to S.arr(S.str(), "Things decided or agreed, one line each; empty if none"),
        ),
    )

    fun understandSystem(lang: String) = """You read part of one conversation from a person's day and say what it was about.

${Prompts.languageRules(lang)}

Rules:
1. $ASR
2. Speaker "me" is the phone's owner; S1, S2… are other voices; "Song" lines are lyrics; "EchoMind" is the assistant.
3. Only what was said. Never invent people, topics or feelings. Do not reuse titles or topics from anything except this transcript.
4. title: specific, like a calendar entry, max 8 words. summary: 2-4 plain sentences on what happened and what was said, naming who said it when it matters.
5. key_points: 2-6 short points in your own words (never copy line numbers or raw lines).
6. topics: the real subjects, with one line each on what was said. decisions: only clear agreements or choices.
7. mood: -1 tense … 0 neutral … 1 warm. Recognition noise is not tension; use 0 unless the words clearly show a mood.
8. is_media: true only if it is mostly TV, video, music or an ad."""

    // ── 2. Commitments ────────────────────────────────────────────

    val commitments = pick("tasks", "events", "task_updates", "open_questions")

    fun commitmentsSystem(lang: String) = """You find promises, to-dos, plans and open requests in part of one conversation.

${Prompts.languageRules(lang)}

Rules:
1. $ASR
2. Speaker "me" is the phone's owner. A task is something someone commits to or asks for: "I'll send it tomorrow", "kal call karna hai", "remind me to…", "you have to book the cab". Talking about a topic is not a task. owner is "me" if the owner must do it, "other" if someone else must.
3. Title starts with a verb and is specific: "Book an auto to Bel Road", not "Travel".
4. Resolve dates against the date and time given. due_iso is empty if no time was said. remind_iso: when a reminder helps most — the time they said, else a sensible moment (an hour before a call, the evening before an early start, the morning of an errand), after the current time; empty for vague ideas and others' tasks.
5. events: things happening at a set time (meetings, trips, appointments, birthdays), not to-dos.
6. task_updates: only for the numbered existing tasks, and only with clear evidence they were done, progressed, stuck, moved or dropped. The existing tasks are reference only: never copy them as new tasks.
7. open_questions: only questions or requests another person put to the owner that were left unanswered. Never the owner's own questions or thinking aloud.
8. quote: the exact words from the transcript. Return empty lists when there is nothing — that is the right answer for most chat."""

    // ── 3. Knowledge ──────────────────────────────────────────────

    val knowledge = pick("people", "relations", "facts", "note_updates")

    fun knowledgeSystem(lang: String) = """You note down what is worth remembering from part of one conversation: who the people are, durable facts, and updates to ongoing topics.

${Prompts.languageRules(lang)}

Rules:
1. $ASR
2. people: only real people named or clearly referred to ("my boss Rahul", "Priya's husband"). Not the owner ("me"). who/role/org only from what was said.
3. facts: durable things worth knowing in future weeks — plans, decisions, preferences, health, money owed or paid, addresses, prices, deadlines, who someone is. Not small talk, not passing remarks.
4. note_updates: information about an ongoing topic (a project, a trip, a purchase, a health issue). Reuse an existing note title when it is the same topic.
5. Every item lists the transcript line numbers it comes from. Empty lists when nothing is worth keeping."""

    // ── 3b. Things (named things worth recalling) ────────────────

    val THING_KINDS = listOf("place", "movie", "show", "book", "music", "tech", "product", "company", "food", "event", "game", "other")

    val things = S.obj(
        "things" to S.arr(S.obj(
            "name" to S.str("the name as said in the line, with the right spelling if you are sure ('Panchayat', 'Kotlin', 'Hampi')"),
            "kind" to S.enum(*THING_KINDS.toTypedArray()),
            "line" to S.int("the L number where it is named"),
            "quote" to S.str("the exact words from that line that contain the name"),
            "intent" to S.enum("none", "want", "recommended", "done", "liked", "disliked"),
            "about" to S.str("a few words on what was said about it, from the transcript; empty if nothing"),
        ), "Named things a person may want to recall later; empty when none"),
    )

    fun thingsSystem(lang: String) = """You list the named things mentioned in part of one conversation, so the owner can recall them later.

${Prompts.languageRules(lang)}

Rules:
1. $ASR
2. A thing has a proper name: a place (city, area, restaurant, shop, landmark), a movie, a TV or web show, a book, a song/album/artist, a technology (app, tool, programming language, framework, gadget, AI model), a product or brand, a company, a dish or cuisine, an event, a game, a course. Never people (they are handled elsewhere), never common nouns ("the office", "a phone", "dinner").
3. kind: show for TV/web series, movie for films, tech for software, apps, languages and devices, company for businesses and organisations, other when none fits.
4. intent: want when someone wants to watch/read/visit/try/buy it; recommended when someone suggests it to another; done when someone already watched/read/visited/used it; liked or disliked when an opinion was given; else none.
5. name must be words that are really in that line (or their spelling in the other script: "पंचायत" is Panchayat). If a name is garbled beyond recognition, leave it out.
6. One entry per thing per part. Return an empty list when nothing was named — that is the right answer for most chat."""

    // ── 4. Speaking coach (owner only) ────────────────────────────

    val coach = S.obj(
        "english_fixes" to S.arr(S.obj(
            "line" to S.int("the L number"),
            "said" to S.str("what the owner said, copied"),
            "better" to S.str("the corrected English sentence"),
            "why" to S.str("the rule in under 10 words, never the sentence itself: 'past tense after yesterday', 'article missing'"),
        ), "Grammar and word-choice mistakes in English or in the English parts of Hinglish"),
        "better_ways" to S.arr(S.obj(
            "line" to S.int(),
            "said" to S.str("what the owner said, copied"),
            "better" to S.str("a clearer, kinder or more confident way to say it"),
            "why" to S.str("what improves, in a few words"),
        ), "Sentences that were understandable but could have been said much better"),
        "nervous" to S.arr(S.obj(
            "line" to S.int(),
            "quote" to S.str("the words, copied"),
            "sign" to S.str("what shows it: fillers, restarts, over-explaining, apologising, trailing off"),
            "tip" to S.str("one practical tip for next time"),
        ), "Moments where the owner sounded unsure or nervous"),
        "slips" to S.arr(S.obj(
            "line" to S.int(),
            "said" to S.str("what the owner said, copied"),
            "problem" to S.str("what was wrong: factually wrong, contradicted themselves, rude, over-promised, shared something private"),
            "fix" to S.str("what would have been better"),
        ), "Things the owner said that were wrong or that they may regret"),
        "confidence" to S.int("0-100: how confident and settled the owner sounded overall"),
        "clarity" to S.int("0-100: how clear and easy to follow the owner was"),
        "strengths" to S.arr(S.str(), "1-3 things the owner did well while speaking"),
    )

    fun coachSystem(strictness: String, max: Int, wants: Set<String>) = """You are a kind, honest speaking coach. You read part of a real conversation and coach only the person labelled "me" (the owner). Other speakers are context.

Rules:
1. $ASR Only correct something when you are sure the owner really said it that way — a clear grammar pattern, not a misheard word. Hindi is fine as Hindi: correct English only.
2. ${when (strictness) { "gentle" -> "Point out only clear, important mistakes."; "strict" -> "Point out every real mistake, even small ones."; else -> "Point out real mistakes that matter; skip tiny ones." }}
3. At most $max items in total across all lists, the most useful first. Copy the owner's words exactly in "said"/"quote".
4. better and fix are complete sentences the owner could say next time, natural and in the same tone.
5. Lines marked [hesitant] showed fillers or restarts; use them as hints for "nervous", not as proof.
${if ("english" !in wants) "6. Leave english_fixes empty.\n" else ""}${if ("better" !in wants) "7. Leave better_ways empty.\n" else ""}${if ("nervous" !in wants) "8. Leave nervous empty.\n" else ""}${if ("slips" !in wants) "9. Leave slips empty.\n" else ""}Empty lists are fine when the owner spoke well."""

    // ── 5. The day ───────────────────────────────────────────────

    val dayInsights = S.obj(
        "lessons" to S.arr(S.obj("title" to S.str("short"), "detail" to S.str("one or two sentences, practical")), "The 2-4 most useful lessons from today's speaking and dealings"),
        "patterns" to S.arr(S.str(), "Habits that showed up more than once today (good or bad)"),
        "people_notes" to S.arr(S.obj("name" to S.str(), "note" to S.str("one line: what happened with them today and anything to follow up"))),
        "topics" to S.arr(S.obj("name" to S.str(), "detail" to S.str("one line"))),
        "followups" to S.arr(S.str(), "Concrete things to follow up on, from today"),
        "best_moment" to S.str("the high point of the day, one line, or empty"),
        "hard_moment" to S.str("the hardest or most stressful moment, one line, or empty"),
        "communication" to S.int("0-100: how well the owner communicated today overall"),
    )

    fun daySystem(lang: String) = """You are a thoughtful personal assistant reviewing one person's day from summaries of their conversations, the people they spoke with, and their speaking coach notes. Write in $lang, in second person ("You…").
Rules:
1. Only use what is in the input. Never invent events or people.
2. Be specific and practical, like a good mentor: name the conversation or person.
3. Merge duplicate topics. Keep lists short and useful."""

    /** For days with many conversations: a few lines per part of the day, so the journal fits. */
    val dayPart = S.obj("summary" to S.str("4-8 sentences covering everything important in these conversations"), "highlights" to S.arr(S.str()))

    fun dayPartSystem(lang: String) = """You condense the summaries of a few conversations from one person's day into one short account, in $lang. Keep names, decisions, numbers and promises. Only what is in the input."""
}
