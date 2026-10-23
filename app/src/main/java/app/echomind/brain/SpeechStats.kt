package app.echomind.brain

import kotlinx.serialization.Serializable

/**
 * How someone spoke, measured without AI: pace, filler words, restarts, who
 * talked how much. Counts are exact; the coach (AI) explains them.
 */
object SpeechStats {
    data class Line(val id: Long, val speaker: String?, val text: String, val startAt: Long, val endAt: Long)

    @Serializable
    data class Talk(val speaker: String, val words: Int, val seconds: Int, val turns: Int)

    @Serializable
    data class Result(
        val words: Int = 0,
        val seconds: Int = 0,
        val wpm: Int = 0,
        val turns: Int = 0,
        val fillers: Map<String, Int> = emptyMap(),
        val fillerPer100: Float = 0f,
        val restarts: Int = 0,
        val stutters: Int = 0,
        val questions: Int = 0,
        val longestTurnSec: Int = 0,
        /** Share of the owner's words in Devanagari (Hindi) vs Latin letters (English / Hinglish). */
        val hindiShare: Float = 0f,
        /** Owner lines with fillers or restarts: hints for the coach. */
        val hesitant: List<Long> = emptyList(),
        val bySpeaker: List<Talk> = emptyList(),
    )

    private val phraseFillers = listOf("you know", "i mean", "kind of", "sort of", "kya bolte", "kya kehte", "क्या बोलते", "क्या कहते")
    private val wordFillers = setOf(
        "um", "umm", "uh", "uhh", "uhm", "er", "erm", "hmm", "basically", "actually", "literally",
        "matlab", "yaani", "yani", "मतलब", "यानी", "यानि", "बेसिकली", "एक्चुअली",
    )
    private val wordRe = Regex("[\\p{L}\\p{M}\\p{N}']+")

    fun words(s: String): List<String> = wordRe.findAll(s.lowercase()).map { it.value }.toList()

    private fun isDevanagari(w: String) = w.any { it in 'ऀ'..'ॿ' }

    fun fillersIn(text: String): Map<String, Int> {
        val lower = " " + words(text).joinToString(" ") + " "
        val out = HashMap<String, Int>()
        for (p in phraseFillers) {
            val n = lower.windowed(p.length + 2, 1).count { it == " $p " }
            if (n > 0) out[p] = n
        }
        for (w in words(text)) if (w in wordFillers) out[w] = (out[w] ?: 0) + 1
        return out
    }

    /** "I I", "मैं मैं": the same word twice in a row. */
    fun stuttersIn(text: String): Int = words(text).zipWithNext().count { (a, b) -> a == b && a.length <= 6 }

    /** The next line starts over what the previous one said (same opening, or mostly the same words). */
    fun isRestart(prev: String, next: String): Boolean {
        val a = words(prev); val b = words(next)
        if (a.size < 2 || b.size < 2) return false
        if (a.take(2) == b.take(2)) return true
        val shared = a.toSet().intersect(b.toSet()).size
        return shared >= 3 && shared.toFloat() / minOf(a.toSet().size, b.toSet().size) >= 0.7f
    }

    fun analyze(lines: List<Line>, owner: String = "me"): Result {
        val talk = lines.filter { it.speaker != "Song" && it.speaker != "EchoMind" }
        val mine = talk.filter { it.speaker == owner }
        if (mine.isEmpty()) return Result(bySpeaker = bySpeaker(talk))
        val allWords = mine.flatMap { words(it.text) }
        val seconds = mine.sumOf { ((it.endAt - it.startAt) / 1000).coerceIn(0, 120) }.toInt()
        val fillers = HashMap<String, Int>()
        val hesitant = ArrayList<Long>()
        var restarts = 0
        var stutters = 0
        var prev: Line? = null
        for (l in talk) {
            if (l.speaker == owner) {
                val f = fillersIn(l.text)
                f.forEach { (k, v) -> fillers[k] = (fillers[k] ?: 0) + v }
                val st = stuttersIn(l.text)
                stutters += st
                val restart = prev?.speaker == owner && l.startAt - prev.endAt < 20_000 && isRestart(prev.text, l.text)
                if (restart) restarts++
                if (f.values.sum() >= 2 || st > 0 || restart) hesitant.add(l.id)
            }
            prev = l
        }
        // A turn: consecutive owner lines with no other voice in between.
        var turns = 0; var longest = 0L; var turnStart = 0L; var inTurn = false; var lastEnd = 0L
        for (l in talk) {
            if (l.speaker == owner) {
                if (!inTurn) { turns++; inTurn = true; turnStart = l.startAt }
                lastEnd = l.endAt
                longest = maxOf(longest, lastEnd - turnStart)
            } else inTurn = false
        }
        val fillerCount = fillers.values.sum()
        return Result(
            words = allWords.size,
            seconds = seconds,
            wpm = if (seconds >= 20) (allWords.size * 60 / seconds) else 0,
            turns = turns,
            fillers = fillers.toList().sortedByDescending { it.second }.toMap(),
            fillerPer100 = if (allWords.isEmpty()) 0f else fillerCount * 100f / allWords.size,
            restarts = restarts,
            stutters = stutters,
            questions = mine.count { it.text.trim().endsWith("?") },
            longestTurnSec = (longest / 1000).toInt(),
            hindiShare = if (allWords.isEmpty()) 0f else allWords.count(::isDevanagari).toFloat() / allWords.size,
            hesitant = hesitant,
            bySpeaker = bySpeaker(talk),
        )
    }

    fun bySpeaker(lines: List<Line>): List<Talk> = lines.groupBy { it.speaker ?: "?" }.map { (who, ls) ->
        var turns = 0; var last: String? = null
        for (l in lines) { if (l.speaker == who && last != who) turns++; last = l.speaker }
        Talk(who, ls.sumOf { words(it.text).size }, ls.sumOf { ((it.endAt - it.startAt) / 1000).coerceIn(0, 120) }.toInt(), turns)
    }.sortedByDescending { it.seconds }

    /** Combines several conversations' results into one for the day. */
    fun combine(rs: List<Result>): Result {
        if (rs.isEmpty()) return Result()
        val words = rs.sumOf { it.words }
        val seconds = rs.sumOf { it.seconds }
        val fillers = HashMap<String, Int>()
        rs.forEach { r -> r.fillers.forEach { (k, v) -> fillers[k] = (fillers[k] ?: 0) + v } }
        val talk = rs.flatMap { it.bySpeaker }.groupBy { it.speaker }.map { (k, v) -> Talk(k, v.sumOf { it.words }, v.sumOf { it.seconds }, v.sumOf { it.turns }) }.sortedByDescending { it.seconds }
        return Result(
            words = words, seconds = seconds, wpm = if (seconds >= 20) words * 60 / seconds else 0, turns = rs.sumOf { it.turns },
            fillers = fillers.toList().sortedByDescending { it.second }.toMap(),
            fillerPer100 = if (words == 0) 0f else fillers.values.sum() * 100f / words,
            restarts = rs.sumOf { it.restarts }, stutters = rs.sumOf { it.stutters }, questions = rs.sumOf { it.questions },
            longestTurnSec = rs.maxOf { it.longestTurnSec },
            hindiShare = if (words == 0) 0f else rs.sumOf { (it.hindiShare * it.words).toDouble() }.toFloat() / words,
            bySpeaker = talk,
        )
    }
}
