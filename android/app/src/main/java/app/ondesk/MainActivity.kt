package app.ondesk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var model: OnDeskViewModel
    private var scanAfterPermissions = false
    private val scan = registerForActivityResult(ScanContract()) { result -> result.contents?.let { model.scanned(it) } }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (scanAfterPermissions && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && localPermission()) {
            scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("QR-код из настроек OnDesk на Mac").setBeepEnabled(false).setOrientationLocked(false))
        } else if (!localPermission()) { model.error = "Для отправки файлов разрешите OnDesk доступ к локальной сети в настройках Android." }
        scanAfterPermissions = false
        TransferWorker.schedule(this)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val shared = intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE
        if (shared) setTheme(R.style.Theme_OnDesk_Shortcut)
        super.onCreate(savedInstanceState)
        if (shared) {
            // Compatibility with callers that cached the old share component.
            startActivity(Intent(intent).setClass(this, ShareActivity::class.java))
            finish()
            return
        }
        model = ViewModelProvider(this)[OnDeskViewModel::class.java]
        setContent {
            val queue by model.queue.collectAsStateWithLifecycle()
            var deleting by remember { mutableStateOf<QueueEntry?>(null) }
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF006F63), background = Color(0xFFF5F8F7), surface = Color.White)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Image(painterResource(R.drawable.ondesk_artwork), "OnDesk", Modifier.size(60.dp))
                            Column { Text("OnDesk", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Text("С телефона — на стол", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(if (model.paired) "Ваш Mac сохранён" else "Подключите Mac", style = MaterialTheme.typography.titleLarge)
                                Text(if (model.paired) model.status else "Откройте настройки OnDesk на Mac и отсканируйте QR-код. Устройства должны быть в одной сети.")
                                Button(onClick = { requestSetup(scan = true) }, enabled = !model.busy) { Text(if (model.paired) "Сопрячь Mac заново" else "Сканировать QR-код") }
                                TextButton(onClick = { startActivity(Intent(this@MainActivity, QuickActionsSetupActivity::class.java)) }) { Text("Быстрые действия") }
                                if (!localPermission()) TextButton(onClick = { requestSetup(scan = false) }) { Text("Разрешить локальную сеть") }
                            }
                        }
                        if (model.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Подготовка… Не закрывайте экран до сохранения файлов в очередь.", style = MaterialTheme.typography.bodySmall) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Очередь · ${queue.count { it.state != "delivered" }}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { model.retry() }, enabled = model.paired && !model.busy) { Text("Повторить") }
                        }
                        if (queue.isEmpty()) {
                            Text("Выберите файл, скриншот или ссылку → «Поделиться» → OnDesk. Если Mac недоступен, отправка дождётся подключения здесь.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(queue, key = { it.id }) { entry ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(entry.name, fontWeight = FontWeight.SemiBold)
                                        entry.link?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
                                        Text("${if (entry.link != null) "Ссылка" else formatSize(entry.size)} · ${stateLabel(entry.state)}", style = MaterialTheme.typography.bodySmall)
                                        if (entry.state == "sending" && entry.size > 0) LinearProgressIndicator(progress = { (entry.offset.toDouble() / entry.size).toFloat() }, modifier = Modifier.fillMaxWidth())
                                        entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                        TextButton(onClick = { deleting = entry }, enabled = !model.busy) { Text("Удалить из очереди") }
                                    }
                                }
                            }
                        }
                    }
                }
                model.error?.let { message -> AlertDialog(onDismissRequest = { model.error = null }, title = { Text("OnDesk") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { model.error = null }) { Text("Понятно") } }) }
                if (model.pendingCode != null) AlertDialog(onDismissRequest = { model.cancelChange() }, title = { Text("Изменить получателя?") }, text = { Text("Элементы очереди будут отправлены на новый Mac. Уже сохранённые на прежнем Mac файлы останутся там.") }, confirmButton = { TextButton(onClick = { model.confirmChange() }) { Text("Сменить Mac") } }, dismissButton = { TextButton(onClick = { model.cancelChange() }) { Text("Отмена") } })
                deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить из очереди?") }, text = { Text(if (entry.link != null) "Ссылка будет удалена из очереди OnDesk." else "Копия «${entry.name}» будет удалена из OnDesk. Исходный файл останется на телефоне.") }, confirmButton = { TextButton(onClick = { model.delete(entry.id); deleting = null }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }) }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) {
            val prefs = getSharedPreferences("status", MODE_PRIVATE)
            prefs.getString("share_error", null)?.let {
                model.error = it
                prefs.edit().remove("share_error").apply()
            }
        }
    }
    private fun localPermission() = Build.VERSION.SDK_INT < 37 || checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED
    private fun requestSetup(scan: Boolean) {
        scanAfterPermissions = scan
        val requested = mutableListOf(Manifest.permission.POST_NOTIFICATIONS)
        if (scan) requested.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 37) requested.add("android.permission.ACCESS_LOCAL_NETWORK")
        permissions.launch(requested.toTypedArray())
    }

}
private fun stateLabel(state: String) = when (state) { "sending" -> "Отправляется"; "blocked" -> "Нужно внимание"; "delivered" -> "Сохранён на Mac"; else -> "Ожидает Mac" }
private fun formatSize(size: Long): String = when { size >= 1073741824L -> "%.1f ГБ".format(size.toDouble()/1073741824); size >= 1048576 -> "%.1f МБ".format(size.toDouble()/1048576); size >= 1024 -> "%.1f КБ".format(size.toDouble()/1024); else -> "$size Б" }
