package com.tsm.wtlens

/** One word/token of a sentence breakdown: its text, guessed grammatical role, and a gloss. */
data class SentenceWordBreakdown(
    val text: String,
    val role: String,
    val gloss: String?
)

/**
 * A crude, particle-based heuristic for showing how a Korean sentence's
 * meaning is put together — NOT a real morphological/dependency parser.
 * Korean is subject-object-verb (predicate-final), and grammatical role is
 * largely signaled by particles attached to the end of each word, so this
 * just pattern-matches the trailing characters of each token against the
 * common case particles and calls the last token the predicate.
 *
 * This will misfire on anything more complex than a simple clause: embedded
 * clauses, multiple predicates, honorific-only endings, particle-less
 * colloquial drops, etc. It's meant to give a rough, useful-most-of-the-time
 * hint for short webtoon dialogue, not a grammatically rigorous parse.
 */
object SentenceAnalyzer {

    private val TRIM_CHARS = charArrayOf(
        '.', ',', '!', '?', ';', ':', '"', '\'', '“', '”', '‘', '’', '…',
        '(', ')', '[', ']', '~', ' ', '　'
    )

    // Longest-marker-first so e.g. "으로" is preferred over the "로" it also
    // trivially ends with, and "에서"/"에게" over bare "에".
    private val MARKERS: List<Pair<String, String>> = listOf(
        "께서" to "Subject (honorific)",
        "에서" to "Location/source",
        "에게" to "Recipient/target",
        "한테" to "Recipient/target",
        "부터" to "Starting point",
        "까지" to "Ending point",
        "으로" to "Direction/means",
        "이" to "Subject",
        "가" to "Subject",
        "은" to "Topic",
        "는" to "Topic",
        "을" to "Object",
        "를" to "Object",
        "에" to "Location/time",
        "로" to "Direction/means",
        "와" to "Connective (and/with)",
        "과" to "Connective (and/with)",
        "도" to "Also/too",
        "만" to "Only",
        "의" to "Possessive"
    ).sortedByDescending { it.first.length }

    fun classifyRole(rawWord: String, isLast: Boolean): String {
        val word = rawWord.trim(*TRIM_CHARS)
        if (word.isEmpty()) return "—"
        if (isLast) return "Predicate (verb/adjective)"
        for ((marker, role) in MARKERS) {
            if (word.length > marker.length && word.endsWith(marker)) {
                return "$role (marked by \"$marker\")"
            }
        }
        return "Unmarked (modifier/adverb/etc.)"
    }
}
