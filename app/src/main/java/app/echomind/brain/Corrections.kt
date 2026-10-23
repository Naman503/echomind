package app.echomind.brain

/**
 * Words speech recognition keeps getting wrong, fixed before the AI reads a
 * transcript: "not taking => note-taking". Whole words only, any case.
 */
object Corrections {
    data class Rule(val heard: String, val meant: String)

    fun parse(lines: List<String>): List<Rule> = lines.mapNotNull { l ->
        val parts = l.split("=>", "→").map { it.trim() }
        if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty() && !parts[0].equals(parts[1], true)) Rule(parts[0], parts[1]) else null
    }

    fun apply(text: String, rules: List<Rule>): String {
        var out = text
        // Longer phrases first, so "not taking app" wins over "not taking".
        for (r in rules.sortedByDescending { it.heard.length }) {
            out = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(r.heard) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).replace(out, Regex.escapeReplacement(r.meant))
        }
        return out
    }
}
