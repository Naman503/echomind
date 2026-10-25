package app.echomind.brain

import app.echomind.data.Db
import app.echomind.data.Kv
import app.echomind.data.Utterance
import app.echomind.stt.VoiceId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Keeps "is this me?" accurate over time, from your own recordings:
 * - the threshold is re-fitted at night to how your voice and other voices actually
 *   scored lately (two groups of scores; the line goes where they meet),
 * - the lines it is least sure about are offered for a quick "me / not me",
 *   and every answer teaches the voice model.
 */
class VoiceCheck(private val db: Db, private val voice: VoiceId) {

    /** One line with its voice score, and whether its recording was kept (so it can be played). */
    data class Scored(val u: Utterance, val score: Float, val playable: Boolean = false)

    /** Everything the check screen shows, from one pass over the lines. */
    data class Report(val stats: Stats, val uncertain: List<Scored>)

    suspend fun report(days: Int = 7, n: Int = 20): Report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val since = System.currentTimeMillis() - days * 86_400_000L
        val lines = db.utterances().range(since, System.currentTimeMillis()).filter { it.speaker != "Song" && it.speaker != "EchoMind" && it.source != "typed" }
        val sc = scoredFrom(lines)
        val thr = voice.threshold()
        val answered = db.memory().kvPrefix("voiceok:").toSet()
        val unsure = sc.filter { abs(it.score - thr) <= 0.09f && it.u.text.length >= 8 && "voiceok:${it.u.id}" !in answered }
            .sortedBy { abs(it.score - thr) }.distinctBy { it.score }.take(n)
            .map { it.copy(playable = db.audio().keptAt(it.u.startAt) != null || db.audio().keptAt(it.u.endAt) != null) }
        val distinct = sc.distinctBy { it.score }.map { it.score }
        Report(
            Stats(
                me = lines.count { it.speaker == "me" }, others = lines.count { it.speaker != null && it.speaker != "me" && it.speaker != "?" }, unknown = lines.count { it.speaker == null || it.speaker == "?" },
                threshold = thr, auto = voice.autoThreshold() != null, samples = voice.samples(), corrections = voice.corrections(),
                separation = Calib.fit(distinct)?.let { (it.hi.mean - it.lo.mean).toFloat() },
            ),
            unsure,
        )
    }

    data class Stats(val me: Int, val others: Int, val unknown: Int, val threshold: Float, val auto: Boolean, val samples: Int, val corrections: Int, val separation: Float?)

    private suspend fun scored(days: Int): List<Scored> {
        val since = System.currentTimeMillis() - days * 86_400_000L
        return scoredFrom(db.utterances().range(since, System.currentTimeMillis()).filter { it.speaker != "Song" && it.speaker != "EchoMind" && it.source != "typed" })
    }

    private suspend fun scoredFrom(lines: List<Utterance>): List<Scored> {
        val prints = lines.map { it.id }.chunked(500).flatMap { db.vectors().forOwners("s", it) }.associateBy { it.ownerId }
        return lines.mapNotNull { u ->
            val v = prints[u.id] ?: return@mapNotNull null
            val s = voice.meScore(VoiceId.normalize(Vectors.dequantize(v.vector, v.scale))) ?: return@mapNotNull null
            Scored(u, s)
        }
    }

    /** Re-fits the threshold from the last two weeks; keeps the enrolment one when the data can't tell. */
    suspend fun calibrate(): Float? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { calibrateNow() }

    private suspend fun calibrateNow(): Float? {
        if (!voice.enrolled()) return null
        // Lines measured together share one print: count each print once.
        val scores = scored(14).distinctBy { it.score }.map { it.score }
        val t = Calib.threshold(scores, voice.baseThreshold())
        voice.setAutoThreshold(t)
        return t
    }

    /** Your answer for one line: fixes the label and teaches the model both ways. */
    suspend fun answer(u: Utterance, isMe: Boolean) {
        val v = db.vectors().forOwners("s", listOf(u.id)).firstOrNull()
        if (v != null) voice.learn(VoiceId.normalize(Vectors.dequantize(v.vector, v.scale)), isMe)
        val label = if (isMe) "me" else u.speaker?.takeIf { it != "me" } ?: "S9"
        if (u.speaker != label) db.utterances().update(u.copy(speaker = label))
        db.memory().putKv(Kv("voiceok:${u.id}", if (isMe) "me" else "other", System.currentTimeMillis()))
    }

    /** A two-group (others / you) fit of 1-D scores, and the threshold where the groups meet. */
    object Calib {
        data class G(val w: Double, val mean: Double, val sd: Double) {
            fun pdf(x: Double) = w / (sd * sqrt(2 * Math.PI)) * exp(-0.5 * ((x - mean) / sd).let { it * it })
        }
        data class Fit(val lo: G, val hi: G)

        /** Expectation–maximisation for two Gaussians; null when there's too little data or no clear second group. */
        fun fit(scores: List<Float>, iterations: Int = 60): Fit? {
            if (scores.size < 40) return null
            val x = scores.map { it.toDouble() }.sorted()
            var lo = G(0.5, x[x.size / 4], 0.08); var hi = G(0.5, x[x.size * 3 / 4], 0.08)
            repeat(iterations) {
                val r = x.map { v -> val a = lo.pdf(v); val b = hi.pdf(v); val s = a + b; if (s == 0.0) 0.5 else b / s }
                val nHi = r.sum().coerceAtLeast(1e-6); val nLo = (x.size - nHi).coerceAtLeast(1e-6)
                val mHi = x.indices.sumOf { r[it] * x[it] } / nHi; val mLo = x.indices.sumOf { (1 - r[it]) * x[it] } / nLo
                val sHi = sqrt(x.indices.sumOf { r[it] * (x[it] - mHi).let { d -> d * d } } / nHi).coerceAtLeast(0.02)
                val sLo = sqrt(x.indices.sumOf { (1 - r[it]) * (x[it] - mLo).let { d -> d * d } } / nLo).coerceAtLeast(0.02)
                lo = G(nLo / x.size, mLo, sLo); hi = G(nHi / x.size, mHi, sHi)
            }
            if (lo.mean > hi.mean) { val t = lo; lo = hi; hi = t }
            // Two real groups: far enough apart (in their own spread) and neither tiny.
            val d = (hi.mean - lo.mean) / sqrt((lo.sd * lo.sd + hi.sd * hi.sd) / 2)
            if (hi.mean - lo.mean < 0.15 || d < 1.5 || lo.w < 0.08 || hi.w < 0.08) return null
            return Fit(lo, hi)
        }

        /** Where the groups meet, kept within ±0.08 of [base] and sane bounds; null keeps [base]. */
        fun threshold(scores: List<Float>, base: Float): Float? {
            val f = fit(scores) ?: return null
            var a = f.lo.mean; var b = f.hi.mean
            repeat(50) {
                val m = (a + b) / 2
                if (ln(f.lo.pdf(m) + 1e-300) > ln(f.hi.pdf(m) + 1e-300)) a = m else b = m
            }
            return ((a + b) / 2).toFloat().coerceIn(base - 0.08f, base + 0.08f).coerceIn(0.4f, 0.72f)
        }
    }
}
