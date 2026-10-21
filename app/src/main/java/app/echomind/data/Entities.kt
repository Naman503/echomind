package app.echomind.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * The data model. Transcript lines (utterances) are the ground truth and are
 * never rewritten by AI; everything else — conversations, facts, notes, tasks,
 * diary — is derived from them and points back to the lines it came from, so
 * every claim in the app can be traced to a moment someone said it.
 */

/** A recorded stretch of audio waiting to be transcribed (deleted once done). */
@Entity(tableName = "audio", indices = [Index("state"), Index("startedAt")])
data class AudioItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val voicedMs: Long,
    /** mic | call | import */
    val source: String,
    /** pending | working | done | failed | skipped */
    val state: String = "pending",
    /** Transcribed up to here; work resumes from this point after a restart. */
    val progressMs: Long = 0,
    val attempts: Int = 0,
    /** Not retried before this time (backoff after a failure). */
    val retryAt: Long = 0,
    val error: String? = null,
    val engine: String? = null,
    /** For calls: who, as the dialer named the recording. */
    val contact: String? = null,
    /** For imports: the MediaStore id, so a recording is never imported twice. */
    val mediaId: Long? = null,
)

/** Time when nothing was recorded, and why — shown honestly on the timeline. */
@Entity(tableName = "gaps", indices = [Index("startAt")])
data class Gap(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startAt: Long,
    val endAt: Long,
    /** paused | call | other_app | stopped | killed */
    val reason: String,
)

/** One line of transcript. */
@Entity(
    tableName = "utterances",
    indices = [Index("startAt"), Index("conversationId"), Index("audioId")],
)
data class Utterance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val audioId: Long? = null,
    val conversationId: Long? = null,
    val startAt: Long,
    val endAt: Long,
    val text: String,
    val lang: String? = null,
    /** "me", "S1", "S2"… or a name once known. */
    val speaker: String? = null,
    /** mic | call | import | typed */
    val source: String = "mic",
    val engine: String? = null,
    /** Transliterated, simplified copy of [text] for spelling-tolerant search (Rahul / Raahul / राहुल). */
    val searchText: String = "",
)

@Fts4(contentEntity = Utterance::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "utterances_fts")
data class UtteranceFts(val text: String, val searchText: String)

/** A conversation: utterances close together in time (or one phone call). */
@Entity(tableName = "conversations", indices = [Index("startAt"), Index("state")])
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startAt: Long,
    val endAt: Long,
    val source: String = "mic",
    val contact: String? = null,
    /** For a call or import: the recording it came from. */
    val audioId: Long? = null,
    val title: String? = null,
    val summary: String? = null,
    /** work | personal | family | health | finance | learning | errand | social | travel | media | other */
    val category: String? = null,
    /** -1..1 */
    val mood: Float? = null,
    /** JSON string list */
    val keyPoints: String? = null,
    /** JSON string list of names */
    val people: String? = null,
    val words: Int = 0,
    /** open | ready | working | done | failed | skipped */
    val state: String = "open",
    val attempts: Int = 0,
    val provider: String? = null,
    val error: String? = null,
    val processedAt: Long? = null,
    /** User said "this was TV / not me" — kept out of AI and stats. */
    val ignored: Boolean = false,
)

/** People, projects, places… the nouns of someone's life. */
@Entity(tableName = "entities", indices = [Index(value = ["kind", "name"]), Index("lastSeenAt")])
data class EntityRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** person | project | place | org | topic */
    val kind: String,
    val name: String,
    /** JSON string list of other names and spellings */
    val aliases: String = "[]",
    /** Who/what this is, kept current by the AI ("Priya — colleague on the Q3 budget"). */
    val summary: String = "",
    val importance: Int = 3,
    val pinned: Boolean = false,
    val mentions: Int = 1,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

/**
 * Long-term memory. Bi-temporal: a fact that stops being true is closed
 * ([validTo], [supersededBy]) rather than deleted, so "what did I think in
 * March" still has an answer.
 */
@Entity(tableName = "facts", indices = [Index("status"), Index("entityId"), Index("updatedAt")])
data class Fact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityId: Long? = null,
    val subject: String,
    val statement: String,
    /** fact | preference | routine | goal | open_loop | relationship | event */
    val kind: String = "fact",
    val importance: Int = 3,
    /** active | superseded | archived */
    val status: String = "active",
    val validFrom: Long,
    val validTo: Long? = null,
    val supersededBy: Long? = null,
    val conversationId: Long? = null,
    /** JSON list of utterance ids this came from */
    val sources: String = "[]",
    val pinned: Boolean = false,
    /** ai | user */
    val origin: String = "ai",
    val mentions: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedReason: String? = null,
)

