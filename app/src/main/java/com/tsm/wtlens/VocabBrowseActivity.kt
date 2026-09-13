package com.tsm.wtlens

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Lists every saved vocab word (not just what's due), with per-word delete. */
class VocabBrowseActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Saved vocabulary"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun refresh() {
        activityScope.launch {
            val entries = VocabManager.allEntries(this@VocabBrowseActivity)
            listContainer.removeAllViews()
            if (entries.isEmpty()) {
                listContainer.addView(TextView(this@VocabBrowseActivity).apply {
                    text = "No words saved yet. Words you look up while reading are saved automatically."
                    alpha = 0.7f
                })
                return@launch
            }
            entries.forEach { entry -> listContainer.addView(buildRow(entry)) }
        }
    }

    private fun buildRow(entry: VocabEntry): android.view.View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        row.addView(android.view.View(this).apply {
            val size = dp(10)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(10) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (entry.learned) Color.rgb(158, 158, 158) else Color.rgb(156, 39, 176))
            }
        })

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = if (!entry.hanja.isNullOrBlank()) "${entry.surface} (${entry.hanja})" else entry.surface
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        textColumn.addView(TextView(this).apply {
            text = entry.gloss
            textSize = 14f
            alpha = 0.8f
        })
        textColumn.addView(TextView(this).apply {
            text = if (entry.learned) "Learned" else "Reviewing — interval ${entry.intervalDays}d"
            textSize = 11f
            alpha = 0.6f
        })
        row.addView(textColumn)

        row.addView(Button(this).apply {
            text = "Edit"
            setOnClickListener { showEditDialog(entry) }
        })

        row.addView(Button(this).apply {
            text = "Delete"
            setOnClickListener {
                activityScope.launch {
                    VocabManager.delete(this@VocabBrowseActivity, entry.id)
                    refresh()
                }
            }
        })

        container.addView(row)
        container.addView(android.view.View(this).apply {
            setBackgroundColor(Color.argb(40, 128, 128, 128))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })

        return container
    }

    private fun showEditDialog(entry: VocabEntry) {
        val input = EditText(this).apply {
            setText(entry.gloss)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSelection(text.length)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            setPadding(padding, padding, padding, padding)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit meaning: ${entry.surface}")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newGloss = input.text.toString().trim()
                if (newGloss.isNotEmpty()) {
                    activityScope.launch {
                        VocabManager.updateGloss(this@VocabBrowseActivity, entry.id, newGloss)
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
