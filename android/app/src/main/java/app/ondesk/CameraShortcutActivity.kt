package app.ondesk

import android.app.Application
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

open class CameraShortcutActivity : ComponentActivity() {
    protected open fun remoteTicket(restoring: Boolean): Pair<String, String>? = null
    private var ticket: Pair<String, String>? = null
    private lateinit var model: CameraCaptureModel
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        model.file?.let { revokeUriPermission(FileProvider.getUriForFile(this, "$packageName.captures", it), Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (success) { model.photoReady = true; model.enqueue() }
        else { model.file?.delete(); cancelRemote(); finish() }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else { cancelRemote(); Toast.makeText(this, "Разрешите камеру для съёмки", Toast.LENGTH_LONG).show(); finish() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ticket = remoteTicket(savedInstanceState != null)
        if (this is RemoteCameraActivity && ticket == null) { finish(); return }
        model = ViewModelProvider(this)[CameraCaptureModel::class.java]
        model.expectedServerID = ticket?.second
        if (model.file == null) {
            val name = savedInstanceState?.getString("capture")
            model.file = name?.takeIf { runCatching { UUID.fromString(it.substringBeforeLast('.')) }.isSuccess && it.endsWith(".jpg") }
                ?.let { File(File(filesDir, "captures"), it) } ?: ticket?.let { File(File(filesDir, "captures").apply { mkdirs() }, "${it.first}.jpg") } ?: newCaptureFile(this, "jpg")
            model.photoReady = savedInstanceState?.getBoolean("ready") ?: false
        }
        setContent {
            MaterialTheme {
                LaunchedEffect(model.done) { if (model.done) { Toast.makeText(this@CameraShortcutActivity, "Фото добавлено в очередь OnDesk", Toast.LENGTH_SHORT).show(); finish() } }
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Камера → Mac", style = MaterialTheme.typography.headlineSmall)
                    Text(model.error ?: "Сохранение фото в очередь…")
                    if (model.error != null) Button(onClick = { model.enqueue() }) { Text("Повторить сохранение") }
                    else CircularProgressIndicator()
                    TextButton(onClick = { finish() }) { Text("Закрыть") }
                }
            }
        }
        if (model.photoReady) model.enqueue()
        else if (savedInstanceState == null && !model.cameraStarted) {
            model.cameraStarted = true
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
            else permission.launch(Manifest.permission.CAMERA)
        }
    }
    private fun cancelRemote() { ticket?.let { RemoteCameraControl.cancel(this, it.first, it.second) } }
    private fun launchCamera() {
        try { camera.launch(FileProvider.getUriForFile(this, "$packageName.captures", requireNotNull(model.file))) }
        catch (_: Exception) { cancelRemote(); model.error = "Не удалось открыть приложение камеры." }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("capture", model.file?.name); outState.putBoolean("ready", model.photoReady)
        super.onSaveInstanceState(outState)
    }
}
class CameraCaptureModel(application: Application) : AndroidViewModel(application) {
    var file: File? = null
    var expectedServerID: String? = null
    var photoReady = false
    var cameraStarted = false
    var error by mutableStateOf<String?>(null)
    var done by mutableStateOf(false)
    private var importing = false
    fun enqueue() {
        if (importing || done || !photoReady) return
        importing = true; error = null
        viewModelScope.launch {
            try {
                QueueRepository(getApplication()).enqueueCapture(requireNotNull(file), "image/jpeg", "OnDesk-Photo-${file!!.nameWithoutExtension}.jpg", expectedServerID)
                done = true
            } catch (_: Exception) { error = "Фото пока не добавлено в очередь. Проверьте свободное место и повторите сохранение." }
            finally { importing = false }
        }
    }
}
