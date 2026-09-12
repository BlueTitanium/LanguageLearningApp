package com.tsm.wtlens

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds the dictionary/citation form of a conjugated Korean verb or
 * adjective using KOMORAN, a real morphological analyzer, rather than
 * naive suffix stripping (which can't recover irregular stem changes,
 * e.g. 들어요 -> 듣다, 도와요 -> 돕다, 그래요 -> 그렇다).
 */
object KoreanMorphAnalyzer {

    private const val TAG = "WebtoonLensMorph"

    // Sejong-tagset tags for predicate stems: verb, adjective, auxiliary
    // predicate, positive/negative copula, and verb/adjective-deriving suffixes.
    private val PREDICATE_TAGS = setOf("VV", "VA", "VX", "VCP", "VCN", "XSV", "XSA")

    private val komoran: kr.co.shineware.nlp.komoran.core.Komoran? by lazy {
        runCatching {
            kr.co.shineware.nlp.komoran.core.Komoran(
                kr.co.shineware.nlp.komoran.constant.DEFAULT_MODEL.STABLE
            )
        }.onFailure { e -> Log.e(TAG, "Failed to load KOMORAN models", e) }.getOrNull()
    }

    /**
     * Returns the dictionary/citation form (stem + "다") of the first
     * predicate morpheme found in [text], or null if none was found (e.g.
     * [text] is just a bare noun, or analysis failed).
     */
    suspend fun findPredicateRoot(text: String): String? = withContext(Dispatchers.Default) {
        val analyzer = komoran ?: return@withContext null
        runCatching {
            val stem = analyzer.analyze(text).tokenList.firstOrNull { it.pos in PREDICATE_TAGS }
                ?: return@runCatching null
            "${stem.morph}다"
        }.onFailure { e -> Log.e(TAG, "Analysis failed for '$text'", e) }.getOrNull()
    }
}
