package app.ondesk

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.*

class TlsClient(host: String, port: Int, fingerprint: String) : Closeable {
    private val socket: SSLSocket
    private val input: BufferedInputStream
    private val output: BufferedOutputStream
    init {
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, type: String) { throw java.security.cert.CertificateException("Client certificates unsupported") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, type: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("Missing certificate")
                chain[0].checkValidity()
                val digest = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                val expected = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (!MessageDigest.isEqual(digest, expected)) throw java.security.cert.CertificateException("Mac certificate changed")
            }
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            socket.soTimeout = 120000
            socket.connect(InetSocketAddress(host, port), 8000)
            socket.startHandshake()
            input = BufferedInputStream(socket.inputStream)
            output = BufferedOutputStream(socket.outputStream)
        } catch (error: Exception) { socket.close(); throw error }
    }
    @Synchronized fun exchange(header: JSONObject, payload: ByteArray? = null): JSONObject {
        FrameCodec.write(output, header, payload)
        val reply = FrameCodec.read(input)
        if (reply.optInt("version") != 1) throw ServerFailure("incompatible")
        if (!reply.getBoolean("ok")) throw ServerFailure(reply.optString("error", "invalid"))
        return reply
    }
    override fun close() { socket.close() }
}
