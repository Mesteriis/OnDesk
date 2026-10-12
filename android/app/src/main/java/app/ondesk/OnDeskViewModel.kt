package app.ondesk

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.withLock

class OnDeskViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OnDeskApplication
    val queue = app.database.queue().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var paired by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("Подключите Mac, чтобы отправлять файлы"); private set
    var error by mutableStateOf<String?>(null)
    var pendingCode by mutableStateOf<PairingCode?>(null); private set
    var imported by mutableStateOf<Int?>(null); private set
    private val importComplete = kotlinx.coroutines.flow.MutableStateFlow(false)
    val importFinished: kotlinx.coroutines.flow.StateFlow<Boolean> = importComplete
    var shareBatchID: String? = null; private set
    var shareExpected = 0; private set
    private var shareJob: Job? = null
    private var shareReleased = false
    fun bindShare(id: String, expected: Int) {
        require(java.util.UUID.fromString(id).toString() == id)
        check(shareBatchID == null || shareBatchID == id)
        shareBatchID = id; shareExpected = expected
    }
    fun releaseShare() {
        val batch = shareBatchID ?: return
        if (shareReleased) return
        shareReleased = true
        val importing = shareJob
        app.backgroundScope.launch {
            // A closing activity may still have an IO copy in progress. Release only
            // after its final insertion, including cancellation and partial imports.
            importing?.join()
            try {
                app.queueMutations.withLock { app.database.queue().releaseShareBatch(batch) }
                TransferWorker.schedule(app)
            } catch (_: Exception) {
                statusPrefs.edit().putString("message", "Файлы доставлены или сохранены в очереди; не удалось очистить состояние окна отправки.").apply()
            }
        }
    }
    private var shareStarted = false
    private val statusPrefs = app.getSharedPreferences("status", Context.MODE_PRIVATE)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "message") viewModelScope.launch { status = statusPrefs.getString("message", "") ?: "" }
    }
    init {
        statusPrefs.registerOnSharedPreferenceChangeListener(listener)
        viewModelScope.launch {
            try {
                paired = withContext(Dispatchers.IO) { app.pairing.read() != null }
                if (paired) status = statusPrefs.getString("message", "Mac сохранён · готов к отправке")!!
            } catch (_: Exception) { error = "Не удалось прочитать сопряжение. Подключите Mac заново." }
        }
    }
    override fun onCleared() { statusPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    fun import(uris: List<Uri>, mime: String?) {
        if (shareStarted) return
        shareStarted = true; busy = true
        shareJob = viewModelScope.launch {
            try { imported = QueueRepository(app).import(uris, mime, shareBatchID) }
            catch (_: Exception) { error = "Не удалось скопировать все файлы. Проверьте доступ к исходникам и свободное место. Уже добавленные файлы остаются в очереди." }
            finally { TransferWorker.schedule(app); busy = false; importComplete.value = true }
        }
    }
    fun importLink(text: String) {
        if (shareStarted) return
        shareStarted = true; busy = true
        shareJob = viewModelScope.launch {
            try { imported = QueueRepository(app).enqueueLink(text, shareBatchID) }
            catch (_: IllegalArgumentException) { error = "Поделитесь одной корректной HTTP/HTTPS ссылкой." }
            catch (_: Exception) { error = "Не удалось сохранить ссылку в очередь. Проверьте свободное место." }
            finally { busy = false; importComplete.value = true }
        }
    }
    fun scanned(text: String) {
        viewModelScope.launch {
            try {
                val code = PairingCode.parse(text)
                val old = withContext(Dispatchers.IO) { app.pairing.read() }
                val queued = withContext(Dispatchers.IO) { app.database.queue().all().any { it.state != "delivered" && it.serverID != null && it.serverID != code.serverID } }
                if (old?.serverID != code.serverID && queued) pendingCode = code
                else pair(code)
            } catch (_: Exception) { error = "Не удалось прочитать код. Отсканируйте новый QR-код из настроек OnDesk на Mac." }
        }
    }
    fun confirmChange() { pendingCode?.let { pair(it, retarget = true) }; pendingCode = null }
    fun cancelChange() { pendingCode = null }
    private fun pair(code: PairingCode, retarget: Boolean = false) {
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.transfers.withLock {
                      app.queueMutations.withLock {
                        val address = MacDiscovery(app).find(code.serverID) ?: (code.host to code.port)
                        TlsClient(address.first, address.second, code.fingerprint).use { client ->
                            val reply = client.exchange(request("pair").put("secret", code.secret).put("deviceID", app.pairing.deviceID).put("deviceName", Build.MODEL.take(60)))
                            check(reply.getString("serverID") == code.serverID)
                            val token = reply.getString("token"); require(token.matches(Regex("[0-9a-f]{64}")))
                            app.pairing.save(PairedMac(code.serverID, address.first, address.second, code.fingerprint, token))
                            if (retarget) app.database.queue().retarget(code.serverID)
                            app.database.queue().assignUnpaired(code.serverID)
                            app.database.queue().retryBlocked()
                        }
                      }
                    }
                }
                paired = true; status = "Mac подключён · файлы отправляются автоматически"
                statusPrefs.edit().putString("message", status).apply()
                TransferWorker.schedule(app)
            } catch (_: Exception) { error = "Сопряжение не завершено. Проверьте общую Wi-Fi сеть, разрешение локальной сети и обновите QR-код на Mac." }
            finally { busy = false }
        }
    }
    fun retry() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.database.queue().retryBlocked() }
            TransferWorker.schedule(app, immediate = true)
        }
    }
    fun delete(id: String) {
        viewModelScope.launch {
            try { QueueRepository(app).delete(id) } catch (failure: Exception) { error = if (failure is IllegalStateException) failure.message else "Не удалось удалить файл из очереди." }
        }
    }
}
