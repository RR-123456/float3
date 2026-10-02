package com.pragon.mobile

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Invisible activity that shows Android's own "Start recording or casting?"
 * prompt (required for any screen capture), then hands the grant to
 * ScreenShareService. Opened when the PC asks to see this phone's screen.
 */
class ScreenConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 4711)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4711 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ScreenShareService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            ContextCompat.startForegroundService(this, i)
        }
        finish()
    }
}
