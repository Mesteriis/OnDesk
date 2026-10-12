package app.ondesk

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class QuickActionsSetupActivity : ComponentActivity() {
    private var screenshotEnabled by mutableStateOf(false)
    override fun onResume() { super.onResume(); screenshotEnabled = ScreenshotService.enabled(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Быстрые действия", style = MaterialTheme.typography.headlineMedium)
                    Text("Скриншот → Mac")
                    Text("Один запуск шортката снимает текущий экран и ставит изображение в очередь для вашего Mac. Для этого вручную включите службу OnDesk в специальных возможностях Android. Она получает доступ к изображениям экрана. Снимки создаются только по запуску действия; постоянной записи и чтения элементов интерфейса нет.")
                    Text(if (screenshotEnabled) "Служба скриншотов включена" else "Служба скриншотов выключена")
                    Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text(if (screenshotEnabled) "Настроить доступ" else "Включить службу OnDesk") }
                    Text("Если Android блокирует включение для APK: сведения о приложении OnDesk → меню ⋮ → разрешить ограниченные настройки, затем вернитесь сюда. Включать доступ нужно самостоятельно.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { startActivity(Intent(this@QuickActionsSetupActivity, ScreenshotShortcutActivity::class.java)) }, enabled = screenshotEnabled) { Text("Скриншот → Mac") }
                    HorizontalDivider()
                    Text("⌘⇧1 на Mac → камера телефона")
                    var remoteEnabled by remember { mutableStateOf(RemoteCameraControl.enabled(this@QuickActionsSetupActivity)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = remoteEnabled, onCheckedChange = { enabled ->
                            getSharedPreferences("quick_actions", MODE_PRIVATE).edit().putBoolean("remote_camera", enabled).apply()
                            remoteEnabled = enabled
                        })
                        Text("Разрешить запуск камеры с сопряжённого Mac")
                    }
                    Text("Требуется включённая служба OnDesk и разблокированный телефон в одной сети. Нажмите ⌘⇧1 на Mac, сделайте и подтвердите фото: оно сохранится на Mac и попадёт в буфер для ⌘V. Команда открывает камеру; сама съёмка остаётся за вами.")
                    Text("Камера → Mac")
                    Text("Открывается обычная камера. Сделайте фото и подтвердите его, если камера попросит. После этого OnDesk автоматически ставит фото в очередь. Отменённый снимок не отправляется.")
                    Button(onClick = { startActivity(Intent(this@QuickActionsSetupActivity, CameraShortcutActivity::class.java)) }) { Text("Камера → Mac") }
                    HorizontalDivider()
                    Text("Touch the Notch: назначьте жест → Apps & Shortcuts → In-App Shortcuts → OnDesk → нужное действие. Также оба действия доступны по долгому нажатию на значок OnDesk. Если приложение предлагает только запуск activity, выберите ScreenshotShortcutActivity или CameraShortcutActivity.")
                    Text("Mac может быть недоступен: файлы дождутся отправки в очереди. Защищённые Android экраны снимать нельзя.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { finish() }) { Text("Назад") }
                }
            }
        }
    }
}
