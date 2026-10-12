package app.ondesk

import android.content.Context
import android.content.Intent
import android.app.KeyguardManager
import kotlinx.coroutines.*
import java.util.UUID

/** Runs only while the user-enabled accessibility service is bound. */
class RemoteCameraControl(private val context: Context, private val scope: CoroutineScope) {
    @Volatile private var client: TlsClient? = null
    private var job: Job? = null
    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (!enabled(context) || context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { delay(2000); continue }
                    val app = context.applicationContext as OnDeskApplication
                    val mac = app.pairing.read() ?: run { delay(2000); continue }
                    val connection = connectRememberedMac(mac.host, mac.port, { MacDiscovery(context).find(mac.serverID) }) { host, port -> TlsClient(host, port, mac.fingerprint) }
                    connection.client.use { connected ->
                        client = connected
                        while (isActive && enabled(context) && app.pairing.read() == mac) {
                            if (!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                                val started = android.os.SystemClock.elapsedRealtime()
                                val reply = connected.exchange(request("camera_poll").put("token", mac.token))
                                val id = reply.optString("cameraID")
                                if (id.isNotEmpty()) {
                                    require(UUID.fromString(id).toString() == id)
                                    val remaining = reply.optLong("cameraTTLMillis", 0)
                                    val deadline = started + remaining
                                    if (remaining !in 1..30000 || android.os.SystemClock.elapsedRealtime() >= deadline) { cancel(context, id, mac.serverID); continue }
                                    withContext(Dispatchers.Main) {
                                        if (android.os.SystemClock.elapsedRealtime() < deadline && enabled(context) && app.pairing.read() == mac && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                                            context.startActivity(Intent(context, RemoteCameraActivity::class.java)
                                                .putExtra("cameraID", id).putExtra("serverID", mac.serverID).putExtra("launchDeadline", deadline).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        } else cancel(context, id, mac.serverID)
                                    }
                                }
                            }
                            delay(2000)
                        }
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { delay(8000) }
                finally { client = null }
            }
        }
    }
    fun stop() {
        job?.cancel(); job = null
        closeControlConnection(client, (context.applicationContext as OnDeskApplication).backgroundScope)
    }
    companion object {
        fun enabled(context: Context) = context.getSharedPreferences("quick_actions", Context.MODE_PRIVATE).getBoolean("remote_camera", true)
        fun cancel(context: Context, id: String, serverID: String) {
            val app = context.applicationContext as OnDeskApplication
            app.backgroundScope.launch {
                try {
                    val mac = app.pairing.read()?.takeIf { it.serverID == serverID } ?: return@launch
                    val connection = connectRememberedMac(mac.host, mac.port, { MacDiscovery(context).find(mac.serverID) }) { host, port -> TlsClient(host, port, mac.fingerprint) }
                    connection.client.use { it.exchange(request("camera_cancel").put("token", mac.token).put("id", id)) }
                } catch (_: Exception) { /* Mac expires an unconfirmed request after ten minutes. */ }
            }
        }
    }
}

internal fun closeControlConnection(connection: java.io.Closeable?, scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
    try { connection?.close() } catch (_: java.io.IOException) { /* Already disconnecting; never crash service teardown. */ }
}
