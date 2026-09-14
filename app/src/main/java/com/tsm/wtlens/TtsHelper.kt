package com.tsm.wtlens

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Thin wrapper around Android's built-in TextToSpeech for reading Korean
 * text/phrases aloud. Uses whatever TTS engine/voices are already on the
 * device (no bundled audio, no network call) - if the device has no Korean
 * TTS voice installed, [speak] silently does nothing rather than crashing
 * or speaking in the wrong language.
 */
class TtsHelper(context: Context) {

    private var tts: TextToSpeech? = null
    private var koreanAvailable = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.KOREAN)
                koreanAvailable = result != null &&
                    result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!koreanAvailable) {
                    Log.w(TAG, "Korean TTS voice not available on this device")
                }
            } else {
                Log.e(TAG, "TextToSpeech init failed: $status")
            }
        }
    }

    fun speak(text: String) {
        if (!koreanAvailable || text.isBlank()) return
        tts?.stop()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wtlens_tts")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "WebtoonLensTts"
        private const val PREFS_NAME = "tts_prefs"
        private const val KEY_AUTOPLAY = "autoplay_enabled"

        /** When on, tapping a single word speaks it aloud automatically. */
        fun isAutoplayEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTOPLAY, false)

        fun setAutoplayEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTOPLAY, enabled).apply()
        }
    }
}
