package com.tsm.wtlens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import androidx.core.content.IntentCompat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var mediaProjection: MediaProjection? = null
    private var screenCapture: ScreenCapture? = null
    private val ocrHelper = OcrHelper()
    private val translationHelper = TranslationHelper()

    private var bubbleView: View? = null
    private var captureOverlayView: CaptureOverlayView? = null
    private var wordDetailView: WordDetailView? = null
    private var isCapturing = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        Log.d(TAG, "handleStart")
        try {
            // Must call startForeground (with the mediaProjection type, on
            // API 29+) before touching any MediaProjection APIs.
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    0
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return
        }

        if (mediaProjection == null) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, MISSING_RESULT_CODE)
            val resultData = IntentCompat.getParcelableExtra(
                intent, EXTRA_RESULT_DATA, Intent::class.java
            )
            Log.d(
                TAG,
                "extras keys=${intent.extras?.keySet()?.joinToString()} " +
                    "resultCode=$resultCode hasData=${resultData != null}"
            )
            if (resultCode == MISSING_RESULT_CODE || resultData == null) {
                Log.e(TAG, "Missing MediaProjection result data, stopping")
                stopSelf()
                return
            }
            try {
                val projectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = projectionManager.getMediaProjection(resultCode, resultData)
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopSelf()
                    }
                }, null)
                mediaProjection = projection
                screenCapture = ScreenCapture(projection)
            } catch (e: Exception) {
                Log.e(TAG, "getMediaProjection failed", e)
                stopSelf()
                return
            }
        }

        if (bubbleView == null) {
            try {
                addBubble()
                Log.d(TAG, "Bubble added")
            } catch (e: Exception) {
                Log.e(TAG, "addBubble failed", e)
            }
        }

        // Kick off the translation model download early so the first tap
        // doesn't have to wait for it.
        serviceScope.launch {
            runCatching { translationHelper.ensureModelDownloaded() }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "webtoonlens_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }

        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_content_text))
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    // --- Floating bubble -----------------------------------------------

    private var bubbleParams: WindowManager.LayoutParams? = null

    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                BubblePrefs.KEY_SIZE_DP, BubblePrefs.KEY_OPACITY_PERCENT, BubblePrefs.KEY_COLOR ->
                    applyBubbleAppearance()
            }
        }

    private fun addBubble() {
        BubblePrefs.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)

        val bubble = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble_dots)
            val density = resources.displayMetrics.density
            val padding = (12 * density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        val (savedX, savedY) = BubblePrefs.savedPosition(this) ?: (40 to 300)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }
        bubbleParams = params

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        bubble.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        BubblePrefs.savePosition(this@OverlayService, params.x, params.y)
                    } else {
                        onBubbleTapped()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubble, params)
        bubbleView = bubble
        applyBubbleAppearance()
    }

    private fun applyBubbleAppearance() {
        val bubble = bubbleView as? ImageView ?: return
        val density = resources.displayMetrics.density
        val sizePx = (BubblePrefs.sizeDp(this) * density).toInt()
        val opacity = BubblePrefs.opacityPercent(this) / 100f
        val color = BubblePrefs.color(this)

        val background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }
        bubble.background = background
        bubble.alpha = opacity

        val params = bubbleParams
        if (params != null && (params.width != sizePx || params.height != sizePx)) {
            params.width = sizePx
            params.height = sizePx
            runCatching { windowManager.updateViewLayout(bubble, params) }
        }
    }

    private fun onBubbleTapped() {
        if (isCapturing) return
        val capture = screenCapture ?: return
        isCapturing = true
        // Hide the bubble *before* capturing so it doesn't bake itself into
        // the screenshot; give the compositor a moment to redraw without it.
        bubbleView?.visibility = View.GONE

        serviceScope.launch {
            try {
                delay(120)
                val bitmap = capture.captureFrame(windowManager)
                val (words, lines) = ocrHelper.recognize(bitmap)
                showCaptureOverlay(bitmap, words, lines)
            } catch (e: Exception) {
                Log.e(TAG, "capture/OCR failed", e)
                bubbleView?.visibility = View.VISIBLE
            } finally {
                isCapturing = false
            }
        }
    }

    // --- Full-screen capture overlay ------------------------------------

    private fun showCaptureOverlay(
        bitmap: android.graphics.Bitmap,
        words: List<OcrHit>,
        lines: List<OcrHit>
    ) {
        bubbleView?.visibility = View.GONE

        val bubbleLayoutParams = bubbleParams
        val bubbleCenterX = bubbleLayoutParams?.let { it.x + it.width / 2f }
        val bubbleCenterY = bubbleLayoutParams?.let { it.y + it.height / 2f }

        val overlay = CaptureOverlayView(
            context = this,
            screenshot = bitmap,
            words = words,
            lines = lines,
            closeButtonCenter = if (bubbleCenterX != null && bubbleCenterY != null) {
                bubbleCenterX to bubbleCenterY
            } else {
                null
            },
            translationHelper = translationHelper,
            dictionaryLookup = { word ->
                if (DictionaryManager.isEnabled(this)) {
                    val entries = DictionaryManager.lookup(this, word)
                    Log.d(TAG, "dictionary lookup '$word' -> ${entries.size} entries")
                    entries
                } else {
                    emptyList()
                }
            },
            onlineTranslate = { text ->
                if (OnlineTranslationManager.isActive(this)) {
                    val provider = OnlineTranslationManager.provider(this)
                    OnlineTranslationManager.translate(this, text).fold(
                        onSuccess = { translated ->
                            Log.d(TAG, "online translate via ${provider.label} succeeded")
                            translated to provider.label
                        },
                        onFailure = { e ->
                            Log.e(TAG, "online translate via ${provider.label} failed", e)
                            null
                        }
                    )
                } else {
                    null
                }
            },
            scope = serviceScope,
            onCloseRequested = { removeCaptureOverlay() },
            onDefinitionTapped = { detail ->
                serviceScope.launch {
                    val isSingleWord = detail.words.size <= 1
                    val breakdown = if (!isSingleWord) buildSentenceBreakdown(detail.words) else emptyList()
                    val conjugationRoot = if (isSingleWord) {
                        KoreanMorphAnalyzer.findPredicateRoot(detail.original)
                    } else {
                        null
                    }
                    showWordDetail(detail.copy(breakdown = breakdown, conjugationRoot = conjugationRoot))
                }
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(overlay, params)
        overlay.requestFocus()
        captureOverlayView = overlay
    }

    private fun removeCaptureOverlay() {
        removeWordDetail()
        captureOverlayView?.let { windowManager.removeView(it) }
        captureOverlayView = null
        bubbleView?.visibility = View.VISIBLE
    }

    // --- Word detail panel ------------------------------------------------

    /**
     * Per-word role (via [SentenceAnalyzer]'s particle heuristic) and gloss.
     * Deliberately uses the dictionary/on-device translator for these small
     * per-word glosses rather than the user's (possibly quota-limited, paid)
     * online API - that's reserved for the headline phrase translation.
     */
    private suspend fun buildSentenceBreakdown(words: List<String>): List<SentenceWordBreakdown> {
        return words.mapIndexed { index, word ->
            val role = SentenceAnalyzer.classifyRole(word, isLast = index == words.lastIndex)
            val gloss = runCatching {
                if (DictionaryManager.isEnabled(this)) {
                    val entries = DictionaryManager.lookup(this, word)
                    if (entries.isNotEmpty()) entries.first().gloss else translationHelper.translate(word)
                } else {
                    translationHelper.translate(word)
                }
            }.getOrNull()
            SentenceWordBreakdown(word, role, gloss)
        }
    }

    private fun showWordDetail(detail: WordLookupDetail) {
        removeWordDetail()
        val view = WordDetailView(this, detail, onDismiss = { removeWordDetail() })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(view, params)
        view.requestFocus()
        wordDetailView = view
    }

    private fun removeWordDetail() {
        wordDetailView?.let { runCatching { windowManager.removeView(it) } }
        wordDetailView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            BubblePrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        removeCaptureOverlay()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        ocrHelper.close()
        translationHelper.close()
        screenCapture?.release()
        screenCapture = null
        mediaProjection?.stop()
        mediaProjection = null
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_START = "com.tsm.wtlens.action.START"
        const val ACTION_STOP = "com.tsm.wtlens.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIF_ID = 42
        private const val TAG = "WebtoonLens"
        private const val MISSING_RESULT_CODE = Int.MIN_VALUE
    }
}
