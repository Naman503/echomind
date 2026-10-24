package app.echomind.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioDao {
    @Insert suspend fun insert(item: AudioItem): Long
    @Update suspend fun update(item: AudioItem)
    @Query("SELECT * FROM audio WHERE id = :id") suspend fun get(id: Long): AudioItem?

    /** Oldest first, so conversations are built in the order life happened. */
    @Query("SELECT * FROM audio WHERE state IN ('pending','working') AND attempts < 6 AND retryAt <= :now ORDER BY startedAt LIMIT 1")
    suspend fun next(now: Long = System.currentTimeMillis()): AudioItem?

    @Query("SELECT COUNT(*) FROM audio WHERE state IN ('pending','working') AND attempts < 6 AND retryAt <= :now")
    suspend fun dueCount(now: Long = System.currentTimeMillis()): Int

    @Query("SELECT COUNT(*) FROM audio WHERE state IN ('pending','working') AND attempts < 6")
    fun pendingCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(durationMs - progressMs), 0) FROM audio WHERE state IN ('pending','working') AND attempts < 6")
    fun pendingMs(): Flow<Long>

    @Query("SELECT MIN(startedAt) FROM audio WHERE state IN ('pending','working') AND attempts < 6")
    suspend fun oldestPendingStart(): Long?

    @Query("SELECT MIN(startedAt) FROM audio WHERE source = 'mic' AND state IN ('pending','working') AND attempts < 6")
    suspend fun oldestPendingMicStart(): Long?

    @Query("UPDATE audio SET progressMs = :ms WHERE id = :id") suspend fun setProgress(id: Long, ms: Long)
    @Query("UPDATE audio SET state = 'pending' WHERE state = 'working'") suspend fun resetWorking()
    @Query("SELECT EXISTS(SELECT 1 FROM audio WHERE mediaId = :mediaId)") suspend fun hasMedia(mediaId: Long): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM audio WHERE path = :path)") suspend fun hasPath(path: String): Boolean
    @Query("SELECT path FROM audio WHERE state IN ('pending','working')") suspend fun livePaths(): List<String>
    @Query("SELECT * FROM audio WHERE state = 'done' AND endedAt < :before AND path != ''") suspend fun doneBefore(before: Long): List<AudioItem>
    @Query("SELECT * FROM audio WHERE (state != 'done' AND state != 'skipped' AND path != '') OR keptPath IS NOT NULL ORDER BY startedAt DESC LIMIT :n") fun observeRecordings(n: Int): Flow<List<AudioItem>>
    /** keepUntil NULL follows the retention setting (ended before :before); a set value is the user's own choice. */
    @Query("SELECT * FROM audio WHERE keptPath IS NOT NULL AND ((keepUntil IS NULL AND endedAt < :before) OR keepUntil < :now)") suspend fun expiredKeeps(before: Long, now: Long): List<AudioItem>
    @Query("SELECT * FROM audio WHERE id = :id") fun observe(id: Long): Flow<AudioItem?>
    @Query("SELECT * FROM audio WHERE keptPath IS NOT NULL AND startedAt <= :at AND endedAt >= :at LIMIT 1") suspend fun keptAt(at: Long): AudioItem?
    @Query("DELETE FROM audio WHERE state IN ('done','skipped') AND endedAt < :before AND keptPath IS NULL") suspend fun pruneDone(before: Long)
    @Query("SELECT * FROM audio WHERE state = 'failed' ORDER BY startedAt DESC LIMIT 50") fun failed(): Flow<List<AudioItem>>
    @Query("UPDATE audio SET state = 'pending', attempts = 0, retryAt = 0, error = NULL WHERE state = 'failed'") suspend fun retryFailed()
    @Query("SELECT * FROM audio WHERE startedAt < :to AND endedAt > :from") suspend fun overlapping(from: Long, to: Long): List<AudioItem>
    @Delete suspend fun delete(item: AudioItem)
}

@Dao
interface GapDao {
    @Insert suspend fun insert(gap: Gap): Long
    @Query("SELECT * FROM gaps WHERE endAt > :from AND startAt < :to ORDER BY startAt") fun inRange(from: Long, to: Long): Flow<List<Gap>>
    @Query("SELECT * FROM gaps WHERE endAt > :from AND startAt < :to ORDER BY startAt") suspend fun inRangeOnce(from: Long, to: Long): List<Gap>
}

