package com.tsm.wtlens

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ReviewRating { AGAIN, HARD, GOOD, EASY }

/** A word's vocab status, for on-screen highlighting. */
enum class WordVocabStatus { NOT_SAVED, IN_PROGRESS, LEARNED }

data class VocabEntry(
    val id: Long,
    val surface: String,
    val hanja: String?,
    val gloss: String,
    val source: String,
    val addedAt: Long,
    val easeFactor: Double,
    val intervalDays: Int,
    val repetitions: Int,
    val dueAt: Long,
    val lastReviewedAt: Long?,
    val learned: Boolean
)

/**
 * User's personal saved-vocabulary list, with Anki-style (SM-2) spaced
 * repetition scheduling. Entirely local, user-generated data - separate
 * from the bundled/downloaded dictionary DB.
 */
object VocabManager {

    private const val DB_NAME = "vocab.db"
    private const val PREFS_NAME = "vocab_prefs"
    private const val KEY_GRADUATION_DAYS = "graduation_days"
    const val DEFAULT_GRADUATION_DAYS = 21
    private const val MIN_EASE = 1.3

    private fun dbFile(context: Context): File = File(context.filesDir, DB_NAME)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun graduationDays(context: Context): Int =
        prefs(context).getInt(KEY_GRADUATION_DAYS, DEFAULT_GRADUATION_DAYS)

