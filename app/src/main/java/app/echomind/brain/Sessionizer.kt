package app.echomind.brain

import app.echomind.core.SettingsStore
import app.echomind.data.Conversation
import app.echomind.data.Db

/**
 * Groups transcript lines into conversations: lines closer together than the
 * silence gap belong together, very long stretches are split, and each phone
 * call is its own conversation. A conversation is "ready" for the AI once
 * nothing more can join it — the gap has passed and no recording that could
 * extend it is still waiting to be transcribed.
 */
class Sessionizer(private val db: Db, private val settings: SettingsStore) {

    suspend fun run(now: Long = System.currentTimeMillis()) {
        val s = settings.value.session
        val gapMs = s.gapMinutes * 60_000L
        val maxMs = s.maxConversationMinutes * 60_000L
        val open = HashMap<String, Conversation>() // key: "mic" or "call:<audioId>"
        for (c in db.conversations().allOpen()) open[keyOf(c)] = c

        // Lines are grouped strictly in time order: a line newer than audio still waiting to be
        // transcribed (a typed note, a shared text) waits, so it can't split a conversation early.
        val oldestPendingStart = db.audio().oldestPendingMicStart()
        val lines = db.utterances().unassigned().filter { it.source == "call" || it.source == "import" || oldestPendingStart == null || it.startAt < oldestPendingStart }
        var batchIds = ArrayList<Long>()
        var batchConv: Conversation? = null

        suspend fun flushBatch() {
            val c = batchConv ?: return
            if (batchIds.isNotEmpty()) db.utterances().assign(batchIds, c.id)
            db.conversations().update(c)
            batchIds = ArrayList()
        }

        for (u in lines) {
            val key = if (u.source == "call" || u.source == "import") "${u.source}:${u.audioId}" else "mic"
            var conv = open[key]
            val joins = conv != null && (key != "mic" || (u.startAt - conv.endAt <= gapMs && u.startAt - conv.startAt <= maxMs))
            if (!joins) {
                if (batchConv != null) flushBatch()
                conv?.let { close(it); open.remove(key) }
                val contact = if (u.source != "mic") u.audioId?.let { db.audio().get(it)?.contact } else null
                val fresh = Conversation(startAt = u.startAt, endAt = u.endAt, source = u.source, contact = contact, audioId = if (u.source == "mic") null else u.audioId)
                conv = fresh.copy(id = db.conversations().insert(fresh))
                open[key] = conv
            } else if (batchConv?.id != conv!!.id) {
                flushBatch()
            }
            conv = conv.copy(endAt = maxOf(conv.endAt, u.endAt), words = conv.words + TextNorm.words(u.text))
            open[key] = conv
            batchConv = conv
            batchIds.add(u.id)
        }
        flushBatch()

        // Close what can no longer grow.
        val oldestPending = oldestPendingStart
        for ((key, c) in open) {
            val stillComing = if (key == "mic") {
                oldestPending != null && oldestPending <= c.endAt + gapMs
            } else {
                val audioId = key.substringAfter(':').toLongOrNull()
                audioId != null && db.audio().get(audioId)?.state in setOf("pending", "working")
            }
            val quietLongEnough = key != "mic" || now - c.endAt > gapMs
            if (!stillComing && quietLongEnough) close(c)
        }
    }

    private fun keyOf(c: Conversation): String {
        if (c.source != "call" && c.source != "import") return "mic"
        return "${c.source}:${c.audioId}"
    }

    private suspend fun close(c: Conversation) {
        val fresh = db.conversations().get(c.id) ?: c
        val state = if (fresh.words >= MIN_WORDS) "ready" else "skipped"
        db.conversations().update(fresh.copy(state = state))
    }

    companion object {
        /** Fewer words than this is a fragment ("haan", "ok"), not a conversation. */
        const val MIN_WORDS = 12
    }
}
