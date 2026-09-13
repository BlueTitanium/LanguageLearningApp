package com.tsm.wtlens

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout

/**
 * Small shared style palette/helpers so MainActivity, the overlay popups,
 * and the vocab screens all read as one consistent app instead of a pile
 * of default-styled widgets. Kept intentionally simple (colors + spacing +
 * a couple of drawable builders) rather than a full design system.
 */
object UiStyle {
    // Core palette (dark surfaces, consistent with the overlay popups).
    val CARD_BACKGROUND = Color.rgb(28, 28, 30)
    val SCRIM = Color.argb(160, 0, 0, 0)
    val TEXT_PRIMARY = Color.WHITE
    val TEXT_SECONDARY = Color.argb(190, 255, 255, 255)
    val TEXT_MUTED = Color.argb(140, 255, 255, 255)
    val ACCENT = Color.rgb(130, 200, 255)
    val DIVIDER = Color.argb(40, 255, 255, 255)

    // The same 3-tier vocab-status colors used for on-screen word highlighting.
    val STATUS_NOT_SAVED = Color.rgb(33, 150, 243)
    val STATUS_IN_PROGRESS = Color.rgb(156, 39, 176)
    val STATUS_LEARNED = Color.rgb(158, 158, 158)

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** A rounded card background for grouping a section of content. */
    fun cardBackground(cornerRadiusDp: Float = 16f, density: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            setColor(CARD_BACKGROUND)
            cornerRadius = cornerRadiusDp * density
        }

    /** A small rounded pill background, e.g. for icon buttons. */
    fun pillBackground(color: Int, cornerRadiusDp: Float, density: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = cornerRadiusDp * density
        }

    fun statusDotDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Applies a simple, consistent "outlined chip" look to a plain Button. */
    fun styleAsChipButton(button: Button, context: Context) {
        val density = context.resources.displayMetrics.density
        button.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        button.background = GradientDrawable().apply {
            setColor(Color.argb(30, 130, 200, 255))
            cornerRadius = 20f * density
            setStroke((1.5f * density).toInt(), Color.argb(90, 130, 200, 255))
        }
        button.setTextColor(ACCENT)
        button.isAllCaps = false
    }

    fun divider(context: Context): android.view.View = android.view.View(context).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)
        )
    }
}
