package net.clench.wallet.ui

import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * Forces the barcode scanner to portrait orientation.
 * Used with ScanOptions.setCaptureActivity() to override the default landscape behavior.
 */
class PortraitCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
    }
}
