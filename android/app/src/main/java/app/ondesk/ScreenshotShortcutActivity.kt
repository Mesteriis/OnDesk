package app.ondesk

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

class ScreenshotShortcutActivity : ComponentActivity() {
    private var captureOnStop = false
    private var captureDisplay = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ScreenshotService.enabled(this)) {
            startActivity(Intent(this, QuickActionsSetupActivity::class.java))
            finish()
        } else {
            captureDisplay = display?.displayId ?: 0
            captureOnStop = true
        }
    }
    override fun onResume() {
        super.onResume()
        if (captureOnStop) finish()
    }
    override fun onStop() {
        super.onStop()
        if (captureOnStop) {
            captureOnStop = false
            sendBroadcast(Intent(ScreenshotService.CAPTURE).setPackage(packageName).putExtra("display", captureDisplay))
        }
    }
}
