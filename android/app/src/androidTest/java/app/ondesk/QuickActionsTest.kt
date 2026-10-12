package app.ondesk

import android.content.Context
import android.content.pm.ShortcutManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuickActionsTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OnDeskApplication
    @Test fun screenshotShortcutCapturesAfterItsWindowCloses() = runBlocking {
        app.transfers.lock()
        try {
            kotlinx.coroutines.withTimeout(20000) { while (!ScreenshotService.enabled(app)) kotlinx.coroutines.delay(100) }
            val before = app.database.queue().all().map { it.id }.toSet()
            app.startActivity(android.content.Intent(app, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            kotlinx.coroutines.delay(1500)
            app.startActivity(android.content.Intent(app, ScreenshotShortcutActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val row = kotlinx.coroutines.withTimeoutOrNull(8000) {
                var found: QueueEntry? = null
                while (found == null) { found = app.database.queue().all().firstOrNull { it.id !in before && it.mime == "image/png" }; kotlinx.coroutines.delay(100) }
                found
            }
            assertNotNull("Shortcut must enqueue the captured screen", row)
            assertTrue(File(row!!.path).length() > 0)
            QueueRepository(app).delete(row.id)
        } finally { app.transfers.unlock() }
    }
    @Test fun remoteCameraQueuesUsingMacRequestID() = runBlocking {
        app.transfers.lock()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val id = UUID.randomUUID().toString()
        val paired = requireNotNull(app.pairing.read())
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != android.provider.MediaStore.ACTION_IMAGE_CAPTURE) return null
                val output = intent.getParcelableExtra(android.provider.MediaStore.EXTRA_OUTPUT, android.net.Uri::class.java)!!
                val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                app.contentResolver.openOutputStream(output)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            app.startActivity(android.content.Intent().setClassName(app, "app.ondesk.RemoteCameraActivity")
                .putExtra("cameraID", id).putExtra("serverID", paired.serverID).putExtra("launchDeadline", android.os.SystemClock.elapsedRealtime() + 30000).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val row = kotlinx.coroutines.withTimeoutOrNull(6000) {
                var row: QueueEntry? = null
                while (row == null) { row = app.database.queue().all().firstOrNull { it.id == id }; kotlinx.coroutines.delay(100) }
                row
            }
            assertNotNull("Remote photo must use the authenticated request UUID", row)
            assertEquals(paired.serverID, row!!.serverID)
            QueueRepository(app).delete(id)
        } finally { instrumentation.removeMonitor(monitor); app.transfers.unlock() }
    }
    @Test fun remoteCameraCannotBeLaunchedByOtherApps() {
        val info = app.packageManager.getActivityInfo(android.content.ComponentName(app.packageName, "app.ondesk.RemoteCameraActivity"), 0)
        assertFalse(info.exported)
    }
    @Test fun cameraResultAutomaticallyEnqueuesPhoto() = runBlocking {
        app.transfers.lock()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != android.provider.MediaStore.ACTION_IMAGE_CAPTURE) return null
                val output = intent.getParcelableExtra(android.provider.MediaStore.EXTRA_OUTPUT, android.net.Uri::class.java)!!
                val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                app.contentResolver.openOutputStream(output)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            val before = app.database.queue().all().map { it.id }.toSet()
            app.startActivity(android.content.Intent(app, CameraShortcutActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val row = kotlinx.coroutines.withTimeout(10000) {
                var found: QueueEntry? = null
                while (found == null) { found = app.database.queue().all().firstOrNull { it.id !in before && it.mime == "image/jpeg" }; kotlinx.coroutines.delay(100) }
                found
            }
            assertTrue(row!!.name.startsWith("OnDesk-Photo-"))
            assertTrue(File(row.path).length() > 0)
            QueueRepository(app).delete(row.id)
        } finally { instrumentation.removeMonitor(monitor); app.transfers.unlock() }
    }
    @Test fun cancellingCameraDoesNotQueueOrKeepPhoto() = runBlocking {
        app.transfers.lock()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? =
                if (intent.action == android.provider.MediaStore.ACTION_IMAGE_CAPTURE) {
                    val output = intent.getParcelableExtra(android.provider.MediaStore.EXTRA_OUTPUT, android.net.Uri::class.java)!!
                    app.contentResolver.openOutputStream(output)!!.use { it.write(byteArrayOf(1, 2, 3)) }
                    android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
                } else null
        }
        instrumentation.addMonitor(monitor)
        try {
            val before = app.database.queue().all().map { it.id }.toSet()
            val files = File(app.filesDir, "captures").listFiles().orEmpty().map { it.name }.toSet()
            app.startActivity(android.content.Intent(app, CameraShortcutActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            kotlinx.coroutines.delay(1500)
            assertEquals(before, app.database.queue().all().map { it.id }.toSet())
            assertEquals(files, File(app.filesDir, "captures").listFiles().orEmpty().map { it.name }.toSet())
        } finally { instrumentation.removeMonitor(monitor); app.transfers.unlock() }
    }
    @Test fun publishesScreenshotAndCameraShortcuts() {
        val shortcuts = app.getSystemService(ShortcutManager::class.java).manifestShortcuts
        assertEquals(setOf("screenshot_to_mac", "camera_to_mac"), shortcuts.map { it.id }.toSet())
        assertTrue(shortcuts.all { it.intent?.component?.packageName == app.packageName })
    }
    @Test fun screenshotServiceOnlyRequestsScreenshotCapability() {
        val services = (app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).installedAccessibilityServiceList
        val service = services.firstOrNull { it.resolveInfo.serviceInfo.packageName == app.packageName }
        assertNotNull("OnDesk screenshot service must be discoverable", service)
        assertEquals(AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT, service!!.capabilities)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.resolveInfo.serviceInfo.permission)
    }
    @Test fun generatedCaptureBecomesDurableQueueWithoutAnotherCopy() = runBlocking {
        app.transfers.lock()
        val file = File(File(app.filesDir, "captures").apply { mkdirs() }, "${UUID.randomUUID()}.png")
        try {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val id = QueueRepository(app).enqueueCapture(file, "image/png", "OnDesk-Screenshot.png")
            val row = app.database.queue().all().single { it.id == id }
            assertEquals(file.absolutePath, row.path)
            assertEquals(file.length(), row.size)
            assertEquals("image/png", row.mime)
            assertEquals(id, QueueRepository(app).enqueueCapture(file, "image/png", "OnDesk-Screenshot.png"))
            assertEquals(1, app.database.queue().all().count { it.id == id })
            QueueRepository(app).delete(id)
            assertFalse(file.exists())
        } finally { file.delete(); app.transfers.unlock() }
    }
    @Test fun captureImporterRejectsFilesOutsideCaptureDirectory() = runBlocking {
        val file = File(app.cacheDir, "${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        try {
            try { QueueRepository(app).enqueueCapture(file, "image/png", "invalid.png"); fail("Accepted unrelated private file") }
            catch (_: IllegalArgumentException) {}
        } finally { file.delete() }
    }
}
