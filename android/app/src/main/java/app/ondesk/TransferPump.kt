package app.ondesk

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.IOException
import java.io.RandomAccessFile

fun interface Exchange { fun call(header: JSONObject, payload: ByteArray?): JSONObject }
class TransferPump {
    suspend fun send(entry: QueueEntry, token: String, exchange: Exchange, progress: suspend (Long) -> Unit): String {
        entry.link?.let { link ->
            val reply = exchange.call(request("open_link").put("token", token).put("id", entry.id).put("url", SharedLink.validate(link)), null)
            if (!reply.optBoolean("complete") || !reply.optBoolean("opened")) throw IOException("Mac did not acknowledge browser launch")
            return "Ссылка"
        }
        val metadata = JSONObject().put("id", entry.id).put("name", entry.name).put("mime", entry.mime).put("size", entry.size).put("sha256", entry.sha256)
        var reply = exchange.call(request("begin").put("token", token).put("transfer", metadata), null)
        if (reply.optBoolean("complete")) return receipt(reply, entry.size)
        var offset = reply.getLong("offset")
        if (offset !in 0..entry.size) throw ServerFailure("offset")
        progress(offset)
        RandomAccessFile(entry.path, "r").use { file ->
            if (file.length() != entry.size) throw ServerFailure("local_file")
            file.seek(offset)
            while (offset < entry.size) {
                currentCoroutineContext().ensureActive()
                val bytes = ByteArray(minOf(4194304L, entry.size - offset).toInt())
                file.readFully(bytes)
                reply = exchange.call(request("chunk").put("token", token).put("id", entry.id).put("offset", offset).put("payloadLength", bytes.size), bytes)
                val next = reply.getLong("offset")
                if (next != offset + bytes.size) throw ServerFailure("offset")
                offset = next; progress(offset)
            }
        }
        currentCoroutineContext().ensureActive()
        return receipt(exchange.call(request("finish").put("token", token).put("id", entry.id), null), entry.size)
    }
    private fun receipt(reply: JSONObject, size: Long): String {
        if (!reply.optBoolean("complete") || reply.optLong("offset", -1) != size || reply.optString("savedName").isEmpty()) throw IOException("Mac did not confirm saving")
        return reply.getString("savedName")
    }
}
