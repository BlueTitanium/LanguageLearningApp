package com.tsm.wtlens

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DictionaryEntry(val surface: String, val hanja: String?, val gloss: String)

/**
 * Optional offline Korean-English dictionary, downloaded on demand.
 *
 * Source: kengdic (Joe Speigle et al.), a plain static TSV file, dual
 * licensed CC-BY-SA 3.0 / LGPL 2.0 - https://github.com/garfieldnate/kengdic
 * Attribution is shown wherever this is offered in the UI, per the license.
 *
 * Word lookups via [TranslationHelper]'s on-device MT are serviceable but
 * imprecise for vocabulary study (no parts of speech, no distinguishing
 * multiple senses). This gives real dictionary glosses instead, when the
 * user opts in, falling back to MT when a word isn't found.
 */
object DictionaryManager {

    private const val TAG = "WebtoonLensDict"
    private const val SOURCE_URL =
        "https://raw.githubusercontent.com/garfieldnate/kengdic/master/kengdic.tsv"
    private const val PREFS_NAME = "dictionary_prefs"
    private const val KEY_ENABLED = "dictionary_enabled"
    const val ATTRIBUTION =
        "Dictionary data from kengdic (Joe Speigle et al.), CC-BY-SA 3.0 / LGPL 2.0 " +
            "— github.com/garfieldnate/kengdic"

    // A handful of common endings/particles, longest first, so a rough
    // "strip and re-look-up" pass can find the dictionary's base form for
    // simple conjugated/inflected OCR'd text. Not a real morphological
    // analyzer - just a heuristic to improve hit rate.
    private val STRIP_SUFFIXES = listOf(
        "습니다", "합니다", "했습니다", "하였습니다", "했어요", "하였어요", "해요", "이에요", "예요",
        "이었다", "였다", "했다", "한다", "이다", "에서", "에게", "한테", "까지", "부터", "으로",
        "면서", "니까", "지만", "거나", "든지", "고는", "다가",
        "는", "은", "이", "가", "을", "를", "와", "과", "도", "만", "의", "로", "고", "서", "면", "다", "에"
    ).sortedByDescending { it.length }

    private fun dbFile(context: Context): File = File(context.filesDir, "kengdic.db")
    private fun tempTsvFile(context: Context): File = File(context.cacheDir, "kengdic_download.tsv")
    private fun tempDbFile(context: Context): File = File(context.cacheDir, "kengdic_building.db")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDownloaded(context: Context): Boolean = dbFile(context).exists()

    fun isEnabled(context: Context): Boolean =
        isDownloaded(context) && prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun dbSizeMb(context: Context): Double = dbFile(context).length() / (1024.0 * 1024.0)

    fun delete(context: Context) {
        closeDb()
        dbFile(context).delete()
        setEnabled(context, false)
    }

    sealed class DownloadProgress {
        data class Downloading(val bytesDone: Long, val bytesTotal: Long) : DownloadProgress()
        object BuildingIndex : DownloadProgress()
    }

    suspend fun download(
        context: Context,
        onProgress: (DownloadProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tsv = tempTsvFile(context)
            downloadFile(tsv, onProgress)

            withContext(Dispatchers.Main) { onProgress(DownloadProgress.BuildingIndex) }
            val building = tempDbFile(context)
            building.delete()
            buildDatabase(tsv, building)
            tsv.delete()

            closeDb()
            val finalDb = dbFile(context)
            finalDb.delete()
            if (!building.renameTo(finalDb)) {
                building.copyTo(finalDb, overwrite = true)
                building.delete()
            }
            setEnabled(context, true)
        }.onFailure { e ->
            Log.e(TAG, "Dictionary download failed", e)
            tempTsvFile(context).delete()
            tempDbFile(context).delete()
        }
    }

