package app.ondesk

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RemoteCloseTest {
    @Test fun closingControlConnectionNeverRunsNetworkCleanupOnMainThread() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.cacheDir, "close-test-${java.util.UUID.randomUUID()}")
        file.writeText("disposable resource")
        val input = file.inputStream()
        val failure = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> failure.set(error) })
        var closing: Job? = null
        try {
            instrumentation.runOnMainSync {
                closing = closeControlConnection(Closeable {
                    check(Looper.myLooper() != Looper.getMainLooper()) { "TLS close may send bytes; main-thread network is forbidden" }
                    input.close()
                }, scope)
            }
            requireNotNull(closing).join()
            assertNull("Service destruction must not crash the app", failure.get())
            try { input.read(); fail("Connection resource was not closed") } catch (_: IOException) {}
        } finally { scope.cancel(); input.close(); file.delete() }
    }
}
