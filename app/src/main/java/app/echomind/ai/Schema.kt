package app.echomind.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A tiny JSON-schema builder. Every object closes its properties and requires
 * all of them, which every provider's strict mode accepts and which keeps
 * small on-device models from inventing fields.
 */
object S {
    fun str(description: String? = null) = node("string", description)
    fun num(description: String? = null) = node("number", description)
    fun int(description: String? = null) = node("integer", description)
    fun bool(description: String? = null) = node("boolean", description)
    fun enum(vararg values: String, description: String? = null) = JsonObject(
        buildMap {
            put("type", JsonPrimitive("string"))
            put("enum", JsonArray(values.map { JsonPrimitive(it) }))
            description?.let { put("description", JsonPrimitive(it)) }
        },
    )
    fun arr(items: JsonObject, description: String? = null) = JsonObject(
        buildMap {
            put("type", JsonPrimitive("array"))
            put("items", items)
            description?.let { put("description", JsonPrimitive(it)) }
        },
    )
    fun obj(vararg props: Pair<String, JsonObject>, description: String? = null) = JsonObject(
        buildMap<String, JsonElement> {
            put("type", JsonPrimitive("object"))
            put("properties", JsonObject(props.toMap()))
            put("required", JsonArray(props.map { JsonPrimitive(it.first) }))
            put("additionalProperties", JsonPrimitive(false))
            description?.let { put("description", JsonPrimitive(it)) }
        },
    )
    private fun node(type: String, description: String?) = JsonObject(
        buildMap {
            put("type", JsonPrimitive(type))
            description?.let { put("description", JsonPrimitive(it)) }
        },
    )

    val lines = arr(int(), "Transcript line numbers (the N in LN) this comes from")
}

object Schemas {
    /** Everything worth keeping from one conversation (or one part of a long one). */
    val extract = S.obj(
        "title" to S.str("Specific, like a calendar entry, max 8 words"),
        "summary" to S.str("2-4 plain sentences"),
        "category" to S.enum("work", "personal", "family", "health", "finance", "learning", "errand", "social", "travel", "media", "other"),
        "mood" to S.num("-1 tense/negative … 0 neutral … 1 warm/positive"),
        "is_media" to S.bool("true if this is mostly TV, video, music, an ad or a call centre recording rather than real people talking"),
        "key_points" to S.arr(S.str()),
        "speakers" to S.arr(S.obj("label" to S.str("S1, S2…"), "name" to S.str("their name, only if clearly said"))),
        "people" to S.arr(
            S.obj(
                "name" to S.str("as said; full name if known"),
                "who" to S.str("who they are to the owner, from what was said ('my manager', 'Priya's husband', 'the plumber'), or empty"),
                "role" to S.str("what they do: job, title or role ('sales lead', 'doctor', 'student'), or empty"),
                "org" to S.str("where they work or study, or the group they belong to, or empty"),
                "circle" to S.enum("family", "work", "friends", "neighbourhood", "services", "other", description = "which part of the owner's life they belong to"),
                "lines" to S.lines,
            ),
        ),
        "relations" to S.arr(
            S.obj(
                "from" to S.str("a person's name"),
                "type" to S.str("how they relate, short: 'manager of', 'wife of', 'works at', 'brother of', 'client of', 'friend of'"),
                "to" to S.str("another person, or an organisation"),
                "lines" to S.lines,
            ),
            "Connections between people (or a person and an organisation) that were stated or are obvious from what was said",
        ),
        "events" to S.arr(
            S.obj(
                "title" to S.str("Like a calendar entry: 'Dentist', 'Budget review with Priya', 'Riya's birthday'"),
                "start_iso" to S.str("yyyy-MM-ddTHH:mm, or yyyy-MM-dd for an all-day event"),
                "end_iso" to S.str("same format, or empty"),
                "location" to S.str("or empty"),
                "people" to S.arr(S.str()),
                "confidence" to S.num("0-1: how clearly this was a real, scheduled plan"),
                "quote" to S.str("the exact words, copied from the transcript"),
                "lines" to S.lines,
            ),
            "Appointments, meetings, trips, birthdays and other things happening at a set date — not to-dos",
        ),
        "facts" to S.arr(
            S.obj(
                "subject" to S.str("a person, project, place or 'me'"),
                "statement" to S.str("one durable fact worth remembering later"),
                "kind" to S.enum("fact", "preference", "routine", "goal", "open_loop", "relationship", "event"),
                "importance" to S.int("1 trivia … 5 central to their life"),
                "lines" to S.lines,
            ),
        ),
        "tasks" to S.arr(
            S.obj(
                "title" to S.str("Starts with a verb: 'Call the plumber'"),
                "owner" to S.enum("me", "other", "unclear"),
                "due_text" to S.str("When, as said ('kal shaam', 'Friday 10am'), or empty"),
                "due_iso" to S.str("That time as yyyy-MM-ddTHH:mm, or yyyy-MM-dd for a day, or empty"),
                "is_reminder" to S.bool("true if the owner asked to be reminded or it must happen at a set time"),
                "confidence" to S.num("0-1: how clearly this was a real commitment"),
                "quote" to S.str("the exact words, copied from the transcript"),
                "lines" to S.lines,
            ),
        ),
        "open_questions" to S.arr(
            S.obj(
                "question" to S.str("A question or request someone put to the owner that was not answered or settled here"),
                "asked_by" to S.str("Who asked, if known"),
            ),
        ),
        "note_updates" to S.arr(
            S.obj(
                "topic" to S.str("An existing note title from the list, or a short new topic title"),
                "points" to S.arr(S.str(), "New or changed information, one point each"),
                "folder" to S.str("For a new topic: the folder path it belongs in, reusing an existing folder if one fits ('Work/Eazeebox', 'Health', 'Home'), or empty"),
                "tags" to S.arr(S.str(), "1-3 short lowercase tags"),
                "lines" to S.lines,
            ),
        ),
    )