data class SpeakerCount(val speaker: String?, val n: Int)

@Dao
interface UtteranceDao {
    @Insert suspend fun insertAll(items: List<Utterance>): List<Long>
    @Insert suspend fun insert(item: Utterance): Long
    @Update suspend fun update(item: Utterance)
    @Query("SELECT * FROM utterances WHERE id = :id") suspend fun get(id: Long): Utterance?
    @Query("SELECT * FROM utterances WHERE id IN (:ids) ORDER BY startAt") suspend fun byIds(ids: List<Long>): List<Utterance>
    @Query("SELECT conversationId FROM utterances WHERE audioId = :audioId AND conversationId IS NOT NULL LIMIT 1") suspend fun conversationOfAudio(audioId: Long): Long?
    @Query("SELECT * FROM utterances WHERE audioId = :audioId ORDER BY startAt") suspend fun forAudio(audioId: Long): List<Utterance>
    @Query("SELECT * FROM utterances WHERE conversationId = :id ORDER BY startAt") suspend fun forConversation(id: Long): List<Utterance>
    @Query("SELECT * FROM utterances WHERE conversationId = :id ORDER BY startAt") fun observeConversation(id: Long): Flow<List<Utterance>>
    @Query("SELECT * FROM utterances WHERE conversationId IS NULL ORDER BY startAt LIMIT 2000") suspend fun unassigned(): List<Utterance>
    @Query("UPDATE utterances SET conversationId = :conversationId WHERE id IN (:ids)") suspend fun assign(ids: List<Long>, conversationId: Long)
    @Query("SELECT * FROM utterances WHERE startAt >= :from AND startAt < :to ORDER BY startAt") suspend fun range(from: Long, to: Long): List<Utterance>
    @Query("SELECT * FROM utterances ORDER BY startAt DESC LIMIT :n") fun latest(n: Int): Flow<List<Utterance>>
    @Query("SELECT * FROM utterances WHERE id > :after ORDER BY id LIMIT :n") suspend fun after(after: Long, n: Int): List<Utterance>
    @Query("SELECT MAX(id) FROM utterances") suspend fun maxId(): Long?
    @Query("UPDATE utterances SET searchText = :key WHERE id = :id") suspend fun setSearchText(id: Long, key: String)
    @Query("SELECT * FROM utterances WHERE startAt >= :from AND startAt < :to AND conversationId = :conversationId ORDER BY startAt") suspend fun around(conversationId: Long, from: Long, to: Long): List<Utterance>

    /** Full-text search over the original words and their transliterated form. */
    @Query(
        """SELECT u.* FROM utterances u JOIN utterances_fts f ON u.id = f.rowid
           WHERE utterances_fts MATCH :query AND u.startAt >= :from AND u.startAt < :to
           ORDER BY u.startAt DESC LIMIT :limit""",
    )
    suspend fun search(query: String, from: Long = 0, to: Long = Long.MAX_VALUE, limit: Int = 60): List<Utterance>

    @Query("SELECT speaker, COUNT(*) AS n FROM utterances WHERE conversationId = :id GROUP BY speaker") suspend fun speakers(id: Long): List<SpeakerCount>
    @Query("UPDATE utterances SET speaker = :name WHERE conversationId = :conversationId AND speaker = :from") suspend fun renameSpeaker(conversationId: Long, from: String, name: String)
    @Query("DELETE FROM utterances WHERE startAt >= :from") suspend fun deleteSince(from: Long): Int
    @Query("DELETE FROM utterances WHERE conversationId = :id") suspend fun deleteForConversation(id: Long)
    @Query("SELECT COUNT(*) FROM utterances") fun count(): Flow<Int>
}

