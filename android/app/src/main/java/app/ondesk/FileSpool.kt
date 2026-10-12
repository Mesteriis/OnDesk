package app.ondesk

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class SpooledFile(val id: String, val name: String, val mime: String, val size: Long, val sha256: String, val path: String)
class FileSpool(private val directory: File) {
    // Caller serializes with imports; only task-owned UUID spool files are removed.
    fun reconcile(retainedPaths: Set<String>) {
        for (file in directory.listFiles().orEmpty()) {
            if (file.extension !in setOf("part", "ready") || file.absolutePath in retainedPaths) continue
            try { UUID.fromString(file.nameWithoutExtension) } catch (_: IllegalArgumentException) { continue }
            check(file.delete()) { "Не удалось очистить незавершённую копию" }
        }
    }
    fun copy(source: InputStream, name: String, mime: String): SpooledFile {
        require(name.isNotBlank() && name != "." && name != ".." && name.toByteArray().size <= 220 && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Неподдерживаемое имя файла" }
        require(mime.toByteArray().size <= 256) { "Неподдерживаемый тип файла" }
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать очередь" }
        val id = UUID.randomUUID().toString()
        val temporary = File(directory, "$id.part")
        val ready = File(directory, "$id.ready")
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var size = 0L
            source.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        hash.update(buffer, 0, count)
                        size += count
                    }
                    output.fd.sync()
                }
            }
            check(temporary.renameTo(ready)) { "Не удалось сохранить файл очереди" }
            return SpooledFile(id, name, mime, size, hash.digest().joinToString("") { "%02x".format(it) }, ready.absolutePath)
        } catch (error: Exception) {
            temporary.delete(); ready.delete()
            throw error
        }
    }
}