    /** A second look at what was extracted, for the "best" quality setting. */
    val review = S.obj(
        "keep_tasks" to S.arr(S.int(), "Numbers of the tasks (T#) the transcript clearly supports"),
        "keep_events" to S.arr(S.int(), "Numbers of the events (E#) that are real scheduled plans"),
        "keep_facts" to S.arr(S.int(), "Numbers of the facts (F#) that are correct and worth remembering"),
    )

    val merge = S.obj("title" to S.str(), "summary" to S.str(), "key_points" to S.arr(S.str()))

    /** Reconciling candidate facts with what memory already holds (Mem0-style). */
    val reconcile = S.obj(
        "decisions" to S.arr(
            S.obj(
                "candidate" to S.int("candidate number"),
                "op" to S.enum("add", "update", "replace", "skip"),
                "target" to S.int("existing memory number for update/replace, else 0"),
                "statement" to S.str("the final wording to store (merged for update), or empty for skip"),
            ),
        ),
    )

    /** Small edits to a living note. */
    val notePatch = S.obj(
        "reason" to S.str("One line: what changed and why, e.g. 'Launch moved to Friday'"),
        "edits" to S.arr(
            S.obj(
                "op" to S.enum("add", "update", "remove"),
                "section" to S.str("Section heading to add under (existing or new)"),
                "find" to S.str("For update/remove: words from the existing bullet"),
                "text" to S.str("The new bullet text (empty for remove)"),
            ),
        ),
    )

    val journal = S.obj(
        "headline" to S.str("The day in under 10 words"),
        "diary" to S.str("A short diary entry, 120-220 words"),
        "mood" to S.num("-1 … 1"),
        "highlights" to S.arr(S.str()),
        "wins" to S.arr(S.str()),
        "loose_ends" to S.arr(S.str()),
        "tomorrow" to S.arr(S.str()),
        "improve" to S.arr(S.obj("observation" to S.str(), "suggestion" to S.str())),
        "productivity" to S.int("0-100: how productive the day was for the owner, judged from what they got done, decided and moved forward — not from how busy it was"),
        "productive_why" to S.str("One short line explaining that score"),
    )

    /** A person's page: who they are, in a few lines the owner can read before meeting them. */
    val person = S.obj(
        "summary" to S.str("One line: who they are to the owner"),
        "story" to S.str("3-6 sentences: who they are, what they do, how the owner knows them, what has been going on between them, anything to remember next time"),
        "role" to S.str("or empty"),
        "org" to S.str("or empty"),
        "circle" to S.enum("family", "work", "friends", "neighbourhood", "services", "other"),
        "relation_to_me" to S.str("or empty"),
        "remember" to S.arr(S.str(), "Up to 4 details worth remembering when you next see them (kids' names, preferences, promises)"),
    )

    /** Where each note belongs. */
    val organize = S.obj(
        "notes" to S.arr(
            S.obj(
                "n" to S.int("note number"),
                "folder" to S.str("Folder path with '/' for sub-folders, e.g. 'Work/Eazeebox/Clients'"),
                "tags" to S.arr(S.str(), "1-4 short lowercase tags"),
            ),
        ),
        "people" to S.arr(
            S.obj(
                "n" to S.int("person number"),
                "circle" to S.enum("family", "work", "friends", "neighbourhood", "services", "other"),
                "org" to S.str("or empty"),
            ),
        ),
    )

    /** Things the owner asked the assistant to change in the app. */
    val actions = S.obj(
        "actions" to S.arr(
            S.obj(
                "op" to S.enum("add_task", "complete_task", "add_event", "create_note", "move_note", "tag_note", "update_person", "relate", "remember", "organize"),
                "target" to S.str("The existing note, task or person this acts on, as named, or empty"),
                "title" to S.str("New task/event/note title, or empty"),
                "text" to S.str("Body text, a fact to remember, or details, or empty"),
                "folder" to S.str("Folder path for a note, or empty"),
                "tags" to S.arr(S.str()),
                "when_iso" to S.str("yyyy-MM-ddTHH:mm or yyyy-MM-dd, or empty"),
                "end_iso" to S.str("or empty"),
                "role" to S.str("or empty"),
                "org" to S.str("or empty"),
                "circle" to S.str("family | work | friends | neighbourhood | services | other, or empty"),
                "relation" to S.str("For relate: 'manager of', 'wife of'…; for update_person: who they are to the owner"),
                "other" to S.str("For relate: the other person or organisation"),
            ),
        ),
        "reply" to S.str("One short sentence telling the owner what was done, or why nothing could be done"),
    )

    val profile = S.obj(
        "profile" to S.str("Who this person is, in compact bullet lines, max ~1200 characters"),
        "people" to S.arr(S.obj("name" to S.str(), "summary" to S.str("one line: who they are and what is going on"))),
        "archive" to S.arr(S.int("memory numbers that are finished or no longer true")),
    )

    val command = S.obj(
        "tasks" to S.arr(
            S.obj(
                "title" to S.str(),
                "due_text" to S.str(),
                "due_iso" to S.str(),
                "is_reminder" to S.bool(),
            ),
        ),
        "note" to S.str("Text the owner asked to note down, or empty"),
    )
}