@Dao
interface ConversationDao {
    @Insert suspend fun insert(c: Conversation): Long
    @Update suspend fun update(c: Conversation)
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun get(id: Long): Conversation?
    @Query("SELECT * FROM conversations WHERE id = :id") fun observe(id: Long): Flow<Conversation?>
    @Query("SELECT * FROM conversations WHERE id IN (:ids)") suspend fun byIds(ids: List<Long>): List<Conversation>
    @Query("SELECT * FROM conversations WHERE startAt >= :from AND startAt < :to ORDER BY startAt DESC") fun inRange(from: Long, to: Long): Flow<List<Conversation>>
    @Query("SELECT * FROM conversations WHERE startAt >= :from AND startAt < :to AND ignored = 0 ORDER BY startAt") suspend fun inRangeOnce(from: Long, to: Long): List<Conversation>
    @Query("SELECT * FROM conversations ORDER BY startAt DESC LIMIT :n") fun recent(n: Int): Flow<List<Conversation>>

    /** The mic conversation still collecting lines, if any. */
    @Query("SELECT * FROM conversations WHERE state = 'open' AND source = :source ORDER BY endAt DESC LIMIT 1") suspend fun openFor(source: String): Conversation?
    @Query("SELECT * FROM conversations WHERE state = 'open'") suspend fun allOpen(): List<Conversation>
    @Query("SELECT * FROM conversations WHERE state IN ('ready','working') AND ignored = 0 AND attempts < 3 ORDER BY startAt LIMIT :n") suspend fun ready(n: Int): List<Conversation>
    @Query("SELECT COUNT(*) FROM conversations WHERE state IN ('ready','working') AND ignored = 0 AND attempts < 3") fun readyCount(): Flow<Int>
    @Query("SELECT COALESCE(SUM(words), 0) FROM conversations WHERE state IN ('ready','working') AND ignored = 0 AND attempts < 3") suspend fun readyWords(): Int
    /** After a crash: interrupted work goes back to the queue, unless it has used all its tries. */
    @Query("UPDATE conversations SET state = CASE WHEN attempts >= 3 THEN 'failed' ELSE 'ready' END WHERE state = 'working'") suspend fun resetWorking()
    @Query("UPDATE conversations SET attempts = 0, state = 'ready' WHERE state IN ('ready','working','failed') AND attempts >= 3 AND ignored = 0") suspend fun retryExhausted()
    @Query("SELECT * FROM conversations WHERE state = 'done' AND ignored = 0 AND startAt >= :from AND startAt < :to ORDER BY startAt DESC LIMIT :n")
    suspend fun doneBefore(from: Long, to: Long, n: Int): List<Conversation>
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface MemoryDao {
    @Insert suspend fun insertEntity(e: EntityRow): Long
    @Update suspend fun updateEntity(e: EntityRow)
    @Query("SELECT * FROM entities WHERE id = :id") suspend fun entity(id: Long): EntityRow?
    @Query("SELECT * FROM entities WHERE id = :id") fun observeEntity(id: Long): Flow<EntityRow?>
    @Query("SELECT * FROM entities ORDER BY pinned DESC, importance DESC, lastSeenAt DESC") fun observeEntities(): Flow<List<EntityRow>>
    @Query("SELECT * FROM entities") suspend fun allEntities(): List<EntityRow>
    @Query("SELECT * FROM entities WHERE kind = 'person' ORDER BY lastSeenAt DESC LIMIT :n") suspend fun recentPeople(n: Int): List<EntityRow>
    @Query("DELETE FROM entities WHERE id = :id") suspend fun deleteEntity(id: Long)

    @Insert suspend fun insertFact(f: Fact): Long
    @Update suspend fun updateFact(f: Fact)
    @Query("SELECT * FROM facts WHERE id = :id") suspend fun fact(id: Long): Fact?
    @Query("SELECT * FROM facts WHERE status = 'active' ORDER BY pinned DESC, importance DESC, updatedAt DESC") fun observeActiveFacts(): Flow<List<Fact>>
    @Query("SELECT * FROM facts WHERE status = 'active'") suspend fun activeFacts(): List<Fact>
    @Query("SELECT * FROM facts WHERE entityId = :entityId ORDER BY status = 'active' DESC, updatedAt DESC") fun observeFactsFor(entityId: Long): Flow<List<Fact>>
    @Query("SELECT * FROM facts WHERE status != 'active' ORDER BY updatedAt DESC LIMIT 40") fun observeFormerFacts(): Flow<List<Fact>>
    @Query("SELECT * FROM facts WHERE id IN (:ids)") suspend fun factsByIds(ids: List<Long>): List<Fact>
    @Query("DELETE FROM facts WHERE id = :id") suspend fun deleteFact(id: Long)

    @Query("SELECT value FROM kv WHERE `key` = :key") suspend fun kv(key: String): String?
    @Query("SELECT value FROM kv WHERE `key` = :key") fun observeKv(key: String): Flow<String?>
    @Upsert suspend fun putKv(kv: Kv)
}

@Dao
interface NoteDao {
    @Insert suspend fun insert(n: Note): Long
    @Update suspend fun update(n: Note)
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun get(id: Long): Note?
    @Query("SELECT * FROM notes WHERE id = :id") fun observe(id: Long): Flow<Note?>
    @Query("SELECT * FROM notes ORDER BY pinned DESC, updatedAt DESC") fun observeAll(): Flow<List<Note>>
    @Query("SELECT * FROM notes") suspend fun all(): List<Note>
    @Query("SELECT n.* FROM notes n JOIN notes_fts f ON n.id = f.rowid WHERE notes_fts MATCH :query LIMIT :limit") suspend fun search(query: String, limit: Int = 20): List<Note>
    @Query("DELETE FROM notes WHERE id = :id") suspend fun delete(id: Long)

