package app.ondesk

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareCompletionTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OnDeskApplication
    private fun shareIntent() = Intent(Intent.ACTION_SEND_MULTIPLE).setClass(app, ShareActivity::class.java).setType("*/*")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Uri.parse("content://app.ondesk.test.fixtures/slow.txt"), Uri.parse("content://app.ondesk.test.fixtures/screenshot.png")))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    private suspend fun imported(before: Set<String>, expected: Int = 2): List<QueueEntry> = withTimeout(10000) {
        var rows: List<QueueEntry>
        do { delay(100); rows = app.database.queue().all().filter { it.id !in before } } while (rows.size != expected || rows.any { it.shareBatchID != null })
        rows
    }
    private suspend fun clean(before: Set<String>) {
        val rows = app.queueMutations.withLock { app.database.queue().all().filter { it.id !in before } }
        rows.forEach { QueueRepository(app).delete(it.id) }
    }
    @Test fun implicitSharesResolveToTransparentEntryPoint() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            val resolved = app.packageManager.resolveActivity(Intent(action).setType("*/*").setPackage(app.packageName), 0)
            assertEquals(ShareActivity::class.java.name, resolved?.activityInfo?.name)
        }
    }
    @Test fun originalScreenStaysVisibleDuringCopyAndResumesAfterIt() = runBlocking {
        app.transfers.lock()
        val before = app.database.queue().all().map { it.id }.toSet()
        try {
            ActivityScenario.launch<QuickActionsSetupActivity>(Intent(app, QuickActionsSetupActivity::class.java)).use { source ->
                val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                fun texts(node: android.view.accessibility.AccessibilityNodeInfo?): String = if (node == null) "" else
                    listOf(node.text?.toString().orEmpty(), (0 until node.childCount).joinToString(" ") { texts(node.getChild(it)) }).joinToString(" ")
                withTimeout(5000) { while (!texts(automation.rootInActiveWindow).contains("Быстрые действия")) delay(50) }
                source.onActivity { it.startActivity(shareIntent().setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                delay(250)
                assertTrue("The original screen remains visible", texts(automation.rootInActiveWindow).contains("Быстрые действия"))
                val bitmap = automation.takeScreenshot()
                java.io.File(app.filesDir, "silent-share-visible-source.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                imported(before)
                withTimeout(5000) { while (source.state != Lifecycle.State.RESUMED) delay(100) }
            }
        } finally { clean(before); app.transfers.unlock() }
    }
    @Test fun invisibleShareClosesAfterCopyWithoutWaitingForMac() = runBlocking {
        app.transfers.lock()
        val before = app.database.queue().all().map { it.id }.toSet()
        try {
            ActivityScenario.launch<ShareActivity>(shareIntent()).use { scenario ->
                scenario.onActivity {
                    assertEquals("No main screen or intermediate view", 0, it.findViewById<FrameLayout>(android.R.id.content).childCount)
                    assertEquals(0, it.window.decorView.background.alpha)
                    assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                }
                val rows = imported(before)
                withTimeout(5000) { while (scenario.state != Lifecycle.State.DESTROYED) delay(100) }
                assertTrue(rows.all { it.state == "queued" && java.io.File(it.path).isFile })
                assertEquals(setOf("slow.txt", "screenshot.png"), rows.map { it.name }.toSet())
            }
        } finally { clean(before); app.transfers.unlock() }
    }
    @Test fun importErrorClosesInvisibleActivityAndLeavesActionableNotification() = runBlocking {
        // A fresh CI installation has no notification permission; establish this
        // test's prerequisite explicitly rather than relying on a previous setup.
        InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
        app.transfers.lock()
        val before = app.database.queue().all().map { it.id }.toSet()
        try {
            val prefs = app.getSharedPreferences("status", 0)
            prefs.edit().remove("share_error").commit()
            val intent = Intent(Intent.ACTION_SEND).setClass(app, ShareActivity::class.java).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://app.ondesk.captures/captures/does-not-exist.txt"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            app.startActivity(intent)
            withTimeout(5000) { while (prefs.getString("share_error", "")?.contains("Не удалось скопировать") != true) delay(100) }
            val error = prefs.getString("share_error", "")!!
            assertTrue(error.contains("Не удалось скопировать"))
            val manager = app.getSystemService(NotificationManager::class.java)
            withTimeout(5000) { while (manager.activeNotifications.none { it.id == ShareFeedback.ERROR_NOTIFICATION_ID }) delay(100) }
            val notice = manager.activeNotifications.first { it.id == ShareFeedback.ERROR_NOTIFICATION_ID }.notification
            assertNotNull(notice.contentIntent)
            assertEquals("Открыть очередь", notice.actions.single().title.toString())
            assertEquals(error, notice.extras.getCharSequence("android.bigText").toString())
            assertTrue(app.database.queue().all().none { it.id !in before })
            prefs.edit().remove("share_error").commit()
            manager.cancel(ShareFeedback.ERROR_NOTIFICATION_ID)
        } finally { clean(before); app.transfers.unlock() }
    }
    @Test fun recreationDuringCopyDoesNotImportTwice() = runBlocking {
        app.transfers.lock()
        val before = app.database.queue().all().map { it.id }.toSet()
        try {
            ActivityScenario.launch<ShareActivity>(shareIntent()).use { scenario ->
                scenario.recreate()
                val rows = imported(before)
                withTimeout(5000) { while (scenario.state != Lifecycle.State.DESTROYED) delay(100) }
                delay(300)
                assertEquals(rows.map { it.id }.toSet(), app.database.queue().all().filter { it.id !in before }.map { it.id }.toSet())
            }
        } finally { clean(before); app.transfers.unlock() }
    }
    @Test fun restoringBatchSkipsDurablePrefixEvenAfterPayloadDeletion() = runBlocking {
        app.transfers.lock()
        val before = app.database.queue().all().map { it.id }.toSet()
        try {
            val batch = java.util.UUID.randomUUID().toString()
            val repository = QueueRepository(app)
            val uris = listOf(Uri.parse("content://app.ondesk.test.fixtures/hello.txt"), Uri.parse("content://app.ondesk.test.fixtures/screenshot.png"))
            repository.import(uris.take(1), "*/*", batch)
            val first = app.database.queue().all().single { it.id !in before }
            app.database.queue().progress(first.id, first.size, "delivered", null)
            java.io.File(first.path).delete()
            assertEquals(2, repository.import(uris, "*/*", batch))
            val rows = app.database.queue().all().filter { it.id !in before }
            assertEquals(2, rows.size)
            assertEquals(first.id, rows.single { it.name == "hello.txt" }.id)
            repository.enqueueLink("https://example.org/restore", batch + "-link")
            repository.enqueueLink("https://example.org/restore", batch + "-link")
            assertEquals(1, app.database.queue().all().count { it.id !in before && it.link != null })
        } finally { clean(before); app.transfers.unlock() }
    }
}
