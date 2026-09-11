package com.tsm.wtlens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * Entry point: grant the "draw over other apps" permission, then start the
 * overlay bubble. Screen-capture (MediaProjection) consent is requested
 * separately, right before the first capture, via [CaptureConsentActivity].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        statusText = TextView(this)
        root.addView(statusText)

        val grantOverlayButton = Button(this).apply {
            text = "1. Grant overlay permission"
            setOnClickListener { requestOverlayPermission() }
        }
        root.addView(grantOverlayButton)

        val startButton = Button(this).apply {
            text = "2. Start WebtoonLens bubble"
            setOnClickListener { startOverlay() }
        }
        root.addView(startButton)

        setContentView(root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        statusText.text = if (Settings.canDrawOverlays(this)) {
            "Overlay permission: granted. Tap Start to enable the floating bubble."
        } else {
            "Overlay permission: NOT granted. Tap step 1 first."
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        // Ask for one-time screen-capture consent before starting the
        // bubble service; the resulting token is handed to OverlayService.
        startActivity(Intent(this, CaptureConsentActivity::class.java))
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1001
    }
}
