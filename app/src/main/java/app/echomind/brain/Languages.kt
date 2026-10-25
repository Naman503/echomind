package app.echomind.brain

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * Which language each line was in — Hindi (Devanagari or Hindi written in Latin
 * letters), English, or Hinglish (a real mix) — and how much time went to each,
 * by you and by others, by hour and by person. Measured from the words, no AI.
 */
object Languages {
    enum class Lang(val label: String) { HINDI("Hindi"), ENGLISH("English"), HINGLISH("Hinglish") }

    /** Common Hindi words as people type them in Latin letters. Ambiguous ones ("to", "do", "the") are left out. */
    private val romanHindi = setOf(
        "hai", "hain", "hu", "hoon", "hun", "tha", "thi", "nahi", "nahin", "nai", "kya", "kyu", "kyun", "kyon", "mein", "mai", "main", "mujhe", "mujhko",
        "tu", "tum", "tumhe", "tumhare", "tumhara", "aap", "aapka", "hum", "humko", "hamara", "yaar", "yar", "bhai", "acha", "accha", "achha", "achcha",
        "theek", "thik", "karna", "karo", "karte", "karta", "karti", "kar", "kiya", "kiye", "raha", "rahi", "rahe", "rha", "rhi", "ho", "hoga", "hogi", "hona",
        "gaya", "gayi", "gaye", "wala", "wali", "wale", "kuch", "kuchh", "sab", "bas", "abhi", "phir", "fir", "toh", "bhi", "aur", "lekin", "matlab",
        "haan", "han", "na", "mera", "meri", "mere", "tera", "teri", "tere", "uska", "uski", "uske", "unka", "unki", "apna", "apni", "apne", "ye", "yeh",
        "wo", "woh", "vo", "idhar", "udhar", "yahan", "wahan", "kahan", "kaha", "kab", "kaise", "kaisa", "kitna", "kitne", "chal", "chalo", "dekh", "dekho",
        "bol", "bolo", "bola", "boli", "sun", "suno", "samajh", "samjha", "pata", "bahut", "bohot", "bohut", "zyada", "jyada", "log", "logo", "ghar",
        "kaam", "din", "raat", "kal", "aaj", "paisa", "paise", "lagta", "lagti", "laga", "hua", "hui", "diya", "liya", "jaa", "ja", "aaja", "aao", "khana",
        "pani", "sach", "jaldi", "thoda", "thodi", "sahi", "galat", "chahiye", "sakta", "sakti", "sakte", "wapas", "vapas", "pehle", "baad", "saath", "sath",
        "liye", "kyunki", "agar", "magar", "lagega", "jaayega", "jayega", "aayega", "ayega", "denge", "lenge", "karenge", "karega", "karegi", "dena", "lena",
        "abe", "arey", "arre", "are", "ji", "nhi", "kr", "krna", "bta", "batao", "bata", "mat", "kaafi", "kafi", "zaroor", "jarur", "shayad", "hamesha",
        "lunga", "lungi", "dunga", "dungi", "karunga", "karungi", "jaunga", "jaungi", "aaunga", "milte", "milenge", "milna", "jaana", "jana", "aana", "aa",
        "rehna", "rakh", "rakho", "mil", "mila", "dekhne", "pahle", "jao", "chalega", "nahi", "lagao", "ladka", "ladki", "bhaiya", "didi", "de", "le", "dekhte", "chahta", "chahti", "pata", "hoti", "hota", "hote", "kyaa", "accha", "bilkul", "ekdum", "sabse",
    )
    private val word = Regex("[\\p{L}\\p{M}']+")

    private fun isDevanagari(w: String) = w.any { it in 'ऀ'..'ॿ' }

    /** The language of one line; null when there's nothing to judge (numbers, a single "ok"). */
    fun of(text: String): Lang? {
        val words = word.findAll(text.lowercase()).map { it.value }.filter { it.length > 1 || isDevanagari(it) }.toList()
        if (words.isEmpty()) return null
        val hi = words.count { isDevanagari(it) || it in romanHindi }
        val share = hi.toDouble() / words.size
        if (words.size < 3) return if (share >= 0.5) Lang.HINDI else Lang.ENGLISH
        return when {
            share >= 0.7 -> Lang.HINDI
            share < 0.15 -> Lang.ENGLISH
            else -> Lang.HINGLISH
        }
    }

    data class Line(val speaker: String?, val text: String, val startAt: Long, val endAt: Long, val conversationId: Long? = null)

    @Serializable
    data class Split(val hindi: Int = 0, val english: Int = 0, val hinglish: Int = 0) {
        val total get() = hindi + english + hinglish
        operator fun get(l: Lang) = when (l) { Lang.HINDI -> hindi; Lang.ENGLISH -> english; Lang.HINGLISH -> hinglish }
        fun plus(l: Lang, s: Int) = when (l) { Lang.HINDI -> copy(hindi = hindi + s); Lang.ENGLISH -> copy(english = english + s); Lang.HINGLISH -> copy(hinglish = hinglish + s) }
        operator fun plus(o: Split) = Split(hindi + o.hindi, english + o.english, hinglish + o.hinglish)
        fun pct(l: Lang) = if (total == 0) 0 else (get(l) * 100 + total / 2) / total
        val main get() = Lang.entries.maxByOrNull { get(it) }?.takeIf { total > 0 }
    }

