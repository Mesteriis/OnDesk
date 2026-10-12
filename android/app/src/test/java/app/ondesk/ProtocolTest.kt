package app.ondesk
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.File

class ProtocolTest {
    @Test fun decodesSharedFixturesAndRetains64BitOffsets() {
        val bytes = File("../../protocol/fixtures/begin.json").readBytes()
        val framed = ByteArrayOutputStream().apply { DataOutputStream(this).writeInt(bytes.size); write(bytes) }.toByteArray()
        val request = FrameCodec.read(framed.inputStream())
        assertEquals("hello.txt", request.getJSONObject("transfer").getString("name"))
        val reply = JSONObject().put("version", 1).put("ok", true).put("offset", 5368709120L)
        val stream = ByteArrayOutputStream(); FrameCodec.write(stream, reply)
        assertEquals(5368709120L, FrameCodec.read(stream.toByteArray().inputStream()).getLong("offset"))
    }
    @Test fun rejectsOversizedTruncatedAndEmptyHeaders() {
        for (length in listOf(-1, 0, 16385, Int.MAX_VALUE, 5)) {
            val stream = ByteArrayOutputStream().apply { DataOutputStream(this).writeInt(length) }
            assertThrows(IOException::class.java) { FrameCodec.read(stream.toByteArray().inputStream()) }
        }
    }
    @Test fun QRRejectsForeignVersionsInvalidPortsAndMissingIdentity() {
        val valid = JSONObject().put("version", 1).put("serverID", "00000000-0000-4000-8000-000000000001").put("host", "mac.local").put("port", 1234).put("fingerprint", "a".repeat(64)).put("secret", "b".repeat(64))
        assertEquals(1234, PairingCode.parse(valid.toString()).port)
        for (field in listOf("version", "serverID", "fingerprint", "secret")) {
            val broken = JSONObject(valid.toString()); broken.remove(field)
            assertThrows(IllegalArgumentException::class.java) { PairingCode.parse(broken.toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) { PairingCode.parse(valid.put("port", 0).toString()) }
        assertThrows(IllegalArgumentException::class.java) { PairingCode.parse(valid.put("port", 1234).put("version", 2).toString()) }
    }
}
