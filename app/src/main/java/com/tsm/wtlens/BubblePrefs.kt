package com.tsm.wtlens

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * Persisted appearance/position settings for the floating bubble, editable
 * from [MainActivity] and read by [OverlayService] both at bubble-creation
 * time and live (via a SharedPreferences listener) while the bubble is up.
 */
object BubblePrefs {

    const val PREFS_NAME = "bubble_prefs"

    const val KEY_SIZE_DP = "bubble_size_dp"
    const val KEY_OPACITY_PERCENT = "bubble_opacity_percent"
    const val KEY_COLOR = "bubble_color"
    const val KEY_POS_X = "bubble_pos_x"
    const val KEY_POS_Y = "bubble_pos_y"

    const val DEFAULT_SIZE_DP = 56
    const val MIN_SIZE_DP = 32
    const val MAX_SIZE_DP = 96

    const val DEFAULT_OPACITY_PERCENT = 90
    const val MIN_OPACITY_PERCENT = 30

    val DEFAULT_COLOR = Color.parseColor("#4285F4")

    val PRESET_COLORS = listOf(
        Color.parseColor("#4285F4"), // blue
        Color.parseColor("#9C27B0"), // purple
        Color.parseColor("#0F9D58"), // green
        Color.parseColor("#F4511E"), // orange
        Color.parseColor("#E91E63")  // pink
    )

    const val NO_POSITION = Int.MIN_VALUE

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun sizeDp(context: Context): Int =
        prefs(context).getInt(KEY_SIZE_DP, DEFAULT_SIZE_DP)

    fun opacityPercent(context: Context): Int =
        prefs(context).getInt(KEY_OPACITY_PERCENT, DEFAULT_OPACITY_PERCENT)

    fun color(context: Context): Int =
        prefs(context).getInt(KEY_COLOR, DEFAULT_COLOR)

    fun savedPosition(context: Context): Pair<Int, Int>? {
        val p = prefs(context)
        val x = p.getInt(KEY_POS_X, NO_POSITION)
        val y = p.getInt(KEY_POS_Y, NO_POSITION)
        return if (x == NO_POSITION || y == NO_POSITION) null else x to y
    }

    fun savePosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(KEY_POS_X, x).putInt(KEY_POS_Y, y).apply()
    }

    fun resetPosition(context: Context) {
        prefs(context).edit().remove(KEY_POS_X).remove(KEY_POS_Y).apply()
    }
}
