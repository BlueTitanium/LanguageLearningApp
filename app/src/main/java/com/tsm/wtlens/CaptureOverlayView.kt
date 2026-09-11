package com.tsm.wtlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Full-screen overlay showing a frozen screenshot with OCR'd Korean text
 * made tappable: tapping a single word looks that word up, tapping anywhere
 * else on its line/speech-bubble translates the whole line.
 */
class CaptureOverlayView(
    context: Context,
    private val screenshot: Bitmap,
    private val words: List<OcrHit>,
    private val lines: List<OcrHit>,
    private val translationHelper: TranslationHelper,
    private val scope: CoroutineScope,
    private val onCloseRequested: () -> Unit
) : View(context) {

    private data class Selection(
        val hit: OcrHit,
        val anchor: RectF,
        var translated: String?,
        var loading: Boolean
    )

    private var selection: Selection? = null

    private val wordBoxPaint = Paint().apply {
        color = Color.argb(60, 255, 235, 59)
        style = Paint.Style.FILL
    }
    private val lineBoxPaint = Paint().apply {
        color = Color.argb(90, 66, 133, 244)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val closeButtonBgPaint = Paint().apply {
        color = Color.argb(200, 30, 30, 30)
        style = Paint.Style.FILL
    }
    private val closeButtonTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val popupBgPaint = Paint().apply {
        color = Color.argb(235, 20, 20, 20)
        style = Paint.Style.FILL
    }
    private val popupOriginalPaint = Paint().apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = 34f
        isAntiAlias = true
    }
    private val popupTranslatedPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
    }

    private val closeButtonRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val margin = 24f
        val size = 96f
        closeButtonRect.set(w - margin - size, margin, w - margin, margin + size)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(screenshot, 0f, 0f, null)

        for (line in lines) {
            canvas.drawRect(line.bounds, lineBoxPaint)
        }
        for (word in words) {
            canvas.drawRect(word.bounds, wordBoxPaint)
        }

        canvas.drawRoundRect(closeButtonRect, 20f, 20f, closeButtonBgPaint)
        canvas.drawText(
            "✕",
            closeButtonRect.centerX(),
            closeButtonRect.centerY() + 14f,
            closeButtonTextPaint
        )

        selection?.let { sel -> drawPopup(canvas, sel) }
    }

    private fun drawPopup(canvas: Canvas, sel: Selection) {
        val paddingH = 32f
        val paddingV = 24f
        val original = sel.hit.text
        val translated = if (sel.loading) "Translating…" else (sel.translated ?: "")

        val originalWidth = popupOriginalPaint.measureText(original)
        val translatedWidth = popupTranslatedPaint.measureText(translated)
        val boxWidth = (maxOf(originalWidth, translatedWidth) + paddingH * 2)
            .coerceAtMost(width - 40f)
        val boxHeight = 130f

        var left = sel.anchor.centerX() - boxWidth / 2
        left = left.coerceIn(20f, width - boxWidth - 20f)

        var top = sel.anchor.top - boxHeight - 16f
        if (top < 20f) top = sel.anchor.bottom + 16f

        val box = RectF(left, top, left + boxWidth, top + boxHeight)
        canvas.drawRoundRect(box, 24f, 24f, popupBgPaint)
        canvas.drawText(original, box.left + paddingH, box.top + paddingV + 30f, popupOriginalPaint)
        canvas.drawText(translated, box.left + paddingH, box.top + paddingV + 78f, popupTranslatedPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x
        val y = event.y

        if (closeButtonRect.contains(x, y)) {
            onCloseRequested()
            return true
        }

        val tappedWord = words.firstOrNull { rectContains(it.bounds, x, y) }
        val tappedLine = lines.firstOrNull { rectContains(it.bounds, x, y) }
        val hit = tappedWord ?: tappedLine

        if (hit == null) {
            if (selection != null) {
                selection = null
                invalidate()
            }
            return true
        }

        val anchor = RectF(hit.bounds)
        selection = Selection(hit, anchor, translated = null, loading = true)
        invalidate()

        scope.launch {
            val result = runCatching { translationHelper.translate(hit.text) }
                .getOrElse { "(translation failed)" }
            if (selection?.hit === hit) {
                selection = selection?.copy(translated = result, loading = false)
                invalidate()
            }
        }
        return true
    }

    private fun rectContains(rect: Rect, x: Float, y: Float): Boolean {
        return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onCloseRequested()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }
}
