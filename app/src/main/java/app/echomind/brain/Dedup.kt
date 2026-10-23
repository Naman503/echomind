package app.echomind.brain

/**
 * One idea of "the same thing said twice", used everywhere: read-aloud scripts,
 * review lists, tasks and nudges. Two texts are the same when most of the
 * shorter one's key words (spelling- and script-tolerant, see [TextNorm]) are
 * in the longer one.
 */
object Dedup {
    fun keys(s: String): Set<String> = TextNorm.keyTerms(s).toSet()

    fun same(a: String, b: String, threshold: Double = 0.7): Boolean {
        val x = keys(a); val y = keys(b)
        if (x.isEmpty() || y.isEmpty()) return a.trim().equals(b.trim(), ignoreCase = true)
        return x.intersect(y).size.toDouble() / minOf(x.size, y.size) >= threshold
    }

    /** Keeps the first of each group of near-duplicates, in order. */
    fun <T> distinct(items: List<T>, threshold: Double = 0.7, text: (T) -> String): List<T> {
        val kept = ArrayList<T>()
        val keySets = ArrayList<Set<String>>()
        for (it in items) {
            val t = text(it)
            val k = keys(t)
            val dup = keySets.indices.any { i ->
                val other = keySets[i]
                if (k.isEmpty() || other.isEmpty()) text(kept[i]).trim().equals(t.trim(), true)
                else k.intersect(other).size.toDouble() / minOf(k.size, other.size) >= threshold
            }
            if (!dup) { kept.add(it); keySets.add(k) }
        }
        return kept
    }

    fun strings(items: List<String>, threshold: Double = 0.7) = distinct(items, threshold) { it }

    /** [items] without anything already said in [already]. */
    fun <T> without(items: List<T>, already: List<String>, threshold: Double = 0.7, text: (T) -> String): List<T> =
        items.filter { i -> already.none { same(text(i), it, threshold) } }
}
