package app.echomind.brain

/**
 * Spelling-tolerant search for code-mixed speech. The same name can reach the
 * transcript as "Rahul", "Raahul" or "राहुल" depending on the engine, so every
 * line also stores a normalised form: Devanagari transliterated to Latin, then
 * reduced to a rough phonetic key (long vowels shortened, aspirates merged).
 * Queries go through the same function, so they meet in the middle.
 */
object TextNorm {
    private val vowels = mapOf(
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o",
    )
    private val signs = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'ृ' to "ri", 'े' to "e", 'ै' to "ai",
        'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ं' to "n", 'ँ' to "n", 'ः' to "h", '्' to "",
    )
    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh",
        'ष' to "sh", 'स' to "s", 'ह' to "h", 'ळ' to "l",
    )
    private val digits = "०१२३४५६७८९"

    /** Devanagari → Latin, with the inherent "a" after consonants not followed by a sign. */
    fun transliterate(s: String): String {
        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val next = s.getOrNull(i + 1)
            val cons = consonants[c]
            when {
                cons != null -> {
                    sb.append(cons)
                    val after = if (next == '़') s.getOrNull(i + 2) else next
                    val nasal = after == 'ं' || after == 'ँ' || after == 'ः'
                    val nextIsSign = after != null && signs.containsKey(after) && !nasal
                    val wordEnd = after == null || !(after in 'ऀ'..'ॿ')
                    // Hindi drops the inherent "a" mid-word before a consonant that carries its own
                    // vowel (रंगरेज → rangrez, अपने → apne), but never on a word's first letter (दरिया → dariya).
                    val firstOfWord = i == 0 || s[i - 1] !in 'ऀ'..'ॿ'
                    val nextCons = after?.let { consonants.containsKey(it) } == true
                    val nextHasVowel = nextCons && s.getOrNull(s.indexOf(after, i + 1) + 1)?.let { signs.containsKey(it) && it != '्' && it != 'ं' && it != 'ँ' } == true
                    val schwaDropped = !firstOfWord && nextHasVowel
                    if (nasal || (!nextIsSign && !wordEnd && !schwaDropped)) sb.append('a')
                }
                vowels.containsKey(c) -> sb.append(vowels[c])
                signs.containsKey(c) -> sb.append(signs[c])
                c == '़' -> {}
                c == '।' || c == '॥' -> sb.append('.')
                digits.indexOf(c) >= 0 -> sb.append('0' + digits.indexOf(c))
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** A rough sound-alike key for one Latin word. */
    fun phonetic(word: String): String {
        var w = word.lowercase()
        w = w.replace("aa", "a").replace("ee", "i").replace("ii", "i").replace("oo", "u").replace("uu", "u")
            // Hindi diphthongs as people actually spell them: mein / men, hai / he, aur / or.
            .replace("ei", "e").replace("ai", "e").replace("au", "o").let { if (it.length > 1 && it.endsWith("h") && it[it.length - 2] in "aeiou") it.dropLast(1) else it }
            .replace("ph", "f").replace("w", "v").replace("z", "j").replace("q", "k").replace("ck", "k")
            .replace(Regex("([bcdfghjklmnprstvy])h"), "$1").replace(Regex("(.)\\1+"), "$1")
        return w
    }

    private val wordRe = Regex("[\\p{L}\\p{M}\\p{N}]+")

    /** The searchable form of a line: original words + their transliterated phonetic keys. */
    fun searchable(text: String): String {
        val latin = transliterate(text).lowercase()
        val keys = wordRe.findAll(latin).map { phonetic(it.value) }.filter { it.length > 1 }.toList()
        return keys.joinToString(" ")
    }

    fun words(text: String): Int = wordRe.findAll(text).count()

    private val stop = setOf(
        "the", "a", "an", "is", "are", "was", "were", "to", "of", "and", "or", "in", "on", "at", "for", "with", "about", "what", "when",
        "who", "did", "do", "does", "i", "me", "my", "we", "you", "he", "she", "it", "they", "that", "this", "say", "said", "tell",
        "kya", "ki", "ka", "ke", "ko", "hai", "tha", "thi", "the", "se", "ne", "mein", "me", "aur", "bhi", "to", "ye", "vo", "wo",
        "kab", "kaun", "kahan", "kaise", "kyun", "last", "week", "today", "yesterday",
    )

    /** An FTS4 MATCH query: prefix matches on the meaningful words, OR-ed. */
    fun ftsQuery(query: String): String? {
        val keys = wordRe.findAll(transliterate(query).lowercase()).map { it.value }.filter { it !in stop && it.length > 1 }
            .map { phonetic(it) }.filter { it.length > 1 }.distinct().take(8).toList()
        if (keys.isEmpty()) return null
        return keys.joinToString(" OR ") { "searchText:$it*" }
    }

    fun keyTerms(query: String): List<String> =
        wordRe.findAll(transliterate(query).lowercase()).map { it.value }.filter { it !in stop && it.length > 2 }.map { phonetic(it) }.distinct().toList()
}
