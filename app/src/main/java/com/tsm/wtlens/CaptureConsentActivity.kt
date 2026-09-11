package com.tsm.wtlens

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Invisible activity whose only job is to show the system's one-time
 * "start recording/casting your screen" consent dialog and hand the
 * resulting token off to [OverlayService]. MediaProjection consent can only
 * be requested from an Activity, so this exists purely as that bridge.
 */
class CaptureConsentActivity : AppCompatActivity() {

    private val requestCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_START
                    putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(OverlayService.EXTRA_RESULT_DATA, result.data)
                }
                startForegroundService(intent)
            } else {
                Toast.makeText(this, "Screen-capture permission denied", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        requestCapture.launch(projectionManager.createScreenCaptureIntent())
    }
}
