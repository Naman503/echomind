package app.echomind.brain

import app.echomind.data.Db
import app.echomind.data.EntityRow
import app.echomind.data.VoicePrint
import app.echomind.stt.VoiceId

/**
 * Voices of the people you talk to. When a speaker in a conversation gets a
 * name — you named them, the AI heard their name, or it's a phone call with a
 * contact — their voice is remembered; in later conversations their lines are
 * labelled with their name straight away.
 */
class VoicePrints(private val db: Db) {
    private fun dequant(v: VoicePrint) = VoiceId.normalize(Vectors.dequantize(v.vector, v.scale))

    /** The average voice of [lineIds] (lines need a stored voice sample). */
    suspend fun centroid(lineIds: List<Long>): Pair<FloatArray, Int>? {
        val prints = lineIds.chunked(500).flatMap { db.vectors().forOwners("s", it) }.map { VoiceId.normalize(Vectors.dequantize(it.vector, it.scale)) }
        if (prints.size < 2) return null
        val avg = FloatArray(prints[0].size)
        for (p in prints) for (i in avg.indices) avg[i] += p[i] / prints.size
        return VoiceId.normalize(avg) to prints.size
    }

    /** Keeps up to 8 distinct samples of a person's voice. */
    suspend fun learn(entityId: Long, print: FloatArray, samples: Int, conversationId: Long?) {
        val existing = db.voiceprints().forEntity(entityId)
        if (existing.any { VoiceId.cosine(dequant(it), print) > 0.93f }) return
        val (bytes, scale) = Vectors.quantize(print)
        db.voiceprints().insert(VoicePrint(entityId = entityId, vector = bytes, scale = scale, samples = samples, conversationId = conversationId, createdAt = System.currentTimeMillis()))
        if (existing.size >= 8) existing.minByOrNull { it.samples }?.let { db.voiceprints().delete(it.id) }
    }

    /** Whose voice this is, if it's clearly one known person (and clearly not the next closest). */
    suspend fun match(print: FloatArray): Pair<EntityRow, Float>? {
        val best = db.voiceprints().all().groupBy { it.entityId }
            .map { (id, ps) -> id to ps.maxOf { VoiceId.cosine(dequant(it), print) } }
            .sortedByDescending { it.second }
        val first = best.firstOrNull() ?: return null
        val second = best.getOrNull(1)?.second ?: 0f
        if (first.second < VoiceId.PERSON_THRESHOLD || first.second - second < 0.05f) return null
        return db.memory().entity(first.first)?.let { it to first.second }
    }

    /** After a conversation is understood: every named speaker teaches the app their voice. */
    suspend fun learnFromConversation(conversationId: Long, contact: String?) {
        val lines = db.utterances().forConversation(conversationId)
        val named = lines.groupBy { it.speaker }.filterKeys { s -> s != null && s != "me" && s != "Song" && s != "?" && !(s.startsWith("S") && s.length <= 3) }
        for ((name, ls) in named) {
            if (ls.size < 3 && ls.sumOf { it.endAt - it.startAt } < 8_000) continue
            val person = db.memory().allEntities().firstOrNull { it.kind == "person" && (it.name.equals(name, true) || it.name.split(' ').first().equals(name, true)) } ?: continue
            val (print, n) = centroid(ls.map { it.id }) ?: continue
            learn(person.id, print, n, conversationId)
        }
    }

    suspend fun forget(entityId: Long) = db.voiceprints().deleteFor(entityId)
}
