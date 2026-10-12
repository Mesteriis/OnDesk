package app.ondesk

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.net.ssl.SSLException

@RunWith(AndroidJUnit4::class)
class CrossPlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as OnDeskApplication
    @Test fun scannerDoesNotForceLandscape() {
        val component = android.content.ComponentName(app, com.journeyapps.barcodescanner.CaptureActivity::class.java)
        val activity = app.packageManager.getActivityInfo(component, 0)
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.screenOrientation)
    }
    @Test fun queueShareWhileUnpaired() = runBlocking {
        assertNull(app.pairing.read())
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setClass(app, MainActivity::class.java).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Uri.parse("content://app.ondesk.test.fixtures/hello.txt"), Uri.parse("content://app.ondesk.test.fixtures/screenshot.png")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(intent)
        withTimeout(30000) { while (app.database.queue().all().size != 2) delay(100) }
        val rows = app.database.queue().all()
        assertEquals(setOf("hello.txt", "screenshot.png"), rows.map { it.name }.toSet())
        assertEquals("abc", File(rows.first { it.name == "hello.txt" }.path).readText())
        assertTrue(rows.all { it.state == "queued" && it.serverID == null && File(it.path).exists() })
    }
    @Test fun pairPinnedMacAndDeliverPersistentQueue() = runBlocking {
        // Run after force-stop/relaunch: reads the real Room DB and spool from stage 1.
        assertEquals(2, app.database.queue().all().size)
        val code = PairingCode.parse(instrumentation.context.assets.open("pairing.json").bufferedReader().use { it.readText() })
        try { TlsClient(code.host, code.port, "0".repeat(64)).close(); fail("Accepted wrong certificate") } catch (_: SSLException) {}
        val token = TlsClient(code.host, code.port, code.fingerprint).use {
            it.exchange(request("pair").put("secret", code.secret).put("deviceID", app.pairing.deviceID).put("deviceName", "OnDesk disposable emulator")).getString("token")
        }
        val mac = PairedMac(code.serverID, code.host, code.port, code.fingerprint, token)
        app.pairing.save(mac)
        assertEquals(mac, PairingStore(app).read())
        TransferWorker.schedule(app)
        withTimeout(60000) { while (app.database.queue().all().isNotEmpty()) delay(250) }
        assertEquals(0, File(app.filesDir, "queue").listFiles()?.size ?: 0)
    }
    @Test fun shareImportDoesNotWaitForActiveTransfer() = runBlocking {
        app.transfers.lock()
        try {
            val intent = Intent(Intent.ACTION_SEND).setClass(app, MainActivity::class.java).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://app.ondesk.test.fixtures/parallel.txt"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.startActivity(intent)
            val imported = withTimeoutOrNull(5000) {
                while (app.database.queue().all().none { it.name == "parallel.txt" }) delay(100)
                true
            } ?: false
            assertTrue("Share import must finish while the sender is busy", imported)
        } finally { app.transfers.unlock() }
        withTimeout(30000) { while (app.database.queue().all().isNotEmpty()) delay(100) }
    }
    @Test fun deletesWaitingFileWhileAnotherTransferIsBusy() = runBlocking {
        app.transfers.lock()
        try {
            val copy = FileSpool(File(app.filesDir, "queue")).copy("abc".byteInputStream(), "cancel.txt", "text/plain")
            app.database.queue().insert(QueueEntry(copy.id, copy.name, copy.mime, copy.size, copy.sha256, copy.path, app.pairing.read()?.serverID))
            withTimeout(3000) { QueueRepository(app).delete(copy.id) }
            assertFalse(File(copy.path).exists())
            assertTrue(app.database.queue().all().none { it.id == copy.id })
        } finally { app.transfers.unlock() }
    }
    @Test fun largeFileResumesAfterConnectionLoss() = runBlocking {
        val mac = requireNotNull(app.pairing.read())
        val size = 4294967296L + 17
        val path = File(app.cacheDir, "large-video-fixture.mp4")
        try {
            RandomAccessFile(path, "rw").use { file -> file.setLength(size); file.seek(size - 1); file.write(42) }
            val hash = MessageDigest.getInstance("SHA-256")
            path.inputStream().buffered().use { input -> val buffer = ByteArray(1024 * 1024); while (true) { val n = input.read(buffer); if (n == -1) break; hash.update(buffer, 0, n) } }
            val entry = QueueEntry(java.util.UUID.randomUUID().toString(), "large-video-fixture.mp4", "video/mp4", size, hash.digest().joinToString("") { "%02x".format(it) }, path.path, mac.serverID)
            val metadata = org.json.JSONObject().put("id", entry.id).put("name", entry.name).put("mime", entry.mime).put("size", entry.size).put("sha256", entry.sha256)
            TlsClient(mac.host, mac.port, mac.fingerprint).use { client ->
                client.exchange(request("begin").put("token", mac.token).put("transfer", metadata))
                val first = ByteArray(4194304)
                val reply = client.exchange(request("chunk").put("token", mac.token).put("id", entry.id).put("offset", 0L).put("payloadLength", first.size), first)
                assertEquals(4194304L, reply.getLong("offset"))
            }
            var firstOffset: Long? = null
            TlsClient(mac.host, mac.port, mac.fingerprint).use { client ->
                assertEquals("large-video-fixture.mp4", TransferPump().send(entry, mac.token, Exchange { header, bytes -> client.exchange(header, bytes) }) { if (firstOffset == null) firstOffset = it })
                val receipt = client.exchange(request("finish").put("token", mac.token).put("id", entry.id))
                assertTrue(receipt.getBoolean("complete"))
            }
            assertEquals(4194304L, firstOffset)
        } finally { path.delete() }
    }
}