    private suspend fun downloadFile(dest: File, onProgress: (DownloadProgress) -> Unit) {
        val connection = URL(SOURCE_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReportedPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            withContext(Dispatchers.Main) {
                                onProgress(DownloadProgress.Downloading(downloaded, total))
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun buildDatabase(tsv: File, dbOut: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(dbOut, null)
        db.execSQL("CREATE TABLE entries (surface TEXT NOT NULL, hanja TEXT, gloss TEXT NOT NULL)")
        db.beginTransaction()
        try {
            val statement = db.compileStatement(
                "INSERT INTO entries (surface, hanja, gloss) VALUES (?, ?, ?)"
            )
            var lineNumber = 0
            var inserted = 0
            tsv.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    lineNumber++
                    if (lineNumber == 1) continue // header
                    val cols = line.split("\t")
                    if (cols.size < 4) continue
                    val surface = cols[1].trim()
                    val hanja = cols[2].trim()
                    val gloss = cols[3].trim()
                    if (surface.isEmpty() || gloss.isEmpty()) continue

                    statement.clearBindings()
                    statement.bindString(1, surface)
                    if (hanja.isEmpty()) statement.bindNull(2) else statement.bindString(2, hanja)
                    statement.bindString(3, gloss)
                    statement.executeInsert()
                    inserted++

                    if (inserted % 5000 == 0) {
                        db.setTransactionSuccessful()
                        db.endTransaction()
                        db.beginTransaction()
                    }
                }
            }
            db.execSQL("CREATE INDEX idx_surface ON entries(surface)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    @Volatile private var readDb: SQLiteDatabase? = null

    private fun openDb(context: Context): SQLiteDatabase? {
        readDb?.let { return it }
        val file = dbFile(context)
        if (!file.exists()) return null
        return synchronized(this) {
            readDb ?: SQLiteDatabase.openDatabase(
                file.path, null, SQLiteDatabase.OPEN_READONLY
            ).also { readDb = it }
        }
    }

    private fun closeDb() {
        synchronized(this) {
            readDb?.close()
            readDb = null
        }
    }

    // OCR frequently attaches trailing/leading punctuation to a "word" that
    // has nothing to do with the word itself (e.g. "아니잖아?", "하지만,").
    private val TRIM_CHARS = charArrayOf(
        '.', ',', '!', '?', ';', ':', '"', '\'', '“', '”', '‘', '’', '…',
        '(', ')', '[', ']', '~', ' ', '　'
    )

    /**
     * The single best-guess canonical/base form of [word] - the
     * punctuation-trimmed text, or its KOMORAN dictionary root if it's a
     * conjugated single-word predicate. Used to key vocabulary entries and
     * word-highlighting so conjugated forms of the same word are treated as
     * the same vocab item. Pure text/morphology, no dictionary DB needed.
     */
    suspend fun canonicalForm(word: String): String {
        val cleaned = word.trim(*TRIM_CHARS)
        if (cleaned.isEmpty()) return word
        if (!cleaned.contains(' ')) {
            KoreanMorphAnalyzer.findPredicateRoot(cleaned)?.let { return it }
        }
        return cleaned
    }

    suspend fun lookup(context: Context, word: String): List<DictionaryEntry> =
        withContext(Dispatchers.IO) {
            val cleaned = word.trim(*TRIM_CHARS)
            if (cleaned.isEmpty()) return@withContext emptyList()
            val db = openDb(context) ?: return@withContext emptyList()

            // Try the linguistically-correct root first (handles irregular
            // conjugations, e.g. 들어요 -> 듣다), then fall back to naive
            // suffix stripping for whatever KOMORAN doesn't resolve. Only
            // for single-word lookups: on a full sentence/phrase, this would
            // just find ONE predicate anywhere in it and, if that happened
            // to also be a real dictionary word, wrongly hijack the whole
            // phrase's lookup into that one verb's definition.
            val morphRoot = if (!cleaned.contains(' ')) {
                KoreanMorphAnalyzer.findPredicateRoot(cleaned).also {
                    Log.d(TAG, "'$cleaned' -> KOMORAN root: $it")
                }
            } else {
                null
            }

            val candidates = buildList {
                add(cleaned)
                morphRoot?.let { add(it) }
                for (suffix in STRIP_SUFFIXES) {
                    if (cleaned.length > suffix.length && cleaned.endsWith(suffix)) {
                        add(cleaned.removeSuffix(suffix))
                    }
                }
            }.distinct()

            for (candidate in candidates) {
                val results = queryExact(db, candidate)
                if (results.isNotEmpty()) return@withContext results
            }
            emptyList()
        }

    private fun queryExact(db: SQLiteDatabase, surface: String): List<DictionaryEntry> {
        val entries = mutableListOf<DictionaryEntry>()
        db.rawQuery(
            "SELECT surface, hanja, gloss FROM entries WHERE surface = ? LIMIT 5",
            arrayOf(surface)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries += DictionaryEntry(
                    surface = cursor.getString(0),
                    hanja = cursor.getString(1),
                    gloss = cursor.getString(2)
                )
            }
        }
        return entries
    }

    fun formatEntries(entries: List<DictionaryEntry>): String =
        entries.take(3).joinToString("; ") { it.gloss }

    /** All senses (uncapped, up to 10), for saving to vocab - not just the top few shown in the popup. */
    fun formatEntriesFull(entries: List<DictionaryEntry>): String =
        entries.take(10).joinToString("; ") { it.gloss }
}
