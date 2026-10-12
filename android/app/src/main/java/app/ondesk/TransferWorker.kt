package app.ondesk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit

class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext as OnDeskApplication
    private val dao get() = app.database.queue()
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        app.transfers.withLock {
            cleanupReceipts()
            val mac = try { app.pairing.read() } catch (_: Exception) { status("Сопряжение недоступно. Подключите Mac заново."); return@withLock Result.success() }
                ?: run {
                    if (dao.next() != null) ShareFeedback.problem(applicationContext, "Файлы в очереди. Откройте OnDesk и подключите Mac.")
                    return@withLock Result.success()
                }
            if (Build.VERSION.SDK_INT >= 37 && applicationContext.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
                if (dao.next() != null) ShareFeedback.problem(applicationContext, "Разрешите доступ к локальной сети в OnDesk.")
                return@withLock Result.success()
            }
            dao.assignUnpaired(mac.serverID)
            if (dao.next() == null) return@withLock Result.success()
            try { setForeground(notification("Подключение к Mac…")) }
            catch (_: Exception) { status("Ожидание фоновой отправки Android"); return@withLock Result.retry() }
            val client = try { connect(mac) }
            catch (_: javax.net.ssl.SSLException) { ShareFeedback.problem(applicationContext, "Сертификат Mac изменился. Выполните сопряжение заново."); return@withLock Result.success() }
            catch (_: Exception) { status("Mac недоступен · файлы в очереди"); return@withLock Result.retry() }
            client.use {
                while (true) {
                    ensureActive()
                    val entry = app.queueMutations.withLock {
                        dao.next()?.also { dao.progress(it.id, it.offset, "sending", null) }
                    } ?: break
                    if (entry.serverID != mac.serverID) { dao.progress(entry.id, entry.offset, "blocked", "Файл ожидает другой Mac"); continue }
                    try {
                        setForeground(notification(entry.name))
                        val savedName = TransferPump().send(entry, mac.token, Exchange { header, payload -> client.exchange(header, payload) }) { offset ->
                            dao.progress(entry.id, offset, "sending", null)
                        }
                        // Persist receipt before deleting bytes. A crash at any following
                        // point leaves a delivered row for cleanup, never a lost queued file.
                        dao.progress(entry.id, entry.size, "delivered", null)
                        if (entry.link != null || !File(entry.path).exists() || File(entry.path).delete()) dao.deleteUnsharedDelivered(entry.id)
                        status(if (entry.link != null) "Ссылка передана браузеру Mac" else "Сохранён на Mac: $savedName")
                    } catch (error: CancellationException) { throw error }
                    catch (error: ServerFailure) {
                        val permanent = error.code in setOf("unauthorized", "checksum", "conflict", "invalid", "incompatible", "local_file", "open_uncertain", "open_failed")
                        dao.failure(entry.id, if (permanent) "blocked" else "queued", message(error.code))
                        status(message(error.code))
                        ShareFeedback.problem(applicationContext, message(error.code))
                        return@withLock if (permanent) Result.success() else Result.retry()
                    } catch (_: Exception) {
                        dao.failure(entry.id, "queued", "Связь прервалась · повторим автоматически")
                        status("Mac недоступен · файлы в очереди")
                        return@withLock Result.retry()
                    }
                }
            }
            Result.success()
        }
    }
    private suspend fun connect(mac: PairedMac): TlsClient {
        val result = connectRememberedMac(mac.host, mac.port, { MacDiscovery(applicationContext).find(mac.serverID) }) { host, port -> TlsClient(host, port, mac.fingerprint) }
        if (result.address != (mac.host to mac.port)) {
            try { app.pairing.save(mac.copy(host = result.address.first, port = result.address.second)) }
            catch (error: Exception) { result.client.close(); throw error }
        }
        return result.client
    }
    private suspend fun cleanupReceipts() = app.queueMutations.withLock {
        FileSpool(File(app.filesDir, "queue")).reconcile(dao.all().map { it.path }.toSet())
        dao.all().filter { it.state == "delivered" }.forEach {
            if (it.link != null || !File(it.path).exists() || File(it.path).delete()) dao.deleteUnsharedDelivered(it.id)
        }
    }
    private fun status(text: String) { applicationContext.getSharedPreferences("status", Context.MODE_PRIVATE).edit().putString("message", text).apply() }
    private fun notification(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("transfers", "Передача файлов", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, "transfers")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("OnDesk").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Остановить", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return ForegroundInfo(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    companion object {
        fun schedule(context: Context, immediate: Boolean = false) {
            val work = OneTimeWorkRequestBuilder<TransferWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
            val manager = WorkManager.getInstance(context)
            if (immediate) manager.enqueue(work)
            else manager.enqueueUniqueWork("ondesk-send", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
        fun message(code: String): String = when (code) {
            "open_uncertain" -> "Mac прервался во время открытия ссылки. Проверьте браузер; если вкладки нет, удалите эту запись и поделитесь ссылкой заново."
            "open_failed" -> "Mac не смог открыть браузер. Проверьте браузер по умолчанию и нажмите «Повторить»."
            "invalid" -> "Данные не поддерживаются. Для ссылок обновите OnDesk на Mac."
            "unauthorized" -> "Доступ отозван. Выполните сопряжение заново."
            "checksum" -> "Проверка целостности не пройдена. Файл сохранён в очереди."
            "local_file" -> "Копия файла в очереди повреждена или недоступна."
            "storage" -> "Mac не смог сохранить файл. Проверьте папку и свободное место."
            "incompatible" -> "Версии OnDesk несовместимы. Обновите оба приложения."
            "conflict" -> "Конфликт передачи. Файл сохранён в очереди."
            else -> "Передача не завершена. Файл сохранён в очереди."
        }
    }
}
