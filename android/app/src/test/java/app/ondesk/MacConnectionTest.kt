package app.ondesk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.SSLHandshakeException

class MacConnectionTest {
    @Test fun discoversRememberedMacAfterOldAddressHasDifferentCertificate() = runBlocking {
        val tried = mutableListOf<String>()
        val result = connectRememberedMac("old", 42, { "new" to 43 }) { host, _ ->
            tried.add(host)
            if (host == "old") throw SSLHandshakeException("Wrong pin")
            "pinned-client"
        }
        assertEquals(listOf("old", "new"), tried)
        assertEquals("pinned-client", result.client)
        assertEquals("new" to 43, result.address)
    }
    @Test fun changedPinIsNeverAcceptedWhenDiscoveryHasSameUntrustedEndpoint() = runBlocking {
        try {
            connectRememberedMac("old", 42, { "old" to 42 }) { _, _ -> throw SSLHandshakeException("Wrong pin") }
            fail("Accepted changed certificate")
        } catch (_: SSLHandshakeException) {}
    }
}
