package com.tsm.wtlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Full-screen overlay showing a frozen screenshot with OCR'd Korean text
 * made tappable in two ways:
 *  - a quick tap on a single word translates just that word.
 *  - dragging a circle/lasso around any area translates everything whose
 *    OCR box falls inside it, in reading order (one word, a whole line, or
 *    a multi-line span all work the same way).
 */
class CaptureOverlayView(
    context: Context,
    private val screenshot: Bitmap,
    private val words: List<OcrHit>,
    private val lines: List<OcrHit>,
    private val learnedWords: Set<String>,
    private val closeButtonCenter: Pair<Float, Float>?,
    private val translationHelper: TranslationHelper,
    private val dictionaryLookup: suspend (String) -> List<DictionaryEntry>,
    private val onlineTranslate: suspend (String, String?) -> Pair<String, String>?,
    private val contextAwareEnabled: Boolean,
    private val scope: CoroutineScope,
    private val onCloseRequested: () -> Unit,
    private val onDefinitionTapped: (WordLookupDetail) -> Unit,
    private val onSpeak: (String) -> Unit,
    private val onSaveVocab: (word: String, hanja: String?, gloss: String, source: String) -> Unit
) : View(context) {

    private data class Selection(
        val id: Int,
        val text: String,
        val anchor: RectF,
        var translated: String?,
        var loading: Boolean,
        var source: String? = null,
        var dictEntries: List<DictionaryEntry> = emptyList(),
        val isSingleWord: Boolean,
        var saved: Boolean = false
    )

    private var selection: Selection? = null
    private var selectedHits: List<OcrHit> = emptyList()
    private var nextSelectionId = 0

    // Not-yet-learned words (never saved, or saved but not graduated).
    private val notLearnedPaint = Paint().apply {
        color = Color.argb(70, 33, 150, 243)
        style = Paint.Style.FILL
    }
    // Learned/graduated words - subdued but still visibly tappable.
    private val learnedPaint = Paint().apply {
        color = Color.argb(55, 158, 158, 158)
        style = Paint.Style.FILL
    }
    private val lineBoxPaint = Paint().apply {
        color = Color.argb(90, 66, 133, 244)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    // The word(s) just tapped/circled.
    private val selectedFillPaint = Paint().apply {
        color = Color.argb(140, 255, 235, 59)
        style = Paint.Style.FILL
    }
    private val lassoStrokePaint = Paint().apply {
        color = Color.argb(230, 255, 87, 34)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val lassoFillPaint = Paint().apply {
        color = Color.argb(50, 255, 87, 34)
        style = Paint.Style.FILL
        isAntiAlias = true
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
    private val popupOriginalPaint = TextPaint().apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = 34f
        isAntiAlias = true
    }
    private val popupTranslatedPaint = TextPaint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
    }
    private val popupSourcePaint = Paint().apply {
        color = Color.argb(180, 130, 200, 255)
        textSize = 24f
        isAntiAlias = true
    }
    private val speakerIconBgPaint = Paint().apply {
        color = Color.argb(255, 60, 60, 65)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val speakerIconTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val closeButtonRect = RectF()
    private var lastPopupBox: RectF? = null
    private var speakerIconRect: RectF? = null
    private var saveIconRect: RectF? = null

    // --- Drag/lasso tracking --------------------------------------------
    private var dragPath: Path? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragMoved = false
    private val minDragDistance = 24f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val margin = 24f
        val size = 96f

        if (closeButtonCenter != null) {
            val (cx, cy) = closeButtonCenter
            val left = (cx - size / 2).coerceIn(margin, w - margin - size)
            val top = (cy - size / 2).coerceIn(margin, h - margin - size)
            closeButtonRect.set(left, top, left + size, top + size)
        } else {
            closeButtonRect.set(w - margin - size, margin, w - margin, margin + size)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(screenshot, 0f, 0f, null)

        for (line in lines) {
            canvas.drawRect(line.bounds, lineBoxPaint)
        }
        for (word in words) {
            val paint = if (learnedWords.contains(word.text)) learnedPaint else notLearnedPaint
            canvas.drawRect(word.bounds, paint)
        }
        for (hit in selectedHits) {
            canvas.drawRect(hit.bounds, selectedFillPaint)
        }

        dragPath?.let { path ->
            canvas.drawPath(path, lassoFillPaint)
            canvas.drawPath(path, lassoStrokePaint)
        }

        canvas.drawRoundRect(closeButtonRect, 20f, 20f, closeButtonBgPaint)
        canvas.drawText(
            "✕",
            closeButtonRect.centerX(),
            closeButtonRect.centerY() + 14f,
            closeButtonTextPaint
        )

        val sel = selection
        if (sel != null) {
            drawPopup(canvas, sel)
        } else {
            lastPopupBox = null
            speakerIconRect = null
            saveIconRect = null
        }
    }

    private fun buildLayout(text: String, paint: TextPaint, maxWidth: Int, maxLines: Int): StaticLayout {
        val safeWidth = maxWidth.coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun maxLineWidth(layout: StaticLayout): Float {
        var max = 0f
        for (i in 0 until layout.lineCount) max = maxOf(max, layout.getLineWidth(i))
        return max
    }

    /** Draws [text] with its top-left at ([x], [topY]) rather than baseline-aligned. */
    private fun drawTextTopAligned(canvas: Canvas, text: String, x: Float, topY: Float, paint: Paint) {
        canvas.drawText(text, x, topY - paint.fontMetrics.top, paint)
    }

    private fun drawPopup(canvas: Canvas, sel: Selection) {
        val paddingH = 32f
        val paddingV = 24f
        val gap = 12f
        val speakerSize = 56f
        val speakerReserve = speakerSize + 16f

        val original = sel.text
        val translated = if (sel.loading) "Translating…" else (sel.translated ?: "")
        val sourceLabel = sel.source?.takeIf { !sel.loading }?.let { "$it · tap for more" }
        val showSaveIcon = sel.isSingleWord && !sel.loading
        val iconCount = if (showSaveIcon) 2 else 1

        val maxBoxWidth = width - 40f
        val maxTextWidth = (maxBoxWidth - paddingH * 2 - speakerReserve * iconCount).toInt()

        val originalLayout = buildLayout(original, popupOriginalPaint, maxTextWidth, maxLines = 3)
        val translatedLayout = buildLayout(translated, popupTranslatedPaint, maxTextWidth, maxLines = 8)

        val sourceWidth = sourceLabel?.let { popupSourcePaint.measureText(it) } ?: 0f
        val neededContentWidth = maxOf(
            maxLineWidth(originalLayout), maxLineWidth(translatedLayout), sourceWidth
        )
        val boxWidth = (neededContentWidth + paddingH * 2 + speakerReserve * iconCount)
            .coerceAtMost(maxBoxWidth)
            .coerceAtLeast(160f)

        var contentHeight = originalLayout.height.toFloat() + gap + translatedLayout.height.toFloat()
        val sourceLineHeight = popupSourcePaint.let { it.fontMetrics.descent - it.fontMetrics.ascent }
        if (sourceLabel != null) contentHeight += gap + sourceLineHeight
        val boxHeight = (contentHeight + paddingV * 2).coerceAtLeast(speakerReserve + paddingV)

        var left = sel.anchor.centerX() - boxWidth / 2
        left = left.coerceIn(20f, (width - boxWidth - 20f).coerceAtLeast(20f))

        var top = sel.anchor.top - boxHeight - 16f
        if (top < 20f) top = sel.anchor.bottom + 16f

        val box = RectF(left, top, left + boxWidth, top + boxHeight)
        canvas.drawRoundRect(box, 24f, 24f, popupBgPaint)

        canvas.save()
        canvas.translate(box.left + paddingH, box.top + paddingV)
        originalLayout.draw(canvas)
        canvas.translate(0f, originalLayout.height + gap)
        translatedLayout.draw(canvas)
        canvas.restore()

        if (sourceLabel != null) {
            val sourceTopY = box.top + paddingV + originalLayout.height + gap + translatedLayout.height + gap
            drawTextTopAligned(canvas, sourceLabel, box.left + paddingH, sourceTopY, popupSourcePaint)
        }

        val speakerRect = RectF(
            box.right - speakerSize - 12f, box.top + 12f,
            box.right - 12f, box.top + 12f + speakerSize
        )
        canvas.drawRoundRect(speakerRect, 14f, 14f, speakerIconBgPaint)
        canvas.drawText(
            "🔊", speakerRect.centerX(), speakerRect.centerY() + 10f, speakerIconTextPaint
        )
        speakerIconRect = speakerRect

        if (showSaveIcon) {
            val saveRect = RectF(
                speakerRect.left - speakerSize - 8f, box.top + 12f,
                speakerRect.left - 8f, box.top + 12f + speakerSize
            )
            canvas.drawRoundRect(saveRect, 14f, 14f, speakerIconBgPaint)
            canvas.drawText(
                if (sel.saved) "✓" else "🔖",
                saveRect.centerX(), saveRect.centerY() + 10f, speakerIconTextPaint
            )
            saveIconRect = saveRect
        } else {
            saveIconRect = null
        }

        // Only tappable-for-detail once it's done loading (nothing to expand into yet otherwise).
        lastPopupBox = if (!sel.loading) box else null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                dragStartY = event.y
                dragMoved = false
                dragPath = Path().apply { moveTo(event.x, event.y) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - dragStartX
                val dy = event.y - dragStartY
                if (hypot(dx, dy) > minDragDistance) dragMoved = true
                dragPath?.lineTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val path = dragPath
                dragPath = null
                if (dragMoved && path != null) {
                    handleLassoComplete(path)
                } else {
                    handleTap(event.x, event.y)
                }
                invalidate()
                return true
            }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        if (closeButtonRect.contains(x, y)) {
            onCloseRequested()
            return
        }

        val sel = selection
        val speakerRect = speakerIconRect
        if (sel != null && speakerRect != null && speakerRect.contains(x, y)) {
            onSpeak(sel.text)
            return
        }

        val saveRect = saveIconRect
        if (sel != null && saveRect != null && saveRect.contains(x, y) && !sel.saved) {
            val hanja = sel.dictEntries.firstOrNull()?.hanja
            val gloss = sel.translated ?: ""
            onSaveVocab(sel.text, hanja, gloss, sel.source ?: "Translation")
            selection = sel.copy(saved = true)
            invalidate()
            return
        }

        val popupBox = lastPopupBox
        if (sel != null && popupBox != null && popupBox.contains(x, y)) {
            onDefinitionTapped(
                WordLookupDetail(
                    original = sel.text,
                    entries = sel.dictEntries,
                    fallbackTranslation = sel.translated ?: "",
                    sourceLabel = sel.source ?: "Translation",
                    words = selectedHits.map { it.text }
                )
            )
            return
        }

        val tappedWord = words.firstOrNull { rectContains(it.bounds, x, y) }
        val tappedLine = lines.firstOrNull { rectContains(it.bounds, x, y) }
        val hit = tappedWord ?: tappedLine

        if (hit == null) {
            clearSelection()
            return
        }

        selectedHits = listOf(hit)
        // The containing line's full text, if this was a word (not a whole
        // line) tap - used as DeepL's "context" param in context-aware mode.
        val contextLine = if (tappedWord != null) {
            lines.firstOrNull { rectContains(it.bounds, x, y) }?.text?.takeIf { it != hit.text }
        } else {
            null
        }
        startTranslation(hit.text, RectF(hit.bounds), contextLine, isSingleWord = true)
    }

    private fun handleLassoComplete(path: Path) {
        path.close()
        val bounds = RectF()
        path.computeBounds(bounds, true)

        val clip = Region(0, 0, width, height)
        val region = Region().apply { setPath(path, clip) }

        val enclosed = words.filter { hit ->
            val cx = hit.bounds.centerX()
            val cy = hit.bounds.centerY()
            region.contains(cx, cy)
        }

        if (enclosed.isEmpty()) {
            clearSelection()
            return
        }

        // Reading order: top-to-bottom, then left-to-right within a line.
        val sorted = enclosed.sortedWith(
            compareBy({ (it.bounds.top / 20) }, { it.bounds.left })
        )
        val combinedText = sorted.joinToString(" ") { it.text }
        val unionBounds = RectF(sorted.first().bounds)
        for (hit in sorted.drop(1)) unionBounds.union(RectF(hit.bounds))

        selectedHits = sorted
        startTranslation(combinedText, unionBounds, isSingleWord = false)
    }

    private fun startTranslation(
        text: String,
        anchor: RectF,
        context: String? = null,
        isSingleWord: Boolean
    ) {
        val id = ++nextSelectionId
        selection = Selection(id, text, anchor, translated = null, loading = true, isSingleWord = isSingleWord)
        invalidate()

        // Context-aware mode (currently DeepL-only) is opt-in and only
        // meaningful when there's actual surrounding context to give, so
        // when active it takes priority over the (context-free) dictionary;
        // otherwise the dictionary is tried first as usual.
        val tryOnlineFirst = contextAwareEnabled && context != null

        scope.launch {
            var dictEntries: List<DictionaryEntry> = emptyList()
            var result: String
            var source: String

            suspend fun tryDictionary(): Boolean {
                dictEntries = runCatching { dictionaryLookup(text) }.getOrDefault(emptyList())
                return dictEntries.isNotEmpty()
            }

            if (tryOnlineFirst) {
                val online = runCatching { onlineTranslate(text, context) }.getOrNull()
                if (online != null) {
                    result = online.first
                    source = online.second
                } else if (tryDictionary()) {
                    result = DictionaryManager.formatEntries(dictEntries)
                    source = "Dictionary"
                } else {
                    result = runCatching { translationHelper.translate(text) }
                        .getOrElse { "(translation failed)" }
                    source = "On-device translation"
                }
            } else if (tryDictionary()) {
                result = DictionaryManager.formatEntries(dictEntries)
                source = "Dictionary"
            } else {
                val online = runCatching { onlineTranslate(text, null) }.getOrNull()
                if (online != null) {
                    result = online.first
                    source = online.second
                } else {
                    result = runCatching { translationHelper.translate(text) }
                        .getOrElse { "(translation failed)" }
                    source = "On-device translation"
                }
            }
            if (selection?.id == id) {
                selection = selection?.copy(
                    translated = result, loading = false, source = source, dictEntries = dictEntries
                )
                invalidate()
            }
        }
    }

    private fun clearSelection() {
        if (selection != null || selectedHits.isNotEmpty()) {
            selection = null
            selectedHits = emptyList()
            invalidate()
        }
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
