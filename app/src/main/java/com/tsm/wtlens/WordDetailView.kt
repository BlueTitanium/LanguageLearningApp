package com.tsm.wtlens

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Everything known about one word/phrase lookup, enough to render a full detail view. */
data class WordLookupDetail(
    val original: String,
    val entries: List<DictionaryEntry>,
    val fallbackTranslation: String,
    val sourceLabel: String,
    /** The individual OCR word tokens that made up this selection, in reading order. */
    val words: List<String> = emptyList(),
    /** Populated only for multi-word selections; see [SentenceAnalyzer]. */
    val breakdown: List<SentenceWordBreakdown> = emptyList(),
    /**
     * The dictionary/citation form of [original], from [KoreanMorphAnalyzer],
     * independent of whether a dictionary entry was actually found - so a
     * word that fell back to online/on-device translation still shows its
     * conjugation root when one exists.
     */
    val conjugationRoot: String? = null
)

/**
 * Full-screen scrim + centered card shown when the translation popup itself
 * is tapped: every dictionary sense (not just the first few used in the
 * compact popup), plus the dictionary's base/root form when it differs from
 * the tapped text (i.e. the tapped text was a conjugated/inflected form).
 * Tapping the scrim (anywhere outside the card) or the Close button, or
 * pressing back, dismisses it.
 */
class WordDetailView(
    context: Context,
    detail: WordLookupDetail,
    private val onDismiss: () -> Unit,
    private val onSpeak: (String) -> Unit,
    private val onSaveVocab: (word: String, hanja: String?, gloss: String, source: String) -> Unit
) : FrameLayout(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.argb(160, 0, 0, 0))
        setOnClickListener { onDismiss() }

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 28, 30))
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true
        }

        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = detail.original
                setTextColor(Color.WHITE)
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            addView(Button(context).apply {
                text = "🔊"
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { onSpeak(detail.original) }
            })
            if (detail.words.size <= 1) {
                addView(Button(context).apply {
                    text = "🔖 Save"
                    setPadding(dp(12), 0, dp(12), 0)
                    setOnClickListener {
                        val hanja = detail.entries.firstOrNull()?.hanja
                        val gloss = detail.entries.firstOrNull()?.gloss ?: detail.fallbackTranslation
                        onSaveVocab(detail.original, hanja, gloss, detail.sourceLabel)
                        text = "✓ Saved"
                        isEnabled = false
                    }
                })
            }
        })

        if (detail.breakdown.isNotEmpty()) {
            card.addView(TextView(context).apply {
                text = "Sentence breakdown (rough guide, not a full parse)"
                setTextColor(Color.argb(220, 130, 200, 255))
                textSize = 13f
                setPadding(0, dp(14), 0, dp(6))
            })
            detail.breakdown.forEachIndexed { index, word ->
                card.addView(TextView(context).apply {
                    val glossPart = word.gloss?.let { " — \"$it\"" } ?: ""
                    text = "${index + 1}. ${word.text} — ${word.role}$glossPart"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setPadding(0, dp(3), 0, dp(3))
                })
            }
            card.addView(divider(dp(1), dp(14)))
        }

        // Prefer KOMORAN's linguistically-derived root; fall back to
        // whichever dictionary entry surface actually matched (may differ
        // slightly, e.g. via the old suffix-stripping heuristic).
        val displayRoot = (detail.conjugationRoot ?: detail.entries.firstOrNull()?.surface)
            ?.takeIf { it != detail.original }

        if (displayRoot != null) {
            card.addView(TextView(context).apply {
                text = "Base/dictionary form: $displayRoot"
                setTextColor(Color.argb(220, 130, 200, 255))
                textSize = 14f
                setPadding(0, dp(6), 0, dp(14))
            })
        } else {
            card.addView(spacer(dp(14)))
        }

        if (detail.entries.isNotEmpty()) {
            detail.entries.take(10).forEachIndexed { index, entry ->
                card.addView(TextView(context).apply {
                    val hanjaPart = if (!entry.hanja.isNullOrBlank()) "  (${entry.hanja})" else ""
                    text = "${index + 1}. ${entry.gloss}$hanjaPart"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setPadding(0, dp(4), 0, dp(4))
                })
            }
        } else {
            card.addView(TextView(context).apply {
                text = detail.fallbackTranslation
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(0, dp(14), 0, dp(6))
            })
            card.addView(TextView(context).apply {
                text = "No dictionary entry found for this text — shown via ${detail.sourceLabel}."
                setTextColor(Color.argb(180, 255, 255, 255))
                textSize = 12f
            })
        }

        card.addView(Button(context).apply {
            text = "Close"
            setPadding(0, dp(16), 0, 0)
            setOnClickListener { onDismiss() }
        })

        val maxHeightPx = (resources.displayMetrics.heightPixels * 0.7).toInt()
        val scroll = MaxHeightScrollView(context, maxHeightPx).apply { addView(card) }

        addView(
            scroll,
            LayoutParams(
                (resources.displayMetrics.widthPixels * 0.85).toInt(),
                LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )
    }

    private fun spacer(heightPx: Int) = View(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
    }

    private fun divider(heightPx: Int, marginPx: Int) = View(context).apply {
        setBackgroundColor(Color.argb(60, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, heightPx
        ).apply { topMargin = marginPx; bottomMargin = marginPx }
    }

    /** A ScrollView that wraps its content up to [maxHeightPx], then scrolls. */
    private class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cappedSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, cappedSpec)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onDismiss()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