/** A living note: AI keeps it current as new conversations touch its topic. */
@Entity(tableName = "notes", indices = [Index("updatedAt")])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Markdown: "## Section" headings with "- point" bullets. */
    val body: String,
    val entityId: Long? = null,
    val pinned: Boolean = false,
    /** When locked the AI never edits it. */
    val locked: Boolean = false,
    /** ai | user */
    val origin: String = "ai",
    val version: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
    /** Lines the user typed themselves (rendered in full contrast), JSON list. */
    val userLines: String = "[]",
    /** Palette index (0 = default surface). */
    @ColumnInfo(defaultValue = "0") val color: Int = 0,
    @ColumnInfo(defaultValue = "NULL") val folder: String? = null,
    /** JSON string list */
    @ColumnInfo(defaultValue = "'[]'") val tags: String = "[]",
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
)

@Fts4(contentEntity = Note::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "notes_fts")
data class NoteFts(val title: String, val body: String)

@Entity(tableName = "note_revisions", indices = [Index("noteId")])
data class NoteRevision(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val version: Int,
    val createdAt: Long,
    /** Human-readable why: "Rahul moved the launch to Friday". */
    val reason: String,
    val conversationId: Long? = null,
    val sources: String = "[]",
    val before: String,
    val after: String,
    /** ai | user */
    val by: String = "ai",
)

@Entity(tableName = "tasks", indices = [Index("status"), Index("dueAt"), Index("createdAt")])
data class TaskRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val detail: String? = null,
    /** me | other | unclear */
    val owner: String = "me",
    /** suggested | open | done | dismissed */
    val status: String = "suggested",
    val dueAt: Long? = null,
    val dueText: String? = null,
    /** When to notify; null = no reminder. */
    val remindAt: Long? = null,
    /** 1 = urgent … 4 = none */
    val priority: Int = 4,
    val confidence: Float = 0.7f,
    val conversationId: Long? = null,
    val sources: String = "[]",
    val quote: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val dismissReason: String? = null,
    /** ai | user | voice */
    val origin: String = "ai",
    /** The list it belongs to; null = Inbox. */
    @ColumnInfo(defaultValue = "NULL") val projectId: Long? = null,
    /** JSON string list */
    @ColumnInfo(defaultValue = "'[]'") val labels: String = "[]",
    /** Repeat rule, see [app.echomind.brain.Recurrence]; null = once. */
    @ColumnInfo(defaultValue = "NULL") val recurrence: String? = null,
    /** Set for a subtask. */
    @ColumnInfo(defaultValue = "NULL") val parentId: Long? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
)

/** A task list ("Work", "Home", "Shopping"). */
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int = 0,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val createdAt: Long,
)

/** One day: diary, summary, things to improve, metrics. */
@Entity(tableName = "days")
data class DayEntry(
    @PrimaryKey val day: String,
    val headline: String? = null,
    val diary: String? = null,
    val mood: Float? = null,
    /** JSON lists */
    val highlights: String = "[]",
    val wins: String = "[]",
    val looseEnds: String = "[]",
    val tomorrow: String = "[]",
    val improve: String = "[]",
    val metrics: String = "{}",
    val generatedAt: Long? = null,
    val provider: String? = null,
    val userEdited: Boolean = false,
    val memoryReviewedAt: Long? = null,
)

@Entity(tableName = "chat_threads", indices = [Index("updatedAt")])
data class ChatThread(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "chat_messages", indices = [Index("threadId")])
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    /** user | assistant */
    val role: String,
    val text: String,
    /** JSON list of Citation */
    val citations: String = "[]",
    val provider: String? = null,
    val createdAt: Long,
)

/** Semantic-search vectors, int8-quantised (256 bytes each). */
@Entity(tableName = "vectors", primaryKeys = ["kind", "ownerId"])
data class VectorRow(
    /** u = utterance chunk (owner = first utterance id), c = conversation, f = fact, n = note */
    val kind: String,
    val ownerId: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vector: ByteArray,
    val scale: Float,
    val model: String,
    val at: Long,
)

/** Every time data left the phone: when, what for, to whom, how much. */
@Entity(tableName = "egress", indices = [Index("at")])
data class Egress(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val feature: String,
    val provider: String,
    val bytes: Long,
    val detail: String,
    val ok: Boolean = true,
)

/** Small key/value store for things like the core memory profile. */
@Entity(tableName = "kv")
data class Kv(@PrimaryKey val key: String, val value: String, val updatedAt: Long)
