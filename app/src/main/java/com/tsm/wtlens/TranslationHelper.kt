package com.tsm.wtlens

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Thin wrapper around ML Kit's on-device Korean->English translator.
 * The model (~30MB) downloads once, on Wi-Fi by default, and then works
 * fully offline — no API key, no per-request network call.
 */
class TranslationHelper {

    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.KOREAN)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )

    private var modelReady = false

    suspend fun ensureModelDownloaded(requireWifi: Boolean = true) {
        if (modelReady) return
        val conditions = DownloadConditions.Builder().apply {
            if (requireWifi) requireWifi()
        }.build()
        translator.downloadModelIfNeeded(conditions).await()
        modelReady = true
    }

    suspend fun translate(korean: String): String {
        ensureModelDownloaded()
        return translator.translate(korean).await()
    }

    fun close() = translator.close()
}
