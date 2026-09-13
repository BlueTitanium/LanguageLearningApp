package com.tsm.wtlens

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One word/phrase looked up during the current bubble session. */
data class SessionHistoryEntry(
    val original: String,
    val translated: String,
    val source: String,
    val timestampMillis: Long,
    val isSingleWord: Boolean
)

/**
 * Overlay listing everything looked up since the bubble was started (most
 * recent first), opened via a long-press on the bubble. Each single-word
 * entry shows a learned/not-learned dot (from [learnedByWord]) matching the
 * same blue/grey scheme used for on-screen word highlighting.
 */
class SessionHistoryView(
    context: Context,
    entries: List<SessionHistoryEntry>,
    learnedByWord: Map<String, Boolean>,
    private val onDismiss: () -> Unit
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

        card.addView(TextView(context).apply {
            text = "Session history"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })

        if (entries.isEmpty()) {
            card.addView(TextView(context).apply {
                text = "Nothing looked up yet this session."
                setTextColor(Color.argb(200, 255, 255, 255))
                textSize = 15f
            })
        } else {
            entries.asReversed().forEach { entry ->
                card.addView(buildRow(context, entry, learnedByWord, dp(1)))
            }
        }

        card.addView(Button(context).apply {
            text = "Close"
            setPadding(0, dp(16), 0, 0)
            setOnClickListener { onDismiss() }
        })

        val maxHeightPx = (resources.displayMetrics.heightPixels * 0.75).toInt()
        val scroll = MaxHeightScrollView(context, maxHeightPx).apply { addView(card) }

        addView(
            scroll,
            LayoutParams(
                (resources.displayMetrics.widthPixels * 0.9).toInt(),
                LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )
    }

    private fun buildRow(
        context: Context,
        entry: SessionHistoryEntry,
        learnedByWord: Map<String, Boolean>,
        dividerHeight: Int
    ): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (entry.isSingleWord) {
            val learned = learnedByWord[entry.original] == true
            headerRow.addView(View(context).apply {
                val size = dp(10)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dp(8)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (learned) Color.rgb(158, 158, 158) else Color.rgb(33, 150, 243))
                }
            })
        }

        headerRow.addView(TextView(context).apply {
            text = entry.original
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        row.addView(headerRow)

        row.addView(TextView(context).apply {
            text = entry.translated
            setTextColor(Color.argb(220, 255, 255, 255))
            textSize = 15f
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(TextView(context).apply {
            val time = DateFormat.format("h:mm a", entry.timestampMillis)
            text = "$time · ${entry.source}"
            setTextColor(Color.argb(150, 255, 255, 255))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(View(context).apply {
            setBackgroundColor(Color.argb(40, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dividerHeight
            ).apply { topMargin = dp(8) }
        })

        return row
    }

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