    @Serializable
    data class Day(
        /** Seconds per language, your lines. */
        val mine: Split = Split(),
        /** Seconds per language, everyone else. */
        val others: Split = Split(),
        /** Your seconds per language for each hour 0..23. */
        val byHour: List<Split> = List(24) { Split() },
        /** Seconds per language with each named person (their lines and yours in the same conversations). */
        val byPerson: Map<String, Split> = emptyMap(),
        /** Times you changed language from one line to your next within a conversation. */
        val switches: Int = 0,
        /** Different words you used. */
        val vocabulary: Int = 0,
        /** Your most used words (stop words left out). */
        val topWords: List<Pair<String, Int>> = emptyList(),
    )

    private val stop = setOf(
        "the", "a", "an", "and", "or", "is", "are", "was", "were", "be", "to", "of", "in", "on", "it", "that", "this", "i", "you", "he", "she", "we", "they",
        "my", "your", "for", "with", "so", "but", "not", "no", "yes", "ok", "okay", "do", "did", "have", "has", "at", "as", "if", "will", "can", "just", "like",
        "है", "हैं", "था", "थी", "नहीं", "क्या", "में", "मैं", "तो", "भी", "और", "की", "का", "के", "को", "से", "पे", "पर", "हो", "हां", "ना", "ये", "वो", "यार",
        "भाई", "रहा", "रही", "रहे", "तुम", "हूं", "हूँ", "फिर", "अभी", "अरे", "कुछ", "मुझे", "मेरा", "मेरी", "मेरे", "तुम्हारा", "आप", "हम", "वह", "यह", "कर", "करो", "करना",
        "गया", "गई", "गए", "दिया", "लिया", "हुआ", "होगा", "बस", "सब", "बहुत", "अच्छा", "ठीक", "नहीं", "नही", "मत", "जो", "कि", "भी", "तक", "लिए", "साथ", "वाला", "वाले",
        "also", "then", "there", "here", "what", "when", "which", "would", "should", "could", "about", "from", "they", "them", "been", "into", "only", "some", "very", "really", "one", "two",
    ) + romanHindi

    private fun isPerson(s: String?, owner: String) = s != null && s != owner && s != "?" && !Regex("S\\d+").matches(s) &&
        !s.equals("other", true) && !s.equals("others", true) && !Memory.isJunkName(s)

    fun day(lines: List<Line>, zone: ZoneId = ZoneId.systemDefault(), owner: String = "me"): Day {
        var mine = Split(); var others = Split()
        val byHour = MutableList(24) { Split() }
        val byPerson = HashMap<String, Split>()
        var switches = 0
        val counts = HashMap<String, Int>()
        val talk = lines.filter { it.speaker != "Song" && it.speaker != "EchoMind" }.sortedBy { it.startAt }
        // Who was in each conversation (named people only), to credit your lines in their company.
        val company = talk.groupBy { it.conversationId }.mapValues { (_, ls) -> ls.mapNotNull { it.speaker }.filter { isPerson(it, owner) }.toSet() }
        var lastMine: Pair<Long?, Lang>? = null
        for (l in talk) {
            val lang = of(l.text) ?: continue
            val sec = ((l.endAt - l.startAt) / 1000).toInt().coerceIn(1, 120)
            if (l.speaker == owner) {
                mine = mine.plus(lang, sec)
                val h = Instant.ofEpochMilli(l.startAt).atZone(zone).hour
                byHour[h] = byHour[h].plus(lang, sec)
                // A switch is Hindi ↔ English between your lines; Hinglish lines are already mixed.
                if (lastMine != null && lastMine.first == l.conversationId && lastMine.second != lang && lang != Lang.HINGLISH && lastMine.second != Lang.HINGLISH) switches++
                lastMine = l.conversationId to lang
                word.findAll(l.text.lowercase()).map { it.value }.filter { it.length > 2 && it !in stop }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
                company[l.conversationId].orEmpty().forEach { p -> byPerson[p] = (byPerson[p] ?: Split()).plus(lang, sec) }
            } else {
                others = others.plus(lang, sec)
                l.speaker?.takeIf { isPerson(it, owner) }?.let { p -> byPerson[p] = (byPerson[p] ?: Split()).plus(lang, sec) }
            }
        }
        return Day(
            mine = mine, others = others, byHour = byHour, byPerson = byPerson, switches = switches,
            vocabulary = counts.size,
            topWords = counts.entries.sortedByDescending { it.value }.take(12).map { it.key to it.value },
        )
    }
}
