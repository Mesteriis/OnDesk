package app.ondesk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.NotificationCompat

object ShareFeedback {
    const val ERROR_NOTIFICATION_ID = 1002
    fun problem(context: Context, message: String) {
        context.getSharedPreferences("status", Context.MODE_PRIVATE).edit()
            .putString("message", message).putString("share_error", message).apply()
        if (context is ShareActivity) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("share-errors", "Ошибки отправки", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = NotificationCompat.Builder(context, "share-errors")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("OnDesk · нужно внимание")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).addAction(0, "Открыть очередь", open).setAutoCancel(true).build()
        try { manager.notify(ERROR_NOTIFICATION_ID, notice) } catch (_: SecurityException) { /* The error remains available in the app. */ }
    }
}
