package com.tsm.wtlens

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
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

    private lateinit var onlineProviderGroup: RadioGroup
    private lateinit var onlineGuideText: TextView
    private lateinit var onlinePapagoFields: LinearLayout
    private lateinit var onlineDeeplFields: LinearLayout
    private lateinit var onlineGoogleFields: LinearLayout
    private lateinit var onlinePapagoClientIdEdit: EditText
    private lateinit var onlinePapagoClientSecretEdit: EditText
    private lateinit var onlineDeeplKeyEdit: EditText
    private lateinit var onlineGoogleKeyEdit: EditText
    private lateinit var onlineEnableSwitch: Switch
    private lateinit var onlineContextAwareSwitch: Switch
    private lateinit var onlineStatusText: TextView
    private lateinit var onlineTestButton: Button

    private lateinit var vocabEnabledSwitch: Switch
    private lateinit var vocabStatsText: TextView
    private lateinit var vocabGraduationText: TextView
    private lateinit var vocabReviewButton: Button

    private lateinit var homeTabLabel: TextView
    private lateinit var vocabTabLabel: TextView
    private lateinit var settingsTabLabel: TextView
    private lateinit var homeScroll: android.widget.ScrollView
    private lateinit var vocabScroll: android.widget.ScrollView
    private lateinit var settingsScroll: android.widget.ScrollView

    private enum class Tab { HOME, VOCAB, SETTINGS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F10"))
        }

        outer.addView(TextView(this).apply {
            text = "WebtoonLens"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(UiStyle.TEXT_PRIMARY)
            setPadding(dp(20), dp(28), dp(20), dp(14))
        })

        // --- Tab bar ---
        homeTabLabel = TextView(this).apply {
            text = "Home"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        vocabTabLabel = TextView(this).apply {
            text = "Vocab"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        settingsTabLabel = TextView(this).apply {
            text = "Settings"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(homeTabLabel)
            addView(vocabTabLabel)
            addView(settingsTabLabel)
        }
        outer.addView(tabBar)
        outer.addView(UiStyle.divider(this))

        val homePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val vocabContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        val settingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(24))
        }
        homeScroll = android.widget.ScrollView(this).apply { addView(homePage) }
        vocabScroll = android.widget.ScrollView(this).apply { addView(vocabContent) }
        settingsScroll = android.widget.ScrollView(this).apply { addView(settingsContent) }

        val pages = android.widget.FrameLayout(this).apply {
            addView(homeScroll)
            addView(vocabScroll)
            addView(settingsScroll)
        }
        outer.addView(
            pages,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        homeTabLabel.setOnClickListener { selectTab(Tab.HOME) }
        vocabTabLabel.setOnClickListener { selectTab(Tab.VOCAB) }
        settingsTabLabel.setOnClickListener { selectTab(Tab.SETTINGS) }

        // --- Home page: the primary reading-session actions ---
        statusText = TextView(this).apply {
            setTextColor(UiStyle.TEXT_SECONDARY)
            textSize = 14f
            setPadding(0, 0, 0, dp(16))
        }
        homePage.addView(statusText)

        val grantOverlayButton = Button(this).apply {
            text = "1. Grant overlay permission"
            setOnClickListener { requestOverlayPermission() }
        }
        UiStyle.styleAsChipButton(grantOverlayButton, this)
        homePage.addView(
            grantOverlayButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        )

        val startButton = Button(this).apply {
            text = "2. Start WebtoonLens bubble"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
        }
        startButton.background = GradientDrawable().apply {
            setColor(Color.parseColor("#2A6DB0"))
            cornerRadius = 28f * density
        }
        startButton.setPadding(dp(24), dp(16), dp(24), dp(16))
        startButton.setOnClickListener { startOverlay() }
        homePage.addView(
            startButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // --- Settings page: everything else, grouped into collapsible cards ---
        val visuals = addCollapsible(settingsContent, "Visuals", initiallyExpanded = true)

        // --- Size ---
        sizeValueText = TextView(this)
        visuals.addView(sizeValueText)
        visuals.addView(SeekBar(this).apply {
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
        visuals.addView(opacityValueText)
        visuals.addView(SeekBar(this).apply {
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
        visuals.addView(sectionLabel("Color"))
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
        visuals.addView(swatchRow)

        val resetPositionButton = Button(this).apply {
            text = "Reset bubble position"
            setOnClickListener {
                BubblePrefs.resetPosition(this@MainActivity)
            }
        }
        visuals.addView(resetPositionButton)

        visuals.addView(Switch(this).apply {
            text = "Auto-play pronunciation on tap"
            isChecked = TtsHelper.isAutoplayEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                TtsHelper.setAutoplayEnabled(this@MainActivity, isChecked)
            }
        })

        // --- Dictionary (offline + online) ---
        val dictionaryCategory = addCollapsible(settingsContent, "Dictionary", initiallyExpanded = false)

        val offline = addCollapsible(
            dictionaryCategory, "Offline dictionary", indentDp = 16, initiallyExpanded = false
        )
        offline.addView(TextView(this).apply {
            text = "By default, word lookups use on-device machine translation. " +
                "Download a real Korean-English dictionary for better definitions " +
                "(word tried first; falls back to translation if not found)."
            setPadding(0, 0, 0, 8)
        })

        dictionaryStatusText = TextView(this)
        offline.addView(dictionaryStatusText)

        dictionaryProgressBar = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            visibility = android.view.View.GONE
        }
        offline.addView(dictionaryProgressBar)

        dictionaryDownloadButton = Button(this).apply {
            setOnClickListener { onDictionaryButtonClicked() }
        }
        offline.addView(dictionaryDownloadButton)

        dictionaryEnableSwitch = Switch(this).apply {
            text = "Use offline dictionary"
            setOnCheckedChangeListener { _, isChecked ->
                DictionaryManager.setEnabled(this@MainActivity, isChecked)
            }
        }
        offline.addView(dictionaryEnableSwitch)

        offline.addView(TextView(this).apply {
            text = DictionaryManager.ATTRIBUTION
            textSize = 11f
            setPadding(0, 16, 0, 0)
            alpha = 0.7f
        })

        val online = addCollapsible(
            dictionaryCategory, "Online translation API", indentDp = 16, initiallyExpanded = false
        )
        buildOnlineTranslationSection(online)

        buildVocabularySection(vocabContent)

        setContentView(outer)
        selectTab(Tab.HOME)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }

    private fun selectTab(tab: Tab) {
        homeScroll.visibility = if (tab == Tab.HOME) android.view.View.VISIBLE else android.view.View.GONE
        vocabScroll.visibility = if (tab == Tab.VOCAB) android.view.View.VISIBLE else android.view.View.GONE
        settingsScroll.visibility = if (tab == Tab.SETTINGS) android.view.View.VISIBLE else android.view.View.GONE
        homeTabLabel.setTextColor(if (tab == Tab.HOME) UiStyle.ACCENT else UiStyle.TEXT_MUTED)
        vocabTabLabel.setTextColor(if (tab == Tab.VOCAB) UiStyle.ACCENT else UiStyle.TEXT_MUTED)
        settingsTabLabel.setTextColor(if (tab == Tab.SETTINGS) UiStyle.ACCENT else UiStyle.TEXT_MUTED)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshDictionaryUi()
        refreshOnlineUi()
        refreshVocabUi()
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

    private fun buildOnlineTranslationSection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "For the most accurate results, you can bring your own API key from a " +
                "translation service. This is tried after the dictionary and before " +
                "falling back to the on-device translator. Your key is stored encrypted " +
                "on this device only."
            setPadding(0, 0, 0, 8)
        })

        val currentProvider = OnlineTranslationManager.provider(this)
        onlineProviderGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val providerButtons = OnlineTranslationManager.Provider.entries.associateWith { provider ->
            RadioButton(this).apply {
                text = provider.label
                id = android.view.View.generateViewId()
                isChecked = provider == currentProvider
            }
        }
        providerButtons.values.forEach { onlineProviderGroup.addView(it) }
        root.addView(onlineProviderGroup)

        onlineGuideText = TextView(this).apply {
            textSize = 12f
            setPadding(0, 8, 0, 8)
            alpha = 0.85f
        }
        root.addView(onlineGuideText)

        onlinePapagoFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        onlinePapagoClientIdEdit = EditText(this).apply { hint = "Client ID" }
        onlinePapagoClientSecretEdit = EditText(this).apply {
            hint = "Client Secret"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        onlinePapagoFields.addView(onlinePapagoClientIdEdit)
        onlinePapagoFields.addView(onlinePapagoClientSecretEdit)
        root.addView(onlinePapagoFields)

        onlineDeeplFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        onlineDeeplKeyEdit = EditText(this).apply {
            hint = "DeepL API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        onlineDeeplFields.addView(onlineDeeplKeyEdit)
        root.addView(onlineDeeplFields)

        onlineGoogleFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        onlineGoogleKeyEdit = EditText(this).apply {
            hint = "Google Cloud API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        onlineGoogleFields.addView(onlineGoogleKeyEdit)
        root.addView(onlineGoogleFields)

        // Pre-fill any previously saved credentials.
        val (papagoId, papagoSecret) = OnlineTranslationManager.papagoCredentials(this)
        onlinePapagoClientIdEdit.setText(papagoId)
        onlinePapagoClientSecretEdit.setText(papagoSecret)
        onlineDeeplKeyEdit.setText(OnlineTranslationManager.deeplApiKey(this))
        onlineGoogleKeyEdit.setText(OnlineTranslationManager.googleApiKey(this))

        val saveButton = Button(this).apply {
            text = "Save credentials"
            setOnClickListener { saveOnlineCredentials() }
        }
        root.addView(saveButton)

        onlineStatusText = TextView(this)
        root.addView(onlineStatusText)

        onlineTestButton = Button(this).apply {
            text = "Test connection"
            setOnClickListener { testOnlineTranslation() }
        }
        root.addView(onlineTestButton)

        onlineEnableSwitch = Switch(this).apply {
            text = "Prefer online translation over on-device"
            setOnCheckedChangeListener { _, isChecked ->
                OnlineTranslationManager.setUserEnabled(this@MainActivity, isChecked)
                refreshOnlineUi()
            }
        }
        root.addView(onlineEnableSwitch)

        onlineContextAwareSwitch = Switch(this).apply {
            text = "Context-aware word definitions (DeepL only)"
            isChecked = OnlineTranslationManager.isContextAwareEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                OnlineTranslationManager.setContextAwareEnabled(this@MainActivity, isChecked)
            }
        }
        root.addView(onlineContextAwareSwitch)
        root.addView(TextView(this).apply {
            text = "When tapping a single word (not a circled phrase), sends the " +
                "containing sentence as extra context so DeepL can pick the right " +
                "sense — otherwise each word is translated in isolation. Adds a bit of " +
                "latency and skips the offline dictionary first, so it may feel slower. " +
                "No effect with Papago/Google — only DeepL's API supports this."
            textSize = 12f
            alpha = 0.8f
            setPadding(0, 0, 0, 8)
        })

        onlineProviderGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = providerButtons.entries.firstOrNull { it.value.id == checkedId }?.key
                ?: OnlineTranslationManager.Provider.NONE
            OnlineTranslationManager.setProvider(this, selected)
            refreshOnlineUi()
        }

        refreshOnlineUi()
    }

    private fun refreshOnlineUi() {
        val provider = OnlineTranslationManager.provider(this)
        onlinePapagoFields.visibility =
            if (provider == OnlineTranslationManager.Provider.PAPAGO) android.view.View.VISIBLE else android.view.View.GONE
        onlineDeeplFields.visibility =
            if (provider == OnlineTranslationManager.Provider.DEEPL) android.view.View.VISIBLE else android.view.View.GONE
        onlineGoogleFields.visibility =
            if (provider == OnlineTranslationManager.Provider.GOOGLE) android.view.View.VISIBLE else android.view.View.GONE

        onlineGuideText.text = when (provider) {
            OnlineTranslationManager.Provider.NONE -> ""
            OnlineTranslationManager.Provider.PAPAGO ->
                "Setup: sign up for a free Naver Cloud Platform account at ncloud.com, " +
                    "open Console → AI·Application Service → Papago Translation, create an " +
                    "application to enable the Translation API, then copy its Client ID and " +
                    "Client Secret below. Free tier: ~10,000 characters/day."
            OnlineTranslationManager.Provider.DEEPL ->
                "Setup: sign up at deepl.com/pro-api, then open Account → API Keys and copy " +
                    "your authentication key below. Free/low-volume keys end in \":fx\"."
            OnlineTranslationManager.Provider.GOOGLE ->
                "Setup: create a project at console.cloud.google.com, enable the \"Cloud " +
                    "Translation API\" under APIs & Services, then create an API key under " +
                    "Credentials and paste it below. Requires billing enabled on the project " +
                    "(includes a free monthly quota)."
        }

        val configured = OnlineTranslationManager.isConfigured(this)
        onlineEnableSwitch.isEnabled = configured
        onlineEnableSwitch.setOnCheckedChangeListener(null)
        onlineEnableSwitch.isChecked = OnlineTranslationManager.isUserEnabled(this) && configured
        onlineEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            OnlineTranslationManager.setUserEnabled(this@MainActivity, isChecked)
            refreshOnlineUi()
        }
        onlineTestButton.isEnabled = configured

        onlineStatusText.text = when {
            provider == OnlineTranslationManager.Provider.NONE -> "Not using an online provider."
            configured && OnlineTranslationManager.isActive(this) -> "Active: using ${provider.label}."
            configured -> "Credentials saved, but not enabled above."
            else -> "Enter and save credentials to use ${provider.label}."
        }
    }

    private fun saveOnlineCredentials() {
        when (OnlineTranslationManager.provider(this)) {
            OnlineTranslationManager.Provider.PAPAGO -> OnlineTranslationManager.setPapagoCredentials(
                this,
                onlinePapagoClientIdEdit.text.toString().trim(),
                onlinePapagoClientSecretEdit.text.toString().trim()
            )
            OnlineTranslationManager.Provider.DEEPL -> OnlineTranslationManager.setDeeplApiKey(
                this, onlineDeeplKeyEdit.text.toString().trim()
            )
            OnlineTranslationManager.Provider.GOOGLE -> OnlineTranslationManager.setGoogleApiKey(
                this, onlineGoogleKeyEdit.text.toString().trim()
            )
            OnlineTranslationManager.Provider.NONE -> {}
        }
        refreshOnlineUi()
    }

    private fun testOnlineTranslation() {
        onlineTestButton.isEnabled = false
        onlineStatusText.text = "Testing…"
        activityScope.launch {
            val result = OnlineTranslationManager.translate(this@MainActivity, "안녕하세요")
            onlineTestButton.isEnabled = true
            result.fold(
                onSuccess = { onlineStatusText.text = "Test succeeded: \"안녕하세요\" → \"$it\"" },
                onFailure = { onlineStatusText.text = "Test failed: ${it.message}" }
            )
        }
    }

    private fun buildVocabularySection(container: LinearLayout) {
        container.addView(TextView(this).apply {
            text = "Every single word you look up while reading is saved here automatically " +
                "for spaced-repetition review, Anki-style. Not-yet-saved words are highlighted " +
                "blue, saved-but-still-reviewing words purple, and learned/graduated words " +
                "grey (still tappable, just de-emphasized). Turn this off to keep the app " +
                "lighter-weight if you don't want it — previously saved words and review " +
                "still work either way, this just stops new auto-saving and the per-word " +
                "highlighting lookup."
            setPadding(0, 0, 0, 8)
        })

        vocabEnabledSwitch = Switch(this).apply {
            text = "Enable vocabulary saving"
            isChecked = VocabManager.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                VocabManager.setEnabled(this@MainActivity, isChecked)
            }
        }
        container.addView(vocabEnabledSwitch)

        vocabStatsText = TextView(this)
        container.addView(vocabStatsText)

        vocabReviewButton = Button(this).apply {
            text = "Review now"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, VocabReviewActivity::class.java))
            }
        }
        container.addView(vocabReviewButton)

        container.addView(Button(this).apply {
            text = "Browse saved words"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, VocabBrowseActivity::class.java))
            }
        })

        container.addView(sectionLabel("Graduation threshold"))
        vocabGraduationText = TextView(this)
        container.addView(vocabGraduationText)
        container.addView(SeekBar(this).apply {
            max = 89 // 1..90 days
            progress = VocabManager.graduationDays(this@MainActivity) - 1
            updateGraduationLabel(VocabManager.graduationDays(this@MainActivity))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val days = progress + 1
                    updateGraduationLabel(days)
                    if (fromUser) VocabManager.setGraduationDays(this@MainActivity, days)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        refreshVocabUi()
    }

    private fun updateGraduationLabel(days: Int) {
        vocabGraduationText.text = "A word is \"learned\" once its review interval reaches $days day(s)."
    }

    private fun refreshVocabUi() {
        vocabEnabledSwitch.setOnCheckedChangeListener(null)
        vocabEnabledSwitch.isChecked = VocabManager.isEnabled(this)
        vocabEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            VocabManager.setEnabled(this@MainActivity, isChecked)
        }

        activityScope.launch {
            val (total, learned, due) = VocabManager.stats(this@MainActivity)
            vocabStatsText.text = "$total saved · $learned learned · $due due for review"
            vocabReviewButton.isEnabled = due > 0
        }
    }

    /**
     * Adds a clickable header to [parent] that toggles a content container's
     * visibility, and returns that container for callers to populate.
     */
    private fun addCollapsible(
        parent: LinearLayout,
        title: String,
        indentDp: Int = 0,
        initiallyExpanded: Boolean = false
    ): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val isTopLevel = indentDp == 0

        val arrow = TextView(this).apply {
            text = if (initiallyExpanded) "▾" else "▸"
            textSize = 15f
            setTextColor(if (isTopLevel) UiStyle.TEXT_PRIMARY else UiStyle.TEXT_SECONDARY)
            setPadding(0, 0, dp(10), 0)
        }
        val titleView = TextView(this).apply {
            text = title
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = if (isTopLevel) 17f else 14f
            setTextColor(if (isTopLevel) UiStyle.TEXT_PRIMARY else UiStyle.TEXT_SECONDARY)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = if (isTopLevel) dp(20) else dp(indentDp)
            val vPad = if (isTopLevel) dp(18) else dp(14)
            setPadding(hPad, vPad, dp(20), vPad)
            isClickable = true
            isFocusable = true
            addView(arrow)
            addView(titleView)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initiallyExpanded) android.view.View.VISIBLE else android.view.View.GONE
            val hPad = if (isTopLevel) dp(20) else dp(indentDp)
            setPadding(hPad, 0, dp(20), if (isTopLevel) dp(18) else dp(10))
        }

        header.setOnClickListener {
            val expanded = content.visibility == android.view.View.VISIBLE
            content.visibility = if (expanded) android.view.View.GONE else android.view.View.VISIBLE
            arrow.text = if (expanded) "▸" else "▾"
        }

        if (isTopLevel) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = UiStyle.cardBackground(16f, density)
                clipToOutline = true
                addView(header)
                addView(content)
            }
            parent.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14) }
            )
        } else {
            parent.addView(header)
            parent.addView(content)
        }
        return content
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
