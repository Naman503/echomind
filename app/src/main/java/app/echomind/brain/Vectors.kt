package app.echomind.brain

import kotlin.math.abs
import kotlin.math.sqrt

/** int8 vectors: 4× smaller than floats, and plenty for ranking by cosine similarity. */
object Vectors {
    fun quantize(v: FloatArray): Pair<ByteArray, Float> {
        var max = 0f
        for (x in v) max = maxOf(max, abs(x))
        val scale = if (max == 0f) 1f else max / 127f
        return ByteArray(v.size) { (v[it] / scale).toInt().coerceIn(-127, 127).toByte() } to scale
    }

    fun dequantize(b: ByteArray, scale: Float) = FloatArray(b.size) { b[it] * scale }

    /** Cosine between a float query and an int8 vector (scale cancels out). */
    fun cosine(q: FloatArray, b: ByteArray): Float {
        if (q.size != b.size) return 0f
        var dot = 0f; var nq = 0f; var nb = 0f
        for (i in q.indices) {
            val y = b[i].toFloat()
            dot += q[i] * y; nq += q[i] * q[i]; nb += y * y
        }
        return if (nq == 0f || nb == 0f) 0f else dot / (sqrt(nq) * sqrt(nb))
    }
}
