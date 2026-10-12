package app.ondesk

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class TransferPumpTest {
    @Test fun resumesFromReceiverOffsetAndRequiresFinalReceipt() = runBlocking {
        val dir = Files.createTempDirectory("ondesk-pump").toFile()
        try {
            val payload = ByteArray(4 * 1024 * 1024 + 11) { (it % 251).toByte() }
            val file = java.io.File(dir, "payload").apply { writeBytes(payload) }
            val entry = QueueEntry("00000000-0000-4000-8000-000000000001", "video.mp4", "video/mp4", payload.size.toLong(), "a".repeat(64), file.path, "mac")
            val received = ByteArrayOutputStream().apply { write(payload, 0, 7) }
            val checkpoints = mutableListOf<Long>()
            val exchange = Exchange { header, bytes ->
                when (header.getString("op")) {
                    "begin" -> JSONObject().put("offset", 7L).put("complete", false)
                    "chunk" -> {
                        if (header.getLong("offset") != received.size().toLong()) throw java.io.IOException("Wrong resume offset")
                        if (bytes == null || bytes.size > 4194304) throw java.io.IOException("Invalid block")
                        received.write(bytes)
                        JSONObject().put("offset", received.size().toLong()).put("complete", false)
                    }
                    "finish" -> JSONObject().put("offset", payload.size.toLong()).put("complete", true).put("savedName", "video.mp4")
                    else -> throw java.io.IOException("Unknown op")
                }
            }
            assertEquals("video.mp4", TransferPump().send(entry, "token", exchange) { checkpoints.add(it) })
            assertArrayEquals(payload, received.toByteArray())
            assertEquals(payload.size.toLong(), checkpoints.last())
            assertTrue(file.exists()) // queue owner deletes only after durable delivered state
            val noReceipt = Exchange { header, _ ->
                if (header.getString("op") == "begin") JSONObject().put("offset", payload.size.toLong()).put("complete", false)
                else JSONObject().put("complete", false)
            }
            try { TransferPump().send(entry, "token", noReceipt) {}; fail("Accepted missing final receipt") } catch (_: java.io.IOException) {}
        } finally { dir.deleteRecursively() }
    }
}
