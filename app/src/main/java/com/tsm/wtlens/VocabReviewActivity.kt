package com.tsm.wtlens

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * In-app (not overlay) Anki-style review session: shows due cards one at a
 * time, front first, then the definition after "Show answer", rated
 * Again/Hard/Good/Easy which drives [VocabManager]'s SM-2 scheduling.
 */
class VocabReviewActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var ttsHelper: TtsHelper

    private var queue: MutableList<VocabEntry> = mutableListOf()
    private var totalThisSession = 0
    private var reviewedCount = 0
    private var currentEntry: VocabEntry? = null

    private lateinit var root: LinearLayout
    private lateinit var counterText: TextView
    private lateinit var wordText: TextView
    private lateinit var backContainer: LinearLayout
    private lateinit var glossText: TextView
    private lateinit var sourceText: TextView
    private lateinit var showAnswerButton: Button
    private lateinit var ratingRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ttsHelper = TtsHelper(this)
        buildUi()

        activityScope.launch {
            queue = VocabManager.dueEntries(this@VocabReviewActivity, limit = 30).toMutableList()
            totalThisSession = queue.size
            showNextCard()
        }
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        counterText = TextView(this).apply { textSize = 14f; alpha = 0.7f }
        root.addView(counterText)

        wordText = TextView(this).apply {
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 64, 0, 32)
            gravity = Gravity.CENTER
        }
        root.addView(wordText)

        root.addView(Button(this).apply {
            text = "🔊 Play"
            setOnClickListener { currentEntry?.let { ttsHelper.speak(it.surface) } }
        })

        backContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 32, 0, 32)
        }
        glossText = TextView(this).apply { textSize = 20f }
        backContainer.addView(glossText)
        sourceText = TextView(this).apply { textSize = 12f; alpha = 0.6f; setPadding(0, 8, 0, 0) }
        backContainer.addView(sourceText)
        root.addView(backContainer)

        showAnswerButton = Button(this).apply {
            text = "Show answer"
            setOnClickListener { revealAnswer() }
        }
        root.addView(showAnswerButton)

        ratingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            weightSum = 4f
        }
        val ratings = listOf(
            "Again" to ReviewRating.AGAIN,
            "Hard" to ReviewRating.HARD,
            "Good" to ReviewRating.GOOD,
            "Easy" to ReviewRating.EASY
        )
        ratings.forEach { (label, rating) ->
            ratingRow.addView(Button(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { rate(rating) }
            })
        }
        root.addView(ratingRow)

        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }

    private fun showNextCard() {
        val entry = queue.removeFirstOrNull()
        currentEntry = entry
        if (entry == null) {
            showSessionComplete()
            return
        }

        counterText.text = "${queue.size + 1} of $totalThisSession remaining"
        wordText.text = if (!entry.hanja.isNullOrBlank()) "${entry.surface} (${entry.hanja})" else entry.surface
        glossText.text = entry.gloss
        sourceText.text = "Source: ${entry.source}"

        backContainer.visibility = View.GONE
        showAnswerButton.visibility = View.VISIBLE
        ratingRow.visibility = View.GONE
    }

    private fun revealAnswer() {
        backContainer.visibility = View.VISIBLE
        showAnswerButton.visibility = View.GONE
        ratingRow.visibility = View.VISIBLE
    }

    private fun rate(rating: ReviewRating) {
        val entry = currentEntry ?: return
        reviewedCount++
        activityScope.launch {
            VocabManager.recordReview(this@VocabReviewActivity, entry, rating)
            showNextCard()
        }
    }

    private fun showSessionComplete() {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = if (totalThisSession == 0) {
                "No words are due for review right now."
            } else {
                "Session complete! Reviewed $reviewedCount word(s)."
            }
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 96, 0, 32)
        })
        root.addView(Button(this).apply {
            text = "Done"
            setOnClickListener { finish() }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsHelper.shutdown()
        activityScope.cancel()
    }
}
