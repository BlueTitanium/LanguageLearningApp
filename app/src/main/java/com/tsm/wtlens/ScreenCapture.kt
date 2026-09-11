package com.tsm.wtlens

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.delay

/**
 * Keeps a single persistent VirtualDisplay mirroring the screen and caches
 * whatever frame it most recently produced.
 *
 * A MediaProjection instance can only have createVirtualDisplay() called on
 * it ONCE — calling it again (e.g. a naive "recreate per screenshot"
 * approach) throws a SecurityException on modern Android. So instead the
 * mirror is set up once and left running; each [captureFrame] call just
 * hands back the latest cached frame, which is exactly correct for a
 * screenshot use case (if nothing on screen changed, the "current" frame
 * legitimately is the same as before).
 */
class ScreenCapture(private val projection: MediaProjection) {

    @Volatile private var latestBitmap: Bitmap? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var capturedWidth = 0
    private var capturedHeight = 0
    private val handler = Handler(Looper.getMainLooper())

    @Synchronized
    private fun ensureDisplay(windowManager: WindowManager) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        if (virtualDisplay != null && width == capturedWidth && height == capturedHeight) return

        // Screen rotated/resized since the display was created: rebuild it.
        // (This still only ever calls createVirtualDisplay once per
        // MediaProjection under normal, no-rotation use.)
        virtualDisplay?.release()
        imageReader?.close()
        latestBitmap = null

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                latestBitmap = imageToBitmap(image, width, height)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "WebtoonLensCapture",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        imageReader = reader
        capturedWidth = width
        capturedHeight = height
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
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
        return cropped
    }

    suspend fun captureFrame(windowManager: WindowManager): Bitmap {
        ensureDisplay(windowManager)
        var waitedMs = 0
        while (latestBitmap == null && waitedMs < 3000) {
            delay(50)
            waitedMs += 50
        }
        return latestBitmap ?: error("Screen capture timed out waiting for a frame")
    }

    fun release() {
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
        latestBitmap = null
    }
}
