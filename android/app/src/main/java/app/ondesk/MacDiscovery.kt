package app.ondesk

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class MacDiscovery(context: Context) {
    private val manager = context.getSystemService(NsdManager::class.java)
    suspend fun find(serverID: String): Pair<String, Int>? = withTimeoutOrNull(5000) {
        suspendCancellableCoroutine { continuation ->
            val direct = Executor { it.run() }
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            lateinit var discovery: NsdManager.DiscoveryListener
            fun stop() { try { manager.stopServiceDiscovery(discovery) } catch (_: IllegalArgumentException) {} }
            fun finish(value: Pair<String, Int>?) {
                if (completed.compareAndSet(false, true)) { continuation.resume(value); stop() }
            }
            discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, code: Int) { finish(null) }
                override fun onStopDiscoveryFailed(type: String, code: Int) {}
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceName != serverID || !continuation.isActive) return
                    try {
                        manager.resolveService(info, direct, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val address = info.hostAddresses.firstOrNull { it is java.net.Inet4Address } ?: info.hostAddresses.firstOrNull()
                                if (address != null && info.port in 1..65535) finish(address.hostAddress!! to info.port)
                            }
                        })
                    } catch (_: IllegalArgumentException) { finish(null) }
                }
            }
            continuation.invokeOnCancellation { completed.set(true); stop() }
            try { manager.discoverServices("_ondesk._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery) }
            catch (_: Exception) { finish(null) }
        }
    }
}
