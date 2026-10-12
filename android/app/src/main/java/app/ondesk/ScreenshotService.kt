package app.ondesk

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.*
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class ScreenshotService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val remoteCamera by lazy { RemoteCameraControl(this, scope) }
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var capturing = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CAPTURE || capturing) return
            capturing = true
            val displayID = intent.getIntExtra("display", 0)
            // The shortcut window must disappear before capturing the underlying app.
            handler.postDelayed({ capture(displayID) }, 400)
        }
    }
    override fun onServiceConnected() {
        remoteCamera.start()
        if (!registered) {
            registerReceiver(receiver, IntentFilter(CAPTURE), RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }
    private fun capture(displayID: Int) {
        if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            capturing = false; message("Разблокируйте телефон для снимка экрана"); return
        }
        try {
            takeScreenshot(displayID, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardware = result.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)?.let { wrapped ->
                            try { wrapped.copy(Bitmap.Config.ARGB_8888, false) } finally { wrapped.recycle() }
                        }
                    } catch (_: Exception) { null } finally { hardware.close() }
                    if (bitmap == null) { capturing = false; message("Не удалось получить снимок экрана"); return }
                    if (!scope.isActive) { bitmap.recycle(); return }
                    scope.launch {
                        var file: File? = null
                        try {
                            withContext(Dispatchers.IO) {
                                file = newCaptureFile(this@ScreenshotService, "png")
                                file!!.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                                QueueRepository(this@ScreenshotService).enqueueCapture(file!!, "image/png", "OnDesk-Screenshot-${System.currentTimeMillis()}.png")
                            }
                            message("Скриншот добавлен в очередь OnDesk")
                        } catch (_: Exception) {
                            // An unqueued capture can be discarded; queued copies belong to the sender.
                            withContext(NonCancellable + Dispatchers.IO) {
                                val retained = (application as OnDeskApplication).database.queue().all().any { it.path == file?.absolutePath }
                                if (!retained) file?.delete()
                            }
                            message("Не удалось сохранить скриншот. Проверьте свободное место.")
                        } finally { bitmap.recycle(); capturing = false }
                    }
                }
                override fun onFailure(errorCode: Int) {
                    capturing = false
                    message(if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) "Этот экран защищён от снимков" else "Снимок не получен. Повторите действие через секунду.")
                }
            })
        } catch (_: Exception) { capturing = false; message("Снимок недоступен. Проверьте службу OnDesk.") }
    }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (registered) unregisterReceiver(receiver)
        handler.removeCallbacksAndMessages(null)
        remoteCamera.stop()
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        internal const val CAPTURE = "app.ondesk.internal.CAPTURE_SCREEN"
        fun enabled(context: Context): Boolean = context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.let { info -> info.packageName == context.packageName && info.name == ScreenshotService::class.java.name } }
    }
}
internal fun newCaptureFile(context: Context, extension: String): File {
    require(extension in setOf("png", "jpg"))
    val directory = File(context.filesDir, "captures")
    check(directory.isDirectory || directory.mkdirs())
    return File(directory, "${UUID.randomUUID()}.$extension")
}
