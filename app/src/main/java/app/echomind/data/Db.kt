package app.echomind.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AudioItem::class, Gap::class, Utterance::class, UtteranceFts::class, Conversation::class,
        EntityRow::class, Fact::class, Note::class, NoteFts::class, NoteRevision::class, TaskRow::class,
        DayEntry::class, ChatThread::class, ChatMessage::class, VectorRow::class, Egress::class, Kv::class, Project::class, Nudge::class, Relation::class, EventRow::class,
    ],
    version = 4,
    exportSchema = true,
    // Additive changes only, so existing transcripts, notes and tasks are kept.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class Db : RoomDatabase() {
    abstract fun audio(): AudioDao
    abstract fun gaps(): GapDao
    abstract fun utterances(): UtteranceDao
    abstract fun conversations(): ConversationDao
    abstract fun memory(): MemoryDao
    abstract fun notes(): NoteDao
    abstract fun tasks(): TaskDao
    abstract fun days(): DayDao
    abstract fun chat(): ChatDao
    abstract fun vectors(): VectorDao
    abstract fun egress(): EgressDao
    abstract fun projects(): ProjectDao
    abstract fun nudges(): NudgeDao
    abstract fun relations(): RelationDao
    abstract fun events(): EventDao

    companion object {
        fun open(context: Context): Db =
            Room.databaseBuilder(context, Db::class.java, "echomind.db")
                // WAL lets the UI read while the pipeline writes.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
