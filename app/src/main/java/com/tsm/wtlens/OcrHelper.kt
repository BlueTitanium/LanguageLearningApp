package com.tsm.wtlens

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** One tappable unit of recognized text: a single word, or a whole line/bubble. */
data class OcrHit(
    val text: String,
    val bounds: Rect,
    val isWord: Boolean
)

class OcrHelper {

    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    /**
     * Runs OCR on a captured screenshot and flattens the result into two
     * layers of hit-testable regions: individual words (fine-grained,
     * checked first on tap) and whole lines (treated as "the speech bubble").
     */
    suspend fun recognize(bitmap: Bitmap): Pair<List<OcrHit>, List<OcrHit>> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result: Text = recognizer.process(image).await()

        val words = mutableListOf<OcrHit>()
        val lines = mutableListOf<OcrHit>()

        for (block in result.textBlocks) {
            for (line in block.lines) {
                val lineBounds = line.boundingBox ?: continue
                lines += OcrHit(line.text, lineBounds, isWord = false)
                for (element in line.elements) {
                    val wordBounds = element.boundingBox ?: continue
                    words += OcrHit(element.text, wordBounds, isWord = true)
                }
            }
        }

        return words to lines
    }

    fun close() = recognizer.close()
}
