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
        "people" to S.arr(S.obj("name" to S.str(), "who" to S.str("who they are to the owner, from what was said"), "lines" to S.lines)),
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
                "lines" to S.lines,
            ),
        ),
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
