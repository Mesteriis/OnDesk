package app.ondesk

import android.app.Application
import androidx.room.Room
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit

class OnDeskApplication : Application() {
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { Room.databaseBuilder(this, QueueDatabase::class.java, "queue.db").build() }
    val pairing by lazy { PairingStore(this) }
    val transfers = kotlinx.coroutines.sync.Mutex()
    val queueMutations = kotlinx.coroutines.sync.Mutex()
    override fun onCreate() {
        super.onCreate()
        backgroundScope.launch {
            try {
                queueMutations.withLock {
                    FileSpool(File(filesDir, "queue")).reconcile(database.queue().all().map { it.path }.toSet())
                }
            } catch (_: Exception) {
                getSharedPreferences("status", MODE_PRIVATE).edit().putString("message", "Не удалось очистить незавершённые копии. Проверьте свободное место.").apply()
            }
        }
        val periodic = PeriodicWorkRequestBuilder<TransferWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("ondesk-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
        TransferWorker.schedule(this)
    }
}