    @Insert suspend fun insertRevision(r: NoteRevision): Long
    @Query("SELECT * FROM note_revisions WHERE noteId = :noteId ORDER BY version DESC") fun observeRevisions(noteId: Long): Flow<List<NoteRevision>>
    @Query("SELECT * FROM note_revisions WHERE conversationId = :conversationId") suspend fun revisionsFrom(conversationId: Long): List<NoteRevision>
}

@Dao
interface TaskDao {
    @Insert suspend fun insert(t: TaskRow): Long
    @Update suspend fun update(t: TaskRow)
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun get(id: Long): TaskRow?
    @Query("SELECT * FROM tasks WHERE status = :status ORDER BY CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt, createdAt DESC") fun observeByStatus(status: String): Flow<List<TaskRow>>
    /** Open top-level tasks (subtasks are shown inside their parent). */
    @Query("SELECT * FROM tasks WHERE status = 'open' AND parentId IS NULL ORDER BY priority, CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt, sortOrder, createdAt DESC")
    fun observeOpen(): Flow<List<TaskRow>>
    @Query("SELECT * FROM tasks WHERE parentId = :parentId ORDER BY sortOrder, createdAt") fun observeSubtasks(parentId: Long): Flow<List<TaskRow>>
    @Query("SELECT * FROM tasks WHERE parentId = :parentId") suspend fun subtasks(parentId: Long): List<TaskRow>
    @Query("SELECT parentId AS id, COUNT(*) AS total, SUM(CASE WHEN status = 'done' THEN 1 ELSE 0 END) AS done FROM tasks WHERE parentId IS NOT NULL AND status != 'dismissed' GROUP BY parentId")
    fun observeSubtaskCounts(): Flow<List<SubtaskCount>>
    @Query("SELECT * FROM tasks WHERE id = :id") fun observe(id: Long): Flow<TaskRow?>
    @Query("SELECT * FROM tasks WHERE status = 'open' AND remindAt IS NOT NULL ORDER BY remindAt") fun observeAllReminders(): Flow<List<TaskRow>>
    @Query("SELECT * FROM tasks WHERE status = 'done' AND completedAt >= :from") fun observeDoneSince(from: Long): Flow<List<TaskRow>>
    @Query("SELECT * FROM tasks WHERE status IN ('open','done') AND parentId IS NULL AND (title LIKE '%' || :q || '%' OR detail LIKE '%' || :q || '%') ORDER BY status = 'done', updatedAt DESC LIMIT 60")
    suspend fun search(q: String): List<TaskRow>
    @Query("SELECT * FROM tasks WHERE status = 'done' ORDER BY completedAt DESC LIMIT 100") fun observeDone(): Flow<List<TaskRow>>
    /** Tasks due, or finished, between two times — what a calendar month shows. */
    @Query("SELECT * FROM tasks WHERE status IN ('open','done') AND parentId IS NULL AND ((dueAt >= :from AND dueAt < :to) OR (completedAt >= :from AND completedAt < :to))")
    fun observeCalendar(from: Long, to: Long): Flow<List<TaskRow>>
    @Query("SELECT * FROM tasks WHERE status IN ('open','suggested')") suspend fun live(): List<TaskRow>
    @Query("SELECT * FROM tasks WHERE status = 'open' AND remindAt IS NOT NULL AND remindAt > :now ORDER BY remindAt") suspend fun upcomingReminders(now: Long): List<TaskRow>
    @Query("SELECT * FROM tasks WHERE status = 'open' AND remindAt IS NOT NULL AND remindAt > :now ORDER BY remindAt LIMIT :n") fun observeUpcoming(now: Long, n: Int): Flow<List<TaskRow>>
    @Query("SELECT COUNT(*) FROM tasks WHERE status = 'suggested'") fun suggestedCount(): Flow<Int>
    @Query("SELECT * FROM tasks WHERE status = 'open' AND doneSuggestedAt IS NOT NULL ORDER BY doneSuggestedAt DESC") fun observeLooksDone(): Flow<List<TaskRow>>
    @Query("SELECT * FROM tasks WHERE conversationId = :id") fun observeForConversation(id: Long): Flow<List<TaskRow>>
    @Query("SELECT * FROM tasks WHERE createdAt >= :from AND createdAt < :to") suspend fun createdIn(from: Long, to: Long): List<TaskRow>
    @Query("SELECT * FROM tasks WHERE completedAt >= :from AND completedAt < :to") suspend fun completedIn(from: Long, to: Long): List<TaskRow>
    @Query("UPDATE tasks SET status = 'dismissed', dismissReason = 'expired', updatedAt = :now WHERE status = 'suggested' AND createdAt < :before")
    suspend fun expireSuggestions(before: Long, now: Long): Int
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface ProjectDao {
    @Insert suspend fun insert(p: Project): Long
    @Update suspend fun update(p: Project)
    @Query("SELECT * FROM projects WHERE archived = 0 ORDER BY sortOrder, name") fun observe(): Flow<List<Project>>
    @Query("SELECT * FROM projects WHERE archived = 0 ORDER BY sortOrder, name") suspend fun all(): List<Project>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun get(id: Long): Project?
    @Query("SELECT * FROM projects WHERE lower(name) = lower(:name) LIMIT 1") suspend fun byName(name: String): Project?
    @Query("DELETE FROM projects WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE tasks SET projectId = NULL WHERE projectId = :id") suspend fun detachTasks(id: Long)
}

@Dao
interface NudgeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(n: Nudge): Long
    @Update suspend fun update(n: Nudge)
    @Query("SELECT * FROM nudges WHERE id = :id") suspend fun get(id: Long): Nudge?
    @Query("SELECT * FROM nudges WHERE state = 'pending' ORDER BY urgency DESC, createdAt") suspend fun pending(): List<Nudge>
    @Query("SELECT COUNT(*) FROM nudges WHERE shownAt >= :from") suspend fun shownSince(from: Long): Int
    @Query("SELECT MAX(shownAt) FROM nudges") suspend fun lastShownAt(): Long?
    @Query("SELECT * FROM nudges WHERE kind = :kind AND state IN ('acted','dismissed') ORDER BY createdAt DESC LIMIT 5") suspend fun recentOutcomes(kind: String): List<Nudge>
    @Query("SELECT * FROM nudges WHERE state IN ('pending','shown') AND createdAt >= :from ORDER BY urgency DESC, createdAt DESC LIMIT 5") fun observeOpen(from: Long): Flow<List<Nudge>>
    @Query("UPDATE nudges SET state = 'expired' WHERE state = 'pending' AND createdAt < :before") suspend fun expire(before: Long)
    @Query("SELECT * FROM nudges WHERE createdAt >= :from") suspend fun since(from: Long): List<Nudge>
}

@Dao
interface RelationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(r: Relation): Long
    @Update suspend fun update(r: Relation)
    @Query("SELECT * FROM relations WHERE fromId = :id OR toId = :id") fun observeFor(id: Long): Flow<List<Relation>>
    @Query("SELECT * FROM relations") fun observeAll(): Flow<List<Relation>>
    @Query("SELECT * FROM relations") suspend fun all(): List<Relation>
    @Query("DELETE FROM relations WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM relations WHERE fromId = :id OR toId = :id") suspend fun deleteFor(id: Long)
}

