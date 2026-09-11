package com.tsm.wtlens

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Grabs a single frame of the current screen via MediaProjection.
 *
 * Deliberately single-shot rather than a continuous live feed: it tears the
 * VirtualDisplay/ImageReader down again right after one frame, which keeps
 * behavior simple and avoids holding capture resources (and battery) while
 * the user is just reading.
 */
class ScreenCapture(private val projection: MediaProjection) {

    suspend fun captureFrame(windowManager: WindowManager): Bitmap = suspendCancellableCoroutine { cont ->
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        val handler = Handler(Looper.getMainLooper())

        var virtualDisplay: VirtualDisplay? = null
        var settled = false

        fun cleanup() {
            virtualDisplay?.release()
            imageReader.close()
        }

        imageReader.setOnImageAvailableListener({ reader ->
            if (settled) return@setOnImageAvailableListener
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            settled = true
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()

                if (cont.isActive) cont.resume(cropped)
            } finally {
                image.close()
                cleanup()
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "WebtoonLensCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler
        )

        cont.invokeOnCancellation { cleanup() }
    }
}
