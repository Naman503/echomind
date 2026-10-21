package app.echomind.ai

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Prompts. Written as hard rules because small on-device models treat hedged
 * wording as optional. Stable parts (rules, memory) come first and changing
 * parts (date, transcript) last, so the runtime can reuse its cache of the
 * prompt's beginning between runs.
 */
object Prompts {
    private val zone get() = ZoneId.systemDefault()
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
    fun day(at: Long): String = Instant.ofEpochMilli(at).atZone(zone).format(dayFmt)
    fun time(at: Long): String = Instant.ofEpochMilli(at).atZone(zone).format(timeFmt)
    fun iso(at: Long): String = Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

    private fun languageRules(lang: String) = """Language:
- The transcript may be English, Hindi (Devanagari or Latin letters), Hinglish, or any language, switching mid-sentence. Understand it as it is.
- Write every title, summary, point, fact, task and note in $lang.
- "quote" fields are the exception: copy the transcript's exact words in their original language and script."""

    fun extractSystem(lang: String) = """You are the memory of one person's day. You read an automatic transcript of real-life audio recorded on their phone and pull out what is worth keeping.

${languageRules(lang)}

Hard rules:
1. Only use what was actually said. Never guess, infer motives or embellish.
2. The transcript is imperfect speech recognition: mishearings, half sentences, several voices, background TV. Ignore fragments you cannot understand.
3. Lines are numbered L1, L2… Every person, fact, task and note update must list the line numbers it comes from in "lines".
4. Lines from speaker "Song" are lyrics being sung or played: never take tasks, facts or note updates from them. Speaker "me" is the phone's owner. S1, S2… are other voices. If a speaker's name is clearly used ("Rahul, can you…" answered by S1), put it in "speakers".
5. A task is something someone commits to or asks for ("I'll send it tomorrow", "kal call karna hai", "remind me to…"). Discussion of a topic is not a task. owner is "me" if the owner commits or says "remind me", "other" if someone else must do it.
6. Resolve dates against the date and time given. due_iso is empty if no time was said. Never invent a time.
7. Facts are durable things worth knowing in future weeks: who someone is, plans, decisions, preferences, health advice, money owed, addresses, deadlines. Not small talk.
8. note_updates collect information about an ongoing topic (a project, a trip, a health issue, a purchase). Reuse an existing note title from the list when it is the same topic.
9. If it is TV, a video, a song, an ad or a scam call, set is_media to true and leave tasks, facts and note_updates empty.
10. The title is specific, like a calendar entry ("Budget call with Priya", "Planning Sunday lunch"). Never use the words transcript, audio, recording or conversation.
11. people: role is what they do, org is where. Only what was said or is obvious ("my boss Rahul" → who "my boss", circle work). relations connect two named people, or a person and an organisation ("Priya from Eazeebox" → Priya works at Eazeebox).
12. events are things happening at a set time (meetings, appointments, trips, birthdays, deadlines on a date), not things to do. A plan stated as fact ("we're meeting Friday at 3") is an event; "I should call him" is a task.
13. open_questions: questions or requests someone put directly to the owner ("can you send me the file?", "kab tak hoga?") that were left unanswered or need a follow-up. Not rhetorical questions, not questions the owner asked.
14. If nothing is worth keeping, return empty lists and a short honest title."""

    data class Context(
        val profile: String = "",
        val people: String = "",
        val openTasks: List<String> = emptyList(),
        val noteTitles: List<String> = emptyList(),
        val earlier: String = "",
    )

    fun extractUser(ctx: Context, startAt: Long, endAt: Long, source: String, contact: String?, lines: String, part: Pair<Int, Int>? = null) = buildString {
        if (ctx.profile.isNotBlank()) append("About the owner (background only, do not repeat as new facts):\n").append(ctx.profile).append("\n\n")
        if (ctx.people.isNotBlank()) append("People and things they already know:\n").append(ctx.people).append("\n\n")
        if (ctx.noteTitles.isNotEmpty()) append("Existing notes: ").append(ctx.noteTitles.joinToString(" | ")).append("\n\n")
        if (ctx.openTasks.isNotEmpty()) append("Already on their task list (do not repeat): ").append(ctx.openTasks.joinToString(" | ")).append("\n\n")
        append("Date: ").append(day(startAt)).append('\n')
        append("Time: ").append(time(startAt)).append(" to ").append(time(endAt)).append('\n')
        if (source == "call") append("This is a phone call").append(contact?.let { " with $it" } ?: "").append(". \"me\" is the owner; the other side is ").append(contact ?: "the caller").append(".\n")
        part?.let { (i, n) -> if (n > 1) append("This is part $i of $n of one long conversation.\n") }
        if (ctx.earlier.isNotBlank()) append("\nEarlier today:\n").append(ctx.earlier).append('\n')
        append("\nTranscript:\n").append(lines)
    }

    fun reviewSystem() = """You check another assistant's work. It read an automatic transcript of a real conversation and listed tasks, events and facts. Keep an item only if the transcript clearly supports it:
- a task is a real commitment or request, with the right owner;
- an event is a real plan at a set time;
- a fact is correct, durable and worth knowing in future weeks.
Drop duplicates, small talk, misheard fragments, TV or video content, and anything guessed. Return the numbers to keep."""