@Dao
interface EventDao {
    @Insert suspend fun insert(e: EventRow): Long
    @Update suspend fun update(e: EventRow)
    @Query("SELECT * FROM events WHERE id = :id") suspend fun get(id: Long): EventRow?
    @Query("SELECT * FROM events WHERE id = :id") fun observe(id: Long): Flow<EventRow?>
    @Query("SELECT * FROM events WHERE status != 'cancelled' AND startAt >= :from AND startAt < :to ORDER BY startAt") fun observeRange(from: Long, to: Long): Flow<List<EventRow>>
    @Query("SELECT * FROM events WHERE status != 'cancelled' AND startAt >= :from AND startAt < :to ORDER BY startAt") suspend fun range(from: Long, to: Long): List<EventRow>
    @Query("SELECT * FROM events WHERE status = 'suggested' ORDER BY startAt") fun observeSuggested(): Flow<List<EventRow>>
    @Query("SELECT * FROM events WHERE deviceId = :deviceId LIMIT 1") suspend fun byDevice(deviceId: Long): EventRow?
    @Query("DELETE FROM events WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface SoundDao {
    @Insert suspend fun insert(s: SoundRow): Long
    @Update suspend fun update(s: SoundRow)
    @Query("SELECT * FROM sounds WHERE id = :id") suspend fun get(id: Long): SoundRow?
    @Query("SELECT * FROM sounds WHERE kind = 'song' ORDER BY startAt DESC") fun observeSongs(): Flow<List<SoundRow>>
    @Query("SELECT * FROM sounds WHERE startAt < :to AND endAt > :from ORDER BY startAt") fun observeRange(from: Long, to: Long): Flow<List<SoundRow>>
    @Query("SELECT * FROM sounds WHERE startAt < :to AND endAt > :from ORDER BY startAt") suspend fun range(from: Long, to: Long): List<SoundRow>
    @Query("SELECT * FROM sounds WHERE kind = :kind ORDER BY endAt DESC LIMIT 1") suspend fun last(kind: String): SoundRow?
    @Query("SELECT * FROM sounds WHERE kind = 'song' AND lyrics IS NULL AND endAt < :before") suspend fun songsWithoutLyrics(before: Long): List<SoundRow>
    @Query("DELETE FROM sounds WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM sounds WHERE kind = 'ambient' AND endAt < :before") suspend fun pruneAmbient(before: Long)
}

@Dao
interface VoicePrintDao {
    @Insert suspend fun insert(v: VoicePrint): Long
    @Query("SELECT * FROM voiceprints") suspend fun all(): List<VoicePrint>
    @Query("SELECT * FROM voiceprints WHERE entityId = :id ORDER BY createdAt") suspend fun forEntity(id: Long): List<VoicePrint>
    @Query("SELECT COUNT(*) FROM voiceprints WHERE entityId = :id") fun observeCount(id: Long): Flow<Int>
    @Query("DELETE FROM voiceprints WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM voiceprints WHERE entityId = :id") suspend fun deleteFor(id: Long)
    @Query("UPDATE voiceprints SET entityId = :to WHERE entityId = :from") suspend fun move(from: Long, to: Long)
}

@Dao
interface CheckInDao {
    @Insert suspend fun insert(c: CheckIn): Long
    @Update suspend fun update(c: CheckIn)
    @Query("SELECT * FROM checkins WHERE id = :id") suspend fun get(id: Long): CheckIn?
    @Query("SELECT * FROM checkins ORDER BY startedAt DESC LIMIT :n") fun observeRecent(n: Int): Flow<List<CheckIn>>
    @Query("SELECT * FROM checkins WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt") suspend fun range(from: Long, to: Long): List<CheckIn>
}

data class SubtaskCount(val id: Long, val total: Int, val done: Int)

@Dao
interface DayDao {
    @Upsert suspend fun upsert(d: DayEntry)
    @Query("SELECT * FROM days WHERE day = :day") suspend fun get(day: String): DayEntry?
    @Query("SELECT * FROM days WHERE day = :day") fun observe(day: String): Flow<DayEntry?>
    @Query("SELECT * FROM days ORDER BY day DESC LIMIT :n") fun observeRecent(n: Int): Flow<List<DayEntry>>
    @Query("SELECT * FROM days WHERE day >= :from ORDER BY day") suspend fun since(from: String): List<DayEntry>
    @Query("SELECT * FROM days WHERE day >= :from AND day <= :to") fun observeRange(from: String, to: String): Flow<List<DayEntry>>
}

@Dao
interface ChatDao {
    @Insert suspend fun insertThread(t: ChatThread): Long
    @Update suspend fun updateThread(t: ChatThread)
    @Query("SELECT * FROM chat_threads ORDER BY updatedAt DESC") fun observeThreads(): Flow<List<ChatThread>>
    @Query("SELECT * FROM chat_threads WHERE id = :id") suspend fun thread(id: Long): ChatThread?
    @Insert suspend fun insertMessage(m: ChatMessage): Long
    @Update suspend fun updateMessage(m: ChatMessage)
    @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY createdAt") fun observeMessages(threadId: Long): Flow<List<ChatMessage>>
    @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY createdAt") suspend fun messages(threadId: Long): List<ChatMessage>
    @Query("DELETE FROM chat_threads WHERE id = :id") suspend fun deleteThread(id: Long)
    @Query("DELETE FROM chat_messages WHERE threadId = :id") suspend fun deleteMessages(id: Long)
}

@Dao
interface VectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(v: VectorRow)
    @Query("SELECT * FROM vectors WHERE kind = :kind") suspend fun all(kind: String): List<VectorRow>
    @Query("SELECT * FROM vectors WHERE kind = :kind AND ownerId IN (:ids)") suspend fun forOwners(kind: String, ids: List<Long>): List<VectorRow>
    @Query("SELECT EXISTS(SELECT 1 FROM vectors WHERE kind = :kind AND ownerId = :ownerId)") suspend fun has(kind: String, ownerId: Long): Boolean
    @Query("DELETE FROM vectors WHERE kind = :kind AND ownerId = :ownerId") suspend fun delete(kind: String, ownerId: Long)
    @Query("DELETE FROM vectors WHERE model != :model") suspend fun deleteOtherModels(model: String)
    @Query("SELECT COUNT(*) FROM vectors") fun count(): Flow<Int>
}

