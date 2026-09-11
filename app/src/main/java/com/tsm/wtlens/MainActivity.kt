package com.tsm.wtlens

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Entry point: grant the "draw over other apps" permission, configure how
 * the bubble looks, then start it. Screen-capture (MediaProjection) consent
 * is requested separately, right before the first capture, via
 * [CaptureConsentActivity].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var sizeValueText: TextView
    private lateinit var opacityValueText: TextView
    private lateinit var swatchViews: List<android.view.View>

    private lateinit var dictionaryStatusText: TextView
    private lateinit var dictionaryEnableSwitch: Switch
    private lateinit var dictionaryDownloadButton: Button
    private lateinit var dictionaryProgressBar: ProgressBar
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isDownloadingDictionary = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        statusText = TextView(this)
        root.addView(statusText)

        val grantOverlayButton = Button(this).apply {
            text = "1. Grant overlay permission"
            setOnClickListener { requestOverlayPermission() }
        }
        root.addView(grantOverlayButton)

        root.addView(sectionLabel("Bubble appearance"))

        // --- Size ---
        sizeValueText = TextView(this)
        root.addView(sizeValueText)
        root.addView(SeekBar(this).apply {
            max = BubblePrefs.MAX_SIZE_DP - BubblePrefs.MIN_SIZE_DP
            progress = BubblePrefs.sizeDp(this@MainActivity) - BubblePrefs.MIN_SIZE_DP
            updateSizeLabel(BubblePrefs.sizeDp(this@MainActivity))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val dp = BubblePrefs.MIN_SIZE_DP + progress
                    updateSizeLabel(dp)
                    if (fromUser) {
                        BubblePrefs.prefs(this@MainActivity).edit()
                            .putInt(BubblePrefs.KEY_SIZE_DP, dp).apply()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // --- Opacity ---
        opacityValueText = TextView(this)
        root.addView(opacityValueText)
        root.addView(SeekBar(this).apply {
            max = 100 - BubblePrefs.MIN_OPACITY_PERCENT
            progress = BubblePrefs.opacityPercent(this@MainActivity) - BubblePrefs.MIN_OPACITY_PERCENT
            updateOpacityLabel(BubblePrefs.opacityPercent(this@MainActivity))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val percent = BubblePrefs.MIN_OPACITY_PERCENT + progress
                    updateOpacityLabel(percent)
                    if (fromUser) {
                        BubblePrefs.prefs(this@MainActivity).edit()
                            .putInt(BubblePrefs.KEY_OPACITY_PERCENT, percent).apply()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // --- Color ---
        root.addView(sectionLabel("Color"))
        val swatchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val currentColor = BubblePrefs.color(this)
        swatchViews = BubblePrefs.PRESET_COLORS.map { color ->
            makeSwatch(color, selected = color == currentColor) {
                BubblePrefs.prefs(this).edit().putInt(BubblePrefs.KEY_COLOR, color).apply()
                refreshSwatchSelection(color)
            }
        }
        swatchViews.forEach { swatchRow.addView(it) }
        root.addView(swatchRow)

        val resetPositionButton = Button(this).apply {
            text = "Reset bubble position"
            setOnClickListener {
                BubblePrefs.resetPosition(this@MainActivity)
            }
        }
        root.addView(resetPositionButton)

        // --- Dictionary ---
        root.addView(sectionLabel("Dictionary (optional)"))
        root.addView(TextView(this).apply {
            text = "By default, word lookups use on-device machine translation. " +
                "Download a real Korean-English dictionary for better definitions " +
                "(word tried first; falls back to translation if not found)."
            setPadding(0, 0, 0, 8)
        })

        dictionaryStatusText = TextView(this)
        root.addView(dictionaryStatusText)

        dictionaryProgressBar = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            visibility = android.view.View.GONE
        }
        root.addView(dictionaryProgressBar)

        dictionaryDownloadButton = Button(this).apply {
            setOnClickListener { onDictionaryButtonClicked() }
        }
        root.addView(dictionaryDownloadButton)

        dictionaryEnableSwitch = Switch(this).apply {
            text = "Use offline dictionary"
            setOnCheckedChangeListener { _, isChecked ->
                DictionaryManager.setEnabled(this@MainActivity, isChecked)
            }
        }
        root.addView(dictionaryEnableSwitch)

        root.addView(TextView(this).apply {
            text = DictionaryManager.ATTRIBUTION
            textSize = 11f
            setPadding(0, 16, 0, 0)
            alpha = 0.7f
        })

        val startButton = Button(this).apply {
            text = "2. Start WebtoonLens bubble"
            setOnClickListener { startOverlay() }
        }
        root.addView(startButton)

        setContentView(root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshDictionaryUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun refreshDictionaryUi() {
        if (isDownloadingDictionary) return
        val downloaded = DictionaryManager.isDownloaded(this)
        dictionaryEnableSwitch.isEnabled = downloaded
        dictionaryEnableSwitch.setOnCheckedChangeListener(null)
        dictionaryEnableSwitch.isChecked = DictionaryManager.isEnabled(this)
        dictionaryEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            DictionaryManager.setEnabled(this@MainActivity, isChecked)
        }

        dictionaryProgressBar.visibility = android.view.View.GONE
        if (downloaded) {
            val mb = "%.1f".format(DictionaryManager.dbSizeMb(this))
            dictionaryStatusText.text = "Dictionary downloaded ($mb MB)."
            dictionaryDownloadButton.text = "Delete dictionary"
        } else {
            dictionaryStatusText.text = "Dictionary not downloaded."
            dictionaryDownloadButton.text = "Download dictionary (~12 MB)"
        }
    }

    private fun onDictionaryButtonClicked() {
        if (DictionaryManager.isDownloaded(this)) {
            DictionaryManager.delete(this)
            refreshDictionaryUi()
            return
        }

        isDownloadingDictionary = true
        dictionaryDownloadButton.isEnabled = false
        dictionaryProgressBar.visibility = android.view.View.VISIBLE
        dictionaryProgressBar.isIndeterminate = false
        dictionaryProgressBar.progress = 0
        dictionaryStatusText.text = "Downloading dictionary…"

        activityScope.launch {
            val result = DictionaryManager.download(this@MainActivity) { progress ->
                when (progress) {
                    is DictionaryManager.DownloadProgress.Downloading -> {
                        if (progress.bytesTotal > 0) {
                            dictionaryProgressBar.isIndeterminate = false
                            val percent = ((progress.bytesDone * 100) / progress.bytesTotal).toInt()
                            dictionaryProgressBar.progress = percent
                            dictionaryStatusText.text = "Downloading dictionary… $percent%"
                        } else {
                            dictionaryProgressBar.isIndeterminate = true
                        }
                    }
                    is DictionaryManager.DownloadProgress.BuildingIndex -> {
                        dictionaryProgressBar.isIndeterminate = true
                        dictionaryStatusText.text = "Building offline index…"
                    }
                }
            }

            isDownloadingDictionary = false
            dictionaryDownloadButton.isEnabled = true
            result.onFailure {
                dictionaryStatusText.text = "Download failed: ${it.message}"
            }
            refreshDictionaryUi()
        }
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setPadding(0, 32, 0, 8)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun updateSizeLabel(dp: Int) {
        sizeValueText.text = "Bubble size: ${dp}dp"
    }

    private fun updateOpacityLabel(percent: Int) {
        opacityValueText.text = "Bubble opacity: $percent%"
    }

    private fun makeSwatch(color: Int, selected: Boolean, onClick: () -> Unit): android.view.View {
        return android.view.View(this).apply {
            val size = (48 * resources.displayMetrics.density).toInt()
            val margin = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, margin, margin, margin)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (selected) setStroke((3 * resources.displayMetrics.density).toInt(), Color.WHITE)
            }
            tag = color
            setOnClickListener { onClick() }
        }
    }

    private fun refreshSwatchSelection(selectedColor: Int) {
        swatchViews.forEach { view ->
            val color = view.tag as Int
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (color == selectedColor) {
                    setStroke((3 * resources.displayMetrics.density).toInt(), Color.WHITE)
                }
            }
        }
    }

    private fun refreshStatus() {
        statusText.text = if (Settings.canDrawOverlays(this)) {
            "Overlay permission: granted. Tap Start to enable the floating bubble."
        } else {
            "Overlay permission: NOT granted. Tap step 1 first."
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        // Ask for one-time screen-capture consent before starting the
        // bubble service; the resulting token is handed to OverlayService.
        startActivity(Intent(this, CaptureConsentActivity::class.java))
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1001
    }
}
