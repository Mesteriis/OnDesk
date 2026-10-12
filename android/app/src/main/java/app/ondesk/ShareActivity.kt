package app.ondesk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/** No content view or app navigation: keep transient URI grants until the durable copy is ready. */
class ShareActivity : ComponentActivity() {
    private lateinit var model: OnDeskViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        model = ViewModelProvider(this)[OnDeskViewModel::class.java]
        val uris = sharedUris(intent)
        val files = uris.filter { it.scheme == "content" }
        model.bindShare(savedInstanceState?.getString("shareBatchID") ?: UUID.randomUUID().toString(), if (files.isEmpty()) 1 else files.size)
        if (files.isNotEmpty()) model.import(files, intent.type)
        else {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }.joinToString("\n").takeIf { it.isNotBlank() } }
                ?: uris.joinToString("\n").takeIf { it.isNotBlank() }
            if (text != null) model.importLink(text)
            else model.error = "В сообщении нет файла или HTTP/HTTPS ссылки."
        }
        lifecycleScope.launch {
            // StateFlow also works without a Compose view/recomposer in this activity.
            if (model.busy || model.error == null) model.importFinished.first { it }
            model.error?.let { ShareFeedback.problem(this@ShareActivity, it) }
                ?: Toast.makeText(this@ShareActivity, "Добавлено в очередь: ${model.imported}", Toast.LENGTH_SHORT).show()
            model.releaseShare()
            finish()
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("shareBatchID", model.shareBatchID)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (isFinishing && ::model.isInitialized) model.releaseShare()
        super.onDestroy()
    }
    private fun sharedUris(intent: Intent): List<Uri> {
        val result = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) result.addAll(intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty())
        else intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { result.add(it) }
        intent.clipData?.let { clip -> for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let { result.add(it) } }
        return result.distinct()
    }
}
