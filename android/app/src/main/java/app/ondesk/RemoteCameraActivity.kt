package app.ondesk

import android.app.KeyguardManager
import java.util.UUID

/** Non-exported entry point; public camera shortcuts ignore remote extras. */
class RemoteCameraActivity : CameraShortcutActivity() {
    override fun remoteTicket(restoring: Boolean): Pair<String, String>? {
        if (!restoring && android.os.SystemClock.elapsedRealtime() >= intent.getLongExtra("launchDeadline", 0)) return null
        if (!RemoteCameraControl.enabled(this) || (!restoring && getSystemService(KeyguardManager::class.java).isKeyguardLocked)) return null
        val id = intent.getStringExtra("cameraID") ?: return null
        val server = intent.getStringExtra("serverID") ?: return null
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) return null
        val mac = (application as OnDeskApplication).pairing.read() ?: return null
        return if (mac.serverID == server) id to server else null
    }
}
