package app.ondesk

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File

class QueueRepository(private val context: Context) {
    private val app get() = context.applicationContext as OnDeskApplication
    suspend fun enqueueLink(text: String, shareBatchID: String? = null): Int = withContext(Dispatchers.IO) {
        val link = SharedLink.extract(text)
        app.queueMutations.withLock {
            if (shareBatchID == null || app.database.queue().all().none { it.shareBatchID == shareBatchID }) {
                app.database.queue().insert(QueueEntry(java.util.UUID.randomUUID().toString(), "Ссылка: ${java.net.URI(link).host}", "text/uri-list", 0, "", "", app.pairing.read()?.serverID, link = link, shareBatchID = shareBatchID))
            }
        }
        TransferWorker.schedule(context)
        1
    }
    suspend fun import(uris: List<Uri>, intentMime: String?, shareBatchID: String? = null): Int = withContext(Dispatchers.IO) {
        var imported = 0
        // Serialize imports with recipient changes, independently of network sends.
        app.queueMutations.withLock {
            val serverID = app.pairing.read()?.serverID
            // A restored transparent activity resumes after the durable prefix, including
            // receipts whose payloads have already been delivered and cleaned up.
            imported = if (shareBatchID == null) 0 else app.database.queue().all().count { it.shareBatchID == shareBatchID }
            check(imported <= uris.size)
            for (uri in uris.drop(imported)) {
                require(uri.scheme == "content") { "Поддерживаются файлы из меню «Поделиться»" }
                var name: String? = null
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) name = cursor.getString(0)
                }
                val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Исходный файл недоступен")
                val mime = context.contentResolver.getType(uri) ?: intentMime?.takeUnless { it == "*/*" } ?: "application/octet-stream"
                val directory = File(context.filesDir, "queue")
                val copy = input.use { FileSpool(directory).copy(it, name ?: "received-file", mime) }
                try {
                    val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
                    try { Os.fsync(fd) } finally { Os.close(fd) }
                    app.database.queue().insert(QueueEntry(copy.id, copy.name, copy.mime, copy.size, copy.sha256, copy.path, serverID, shareBatchID = shareBatchID))
                } catch (error: Exception) { File(copy.path).delete(); throw error }
                imported++
            }
        }
        TransferWorker.schedule(context)
        imported
    }
    suspend fun enqueueCapture(file: File, mime: String, name: String, expectedServerID: String? = null): String = withContext(Dispatchers.IO) {
        val id = java.util.UUID.fromString(file.nameWithoutExtension).toString()
        require(file.canonicalFile.parentFile == File(app.filesDir, "captures").canonicalFile)
        require(mime in setOf("image/png", "image/jpeg") && name.isNotBlank() && name.toByteArray().size <= 220 && name.none { it == '/' || it == '\\' || it.isISOControl() })
        app.queueMutations.withLock {
            val recipient = app.pairing.read()?.serverID
            require(expectedServerID == null || expectedServerID == recipient) { "Сопряжённый Mac изменился. Фото не отправлено." }
            val existing = app.database.queue().all().firstOrNull { it.id == id }
            if (existing == null) {
                require(file.isFile && file.length() > 0)
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == mime)
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) { val n = input.read(buffer); if (n == -1) break; digest.update(buffer, 0, n) }
                }
                java.io.RandomAccessFile(file, "rw").use { it.fd.sync() }
                val fd = Os.open(file.parentFile!!.path, OsConstants.O_RDONLY, 0)
                try { Os.fsync(fd) } finally { Os.close(fd) }
                app.database.queue().insert(QueueEntry(id, name, mime, file.length(), digest.digest().joinToString("") { "%02x".format(it) }, file.absolutePath, recipient))
            } else { require(existing.path == file.absolutePath && existing.mime == mime) }
        }
        TransferWorker.schedule(context)
        id
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        app.queueMutations.withLock {
            val row = app.database.queue().all().firstOrNull { it.id == id } ?: return@withLock
            check(row.state != "sending" || !app.transfers.isLocked) { "Дождитесь завершения текущей передачи" }
            check(row.link != null || !File(row.path).exists() || File(row.path).delete()) { "Не удалось удалить копию файла" }
            app.database.queue().delete(id)
        }
    }
}
