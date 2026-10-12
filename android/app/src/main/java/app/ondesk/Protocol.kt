package app.ondesk

import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

data class PairingCode(val serverID: String, val host: String, val port: Int, val fingerprint: String, val secret: String) {
    companion object {
        fun parse(text: String): PairingCode {
            try {
                require(text.toByteArray().size <= 16384)
                val json = JSONObject(text)
                require(json.getInt("version") == 1)
                val id = json.getString("serverID")
                require(UUID.fromString(id).toString().equals(id, ignoreCase = true))
                val host = json.getString("host")
                require(host.length in 1..255 && host.matches(Regex("[A-Za-z0-9._:%-]+")))
                val port = json.getInt("port"); require(port in 1..65535)
                val fingerprint = json.getString("fingerprint").lowercase()
                val secret = json.getString("secret")
                require(fingerprint.matches(Regex("[0-9a-f]{64}")) && secret.matches(Regex("[0-9a-f]{64}")))
                return PairingCode(id, host, port, fingerprint, secret)
            } catch (error: Exception) { throw IllegalArgumentException("Это не действующий QR-код OnDesk", error) }
        }
    }
}
object FrameCodec {
    fun read(input: InputStream): JSONObject {
        try {
            val size = DataInputStream(input).readInt()
            if (size !in 1..16384) throw IOException("Invalid frame length")
            val bytes = ByteArray(size); DataInputStream(input).readFully(bytes)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            return JSONObject(text)
        } catch (error: IOException) { throw error }
        catch (error: Exception) { throw IOException("Invalid frame", error) }
    }
    fun write(output: OutputStream, header: JSONObject, payload: ByteArray? = null) {
        val bytes = header.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..16384 || (payload?.size ?: 0) > 4 * 1024 * 1024) throw IOException("Invalid frame length")
        DataOutputStream(output).writeInt(bytes.size)
        output.write(bytes); payload?.let { output.write(it) }; output.flush()
    }
}
class ServerFailure(val code: String) : IOException(code)
fun request(op: String) = JSONObject().put("version", 1).put("op", op)