@Dao
interface EgressDao {
    @Insert suspend fun insert(e: Egress): Long
    @Query("SELECT * FROM egress ORDER BY at DESC LIMIT 300") fun observeRecent(): Flow<List<Egress>>
    @Query("SELECT COUNT(*) FROM egress WHERE at >= :from") fun countSince(from: Long): Flow<Int>
    @Query("SELECT COALESCE(SUM(bytes), 0) FROM egress WHERE at >= :from") fun bytesSince(from: Long): Flow<Long>
    @Query("DELETE FROM egress") suspend fun clear()
}

@Dao
interface AnalysisDao {
    @Upsert suspend fun upsert(a: Analysis)
    @Query("SELECT * FROM analyses WHERE kind = :kind AND ref = :ref") suspend fun get(kind: String, ref: String): Analysis?
    @Query("SELECT * FROM analyses WHERE kind = :kind AND ref = :ref") fun observe(kind: String, ref: String): Flow<Analysis?>
    @Query("SELECT * FROM analyses WHERE kind = :kind AND ref IN (:refs)") suspend fun many(kind: String, refs: List<String>): List<Analysis>
    @Query("SELECT * FROM analyses WHERE kind = :kind AND ref IN (:refs)") fun observeMany(kind: String, refs: List<String>): Flow<List<Analysis>>
    /** Day rows in a date range (refs are yyyy-MM-dd, so they sort as text). */
    @Query("SELECT * FROM analyses WHERE kind = 'day' AND ref >= :from AND ref <= :to ORDER BY ref") fun observeDays(from: String, to: String): Flow<List<Analysis>>
    @Query("DELETE FROM analyses WHERE kind = :kind AND ref = :ref") suspend fun delete(kind: String, ref: String)
    @Query("SELECT * FROM analyses WHERE kind = :kind ORDER BY ref") fun observeKind(kind: String): Flow<List<Analysis>>
    @Query("SELECT people FROM conversations WHERE people IS NOT NULL") suspend fun conversationPeople(): List<String>
    @Query("SELECT DISTINCT speaker FROM utterances WHERE speaker IS NOT NULL") suspend fun speakers(): List<String>
}
