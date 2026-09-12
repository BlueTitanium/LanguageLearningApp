package com.tsm.wtlens

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Optional opt-in hook for higher-accuracy online machine translation,
 * used (when enabled and configured) in preference to the on-device ML Kit
 * translator for anything the offline dictionary doesn't have an entry
 * for. The user supplies their own account/API key for whichever provider
 * they choose - nothing is bundled or hardcoded.
 */
object OnlineTranslationManager {

    private const val TAG = "WebtoonLensOnlineMT"
    private const val PREFS_NAME = "online_translation_prefs"

    enum class Provider(val label: String, val storageKey: String) {
        NONE("None", "none"),
        PAPAGO("Naver Papago", "papago"),
        DEEPL("DeepL", "deepl"),
        GOOGLE("Google Cloud Translation", "google")
    }

    private const val KEY_PROVIDER = "provider"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PAPAGO_CLIENT_ID = "papago_client_id"
    private const val KEY_PAPAGO_CLIENT_SECRET = "papago_client_secret"
    private const val KEY_DEEPL_API_KEY = "deepl_api_key"
    private const val KEY_GOOGLE_API_KEY = "google_api_key"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun provider(context: Context): Provider {
        val stored = prefs(context).getString(KEY_PROVIDER, Provider.NONE.storageKey)
        return Provider.entries.firstOrNull { it.storageKey == stored } ?: Provider.NONE
    }

    fun setProvider(context: Context, provider: Provider) {
        prefs(context).edit().putString(KEY_PROVIDER, provider.storageKey).apply()
    }

    fun isUserEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setUserEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun papagoCredentials(context: Context): Pair<String, String> {
        val p = prefs(context)
        return (p.getString(KEY_PAPAGO_CLIENT_ID, "") ?: "") to
            (p.getString(KEY_PAPAGO_CLIENT_SECRET, "") ?: "")
    }

    fun setPapagoCredentials(context: Context, clientId: String, clientSecret: String) {
        prefs(context).edit()
            .putString(KEY_PAPAGO_CLIENT_ID, clientId)
            .putString(KEY_PAPAGO_CLIENT_SECRET, clientSecret)
            .apply()
    }

    fun deeplApiKey(context: Context): String = prefs(context).getString(KEY_DEEPL_API_KEY, "") ?: ""

    fun setDeeplApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_DEEPL_API_KEY, key).apply()
    }

    fun googleApiKey(context: Context): String = prefs(context).getString(KEY_GOOGLE_API_KEY, "") ?: ""

    fun setGoogleApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_GOOGLE_API_KEY, key).apply()
    }

    fun isConfigured(context: Context): Boolean = when (provider(context)) {
        Provider.NONE -> false
        Provider.PAPAGO -> papagoCredentials(context).let { it.first.isNotBlank() && it.second.isNotBlank() }
        Provider.DEEPL -> deeplApiKey(context).isNotBlank()
        Provider.GOOGLE -> googleApiKey(context).isNotBlank()
    }

    /** Whether online translation should actually be used right now. */
    fun isActive(context: Context): Boolean =
        isUserEnabled(context) && isConfigured(context) && provider(context) != Provider.NONE

    /**
     * Translates Korean text to English using whichever provider is
     * configured. Returns failure (never throws) so callers can cleanly
     * fall back to on-device translation.
     */
    suspend fun translate(context: Context, text: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (provider(context)) {
                    Provider.NONE -> error("No online provider configured")
                    Provider.PAPAGO -> {
                        val (clientId, clientSecret) = papagoCredentials(context)
                        papagoTranslate(clientId, clientSecret, text)
                    }
                    Provider.DEEPL -> deeplTranslate(deeplApiKey(context), text)
                    Provider.GOOGLE -> googleTranslate(googleApiKey(context), text)
                }
            }.onFailure { e -> Log.e(TAG, "Online translation failed", e) }
        }

    private fun formEncode(params: Map<String, String>): ByteArray =
        params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }.toByteArray(Charsets.UTF_8)

    private fun papagoTranslate(clientId: String, clientSecret: String, text: String): String {
        val url = URL("https://papago.apigw.ntruss.com/nmt/v1/translation")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("X-NCP-APIGW-API-KEY-ID", clientId)
        conn.setRequestProperty("X-NCP-APIGW-API-KEY", clientSecret)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        try {
            conn.outputStream.use {
                it.write(formEncode(mapOf("source" to "ko", "target" to "en", "text" to text)))
            }
            checkResponseCode(conn)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            return JSONObject(body).getJSONObject("message").getJSONObject("result")
                .getString("translatedText")
        } finally {
            conn.disconnect()
        }
    }

    private fun deeplTranslate(apiKey: String, text: String): String {
        val base = if (apiKey.trim().endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val url = URL("$base/v2/translate")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Authorization", "DeepL-Auth-Key $apiKey")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        try {
            conn.outputStream.use {
                it.write(formEncode(mapOf("text" to text, "source_lang" to "KO", "target_lang" to "EN-US")))
            }
            checkResponseCode(conn)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            return JSONObject(body).getJSONArray("translations").getJSONObject(0).getString("text")
        } finally {
            conn.disconnect()
        }
    }

    private fun googleTranslate(apiKey: String, text: String): String {
        val url = URL(
            "https://translation.googleapis.com/language/translate/v2?key=" +
                URLEncoder.encode(apiKey, "UTF-8")
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        try {
            val requestBody = JSONObject().apply {
                put("q", text)
                put("source", "ko")
                put("target", "en")
                put("format", "text")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(requestBody.toString())
            }
            checkResponseCode(conn)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            return JSONObject(body).getJSONObject("data").getJSONArray("translations")
                .getJSONObject(0).getString("translatedText")
        } finally {
            conn.disconnect()
        }
    }

    private fun checkResponseCode(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code !in 200..299) {
            val errorBody = runCatching {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
            }.getOrNull()
            error("HTTP $code${if (errorBody != null) ": $errorBody" else ""}")
        }
    }
}