    fun mergeSystem(lang: String) = """You combine the summaries of consecutive parts of one long conversation into one title, summary and key points, in $lang. Use only what the parts say. Title: max 8 words, like a calendar entry."""

    fun reconcileSystem(lang: String) = """You maintain a person's long-term memory. For each candidate fact from today, compare it with the existing memories shown under it and decide:
- add: it is new information.
- update: it adds detail to an existing memory about the same thing; write the merged statement.
- replace: it contradicts or supersedes an existing memory (a plan changed, a status moved on); write the new statement. The old one is kept as history.
- skip: it is already known, trivial, or not durable.
Write statements in $lang, as one clear sentence each. Use the numbers exactly as given."""

    fun notePatchSystem(lang: String) = """You keep a living note up to date. You get the note as it is now and new information from a conversation. Make the smallest edits that bring the note up to date:
- add a bullet for new information (under an existing section heading, or a new short heading);
- update a bullet when the information changed (give words from the old bullet in "find");
- remove a bullet only when it is clearly no longer true or done.
Never rewrite bullets that did not change. Never add anything that is not in the new information. Write in $lang. Keep bullets short."""

    fun journalSystem(lang: String) = """You write a private end-of-day journal for one person from summaries of their own day, in $lang.
Rules:
1. Second person ("You…"), warm but plain. No pep talk, no therapy voice, no emoji.
2. Only use what is in the input. Never invent events, feelings or people.
3. loose_ends: things raised but not resolved. tomorrow: concrete next steps drawn from today.
4. improve: up to 3 gentle, specific observations with a practical suggestion each, grounded in the day (e.g. many interruptions in a work call, a promise slipping, skipped meals mentioned). Observations, not judgements. Empty if nothing stands out.
5. A quiet day gets a short entry; do not pad.
6. productivity judges progress, not busyness: things finished, decisions made, real work moved forward, promises kept. A restful day off is not "unproductive" if rest was the plan — say so in productive_why."""

    fun profileSystem(lang: String) = """You maintain the short profile an assistant keeps about one person: who they are, their family and key people, work, ongoing projects and goals, routines and preferences. Update it with today's new memories. Keep what is still true, drop what is finished, stay under ~1200 characters, compact bullet lines, in $lang.
Also write a one-line summary for each listed person (who they are to the owner and what is currently going on), and list memory numbers that are clearly finished or no longer true."""

    fun askSystem(lang: String) = """You are the owner's second brain. Answer their question using ONLY the numbered excerpts from their own recorded conversations, notes and memory.
Rules:
1. Cite every claim with the excerpt number in square brackets, like [2] or [1][4]. Calendar and people lists, when given, are cited as [cal] and [people].
2. If the excerpts do not contain the answer, say plainly that you could not find it, and mention what came closest.
3. Mention when something was said ("on Tuesday around 4 pm") when it helps.
4. Never invent. Never speculate about what someone meant or felt.
5. Be brief: 1-4 sentences, or a short list when the question asks for one.
6. Answer in the language of the question; default to $lang.
7. Excerpts labelled "Web" come from the internet, not from the owner's life: use them for general facts, and say when an answer comes from the web."""

    fun personSystem(lang: String) = """You help someone who finds names hard to remember. From what their conversations and memory say about one person, write that person's page in $lang: who they are, what they do, how the owner knows them, and what to remember next time. Use only the input; leave a field empty rather than guess."""

    fun organizeSystem(lang: String) = """You keep a person's notes and contacts tidy. Put each note in a folder (use '/' for sub-folders, at most 3 levels), reusing existing folders when they fit and creating a clear new one only when needed — like a well-kept drive: 'Work/<company>/<project>', 'Health', 'Home/Renovation', 'Finance', 'Family', 'Ideas', 'Learning'. Give 1-4 short lowercase tags. For each person, choose their circle and organisation from what is known. Folder and tag names in $lang."""

    fun actionsSystem(lang: String) = """The owner told their assistant to change something in the app. Turn the instruction into actions from the allowed list. Rules:
- Only do what was asked. Never invent targets: "target" must be how the owner named an existing note, task or person.
- move_note: put a note in a folder path ('Work/Clients'). tag_note: add tags. create_note: title, text, optional folder and tags.
- update_person: set what a person does (role), where (org), circle, and who they are to the owner (relation). text = anything else to remember about them.
- relate: target is one person, other is a person or organisation, relation is how they connect ('manager of', 'works at').
- add_event: a meeting or appointment at a time (when_iso; end_iso if said). add_task: a to-do, with when_iso if a time was said.
- remember: a fact about the owner or someone (target = who it's about, text = the fact).
- organize: tidy all notes into folders and tags.
- Resolve dates against the given date and time. Write in $lang.
If the instruction is really a question, return no actions."""

    fun webPlanSystem() = """You decide what to search on the web to meet a need. Write 1-3 short, general search queries, like a careful researcher would type into a search engine.
Privacy rules: never include anyone's name, phone number, address, company-internal details or anything personal — describe the thing generally instead ("gift ideas for a mother who likes gardening", not "gift for Sunita"). If the need is about a song, search the exact lyric fragment in quotes plus the word lyrics."""

    fun commandSystem(lang: String) = """The owner just said something to their assistant out loud. Extract any reminder or task they asked for, and any text they asked to note down. Resolve times against the given date and time. Write in $lang. Empty lists if it was not a request."""
}
