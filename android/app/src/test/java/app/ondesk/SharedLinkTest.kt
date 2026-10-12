package app.ondesk

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedLinkTest {
    @Test fun extractsBrowserShareAndPreservesQueryAndFragment() {
        assertEquals("https://example.org/a?q=one%20two&x=1#part", SharedLink.extract("Название страницы\nhttps://example.org/a?q=one%20two&x=1#part"))
        assertEquals("http://example.org/", SharedLink.extract(" http://example.org/ "))
        for (text in listOf("file:///tmp/a", "javascript:alert(1)", "https://user:pass@example.org/", "https://", "https://example.org/%broken", "https://example.org/ https://other.org/", "просто текст")) {
            assertThrows(IllegalArgumentException::class.java) { SharedLink.extract(text) }
        }
    }
    @Test fun sendsLinkAsCommandAndRequiresOpeningReceipt() = runBlocking {
        val entry = QueueEntry("00000000-0000-4000-8000-000000000001", "Ссылка", "text/uri-list", 0, "", "", "mac", link = "https://example.org/")
        var calls = 0
        val server = Exchange { header, bytes ->
            calls++
            assertEquals("open_link", header.getString("op"))
            assertEquals(entry.id, header.getString("id"))
            assertEquals(entry.link, header.getString("url"))
            assertNull(bytes)
            JSONObject().put("complete", true).put("opened", true)
        }
        assertEquals("Ссылка", TransferPump().send(entry, "token", server) {})
        assertEquals(1, calls)
        try {
            TransferPump().send(entry, "token", Exchange { _, _ -> JSONObject().put("complete", true) }) {}
            fail("Accepted receipt without opening acknowledgement")
        } catch (_: java.io.IOException) {}
    }
}