    fun setGraduationDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_GRADUATION_DAYS, days.coerceIn(1, 365)).apply()
    }

    @Volatile private var db: SQLiteDatabase? = null

    private fun openDb(context: Context): SQLiteDatabase {
        db?.let { return it }
        return synchronized(this) {
            db ?: SQLiteDatabase.openOrCreateDatabase(dbFile(context), null).also {
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS vocab (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        surface TEXT NOT NULL UNIQUE,
                        hanja TEXT,
                        gloss TEXT NOT NULL,
                        source TEXT NOT NULL,
                        added_at INTEGER NOT NULL,
                        ease_factor REAL NOT NULL DEFAULT 2.5,
                        interval_days INTEGER NOT NULL DEFAULT 0,
                        repetitions INTEGER NOT NULL DEFAULT 0,
                        due_at INTEGER NOT NULL,
                        last_reviewed_at INTEGER,
                        learned INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db = it
            }
        }
    }

    private fun rowToEntry(c: android.database.Cursor): VocabEntry = VocabEntry(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        surface = c.getString(c.getColumnIndexOrThrow("surface")),
        hanja = c.getString(c.getColumnIndexOrThrow("hanja")),
        gloss = c.getString(c.getColumnIndexOrThrow("gloss")),
        source = c.getString(c.getColumnIndexOrThrow("source")),
        addedAt = c.getLong(c.getColumnIndexOrThrow("added_at")),
        easeFactor = c.getDouble(c.getColumnIndexOrThrow("ease_factor")),
        intervalDays = c.getInt(c.getColumnIndexOrThrow("interval_days")),
        repetitions = c.getInt(c.getColumnIndexOrThrow("repetitions")),
        dueAt = c.getLong(c.getColumnIndexOrThrow("due_at")),
        lastReviewedAt = if (c.isNull(c.getColumnIndexOrThrow("last_reviewed_at"))) {
            null
        } else {
            c.getLong(c.getColumnIndexOrThrow("last_reviewed_at"))
        },
        learned = c.getInt(c.getColumnIndexOrThrow("learned")) != 0
    )

    suspend fun save(context: Context, surface: String, hanja: String?, gloss: String, source: String) {
        withContext(Dispatchers.IO) {
            val database = openDb(context)
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("surface", surface)
                put("hanja", hanja)
                put("gloss", gloss)
                put("source", source)
                put("added_at", now)
                put("due_at", now)
            }
            database.insertWithOnConflict("vocab", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    suspend fun isSaved(context: Context, surface: String): Boolean = withContext(Dispatchers.IO) {
        openDb(context).rawQuery("SELECT 1 FROM vocab WHERE surface = ? LIMIT 1", arrayOf(surface))
            .use { it.moveToFirst() }
    }

    /**
     * Which of [surfaces] are already "learned" (graduated). Anything not
     * present in the result (never saved, or saved but not yet graduated)
     * should be treated as "not learned" by callers.
     */
    suspend fun learnedStatus(context: Context, surfaces: Collection<String>): Map<String, Boolean> =
        withContext(Dispatchers.IO) {
            if (surfaces.isEmpty()) return@withContext emptyMap()
            val database = openDb(context)
            val result = mutableMapOf<String, Boolean>()
            val placeholders = surfaces.joinToString(",") { "?" }
            database.rawQuery(
                "SELECT surface, learned FROM vocab WHERE surface IN ($placeholders)",
                surfaces.toTypedArray()
            ).use { c ->
                while (c.moveToNext()) {
                    result[c.getString(0)] = c.getInt(1) != 0
                }
            }
            result
        }

    suspend fun dueCount(context: Context): Int = withContext(Dispatchers.IO) {
        openDb(context).rawQuery(
            "SELECT COUNT(*) FROM vocab WHERE due_at <= ?", arrayOf(System.currentTimeMillis().toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    suspend fun stats(context: Context): Triple<Int, Int, Int> = withContext(Dispatchers.IO) {
        val database = openDb(context)
        fun count(where: String, args: Array<String> = emptyArray()): Int =
            database.rawQuery("SELECT COUNT(*) FROM vocab $where", args)
                .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val total = count("")
        val learned = count("WHERE learned = 1")
        val due = count("WHERE due_at <= ?", arrayOf(System.currentTimeMillis().toString()))
        Triple(total, learned, due)
    }

    suspend fun dueEntries(context: Context, limit: Int = 30): List<VocabEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<VocabEntry>()
        openDb(context).rawQuery(
            "SELECT * FROM vocab WHERE due_at <= ? ORDER BY due_at ASC LIMIT ?",
            arrayOf(System.currentTimeMillis().toString(), limit.toString())
        ).use { c -> while (c.moveToNext()) entries += rowToEntry(c) }
        entries
    }

    /** Every saved word, most recently added first. */
    suspend fun allEntries(context: Context): List<VocabEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<VocabEntry>()
        openDb(context).rawQuery("SELECT * FROM vocab ORDER BY added_at DESC", null)
            .use { c -> while (c.moveToNext()) entries += rowToEntry(c) }
        entries
    }

    suspend fun delete(context: Context, id: Long) {
        withContext(Dispatchers.IO) {
            openDb(context).delete("vocab", "id = ?", arrayOf(id.toString()))
        }
    }

    suspend fun updateGloss(context: Context, id: Long, gloss: String) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply { put("gloss", gloss) }
            openDb(context).update("vocab", values, "id = ?", arrayOf(id.toString()))
        }
    }

    suspend fun recordReview(context: Context, entry: VocabEntry, rating: ReviewRating) {
        withContext(Dispatchers.IO) {
            val graduationDays = graduationDays(context)
            val now = System.currentTimeMillis()
            var ease = entry.easeFactor
            var interval = entry.intervalDays
            var reps = entry.repetitions
            var dueInMillis: Long

            when (rating) {
                ReviewRating.AGAIN -> {
                    reps = 0
                    interval = 0
                    ease = (ease - 0.2).coerceAtLeast(MIN_EASE)
                    dueInMillis = 10 * 60 * 1000L // relearn in 10 minutes, like Anki's default
                }
                ReviewRating.HARD -> {
                    interval = maxOf(1, (interval * 1.2).toInt())
                    ease = (ease - 0.15).coerceAtLeast(MIN_EASE)
                    reps += 1
                    dueInMillis = interval * 24 * 60 * 60 * 1000L
                }
                ReviewRating.GOOD -> {
                    interval = when (reps) {
                        0 -> 1
                        1 -> 6
                        else -> maxOf(1, (interval * ease).toInt())
                    }
                    reps += 1
                    dueInMillis = interval * 24 * 60 * 60 * 1000L
                }
                ReviewRating.EASY -> {
                    interval = maxOf(1, (interval * ease * 1.3).toInt())
                    ease += 0.15
                    reps += 1
                    dueInMillis = interval * 24 * 60 * 60 * 1000L
                }
            }

            val learned = interval >= graduationDays
            val database = openDb(context)
            val values = ContentValues().apply {
                put("ease_factor", ease)
                put("interval_days", interval)
                put("repetitions", reps)
                put("due_at", now + dueInMillis)
                put("last_reviewed_at", now)
                put("learned", if (learned) 1 else 0)
            }
            database.update("vocab", values, "id = ?", arrayOf(entry.id.toString()))
        }
    }

    fun close() {
        synchronized(this) {
            db?.close()
            db = null
        }
    }
}
