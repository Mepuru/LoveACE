package tech.loveace.appv3.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import tech.loveace.appv3.MainActivity
import tech.loveace.appv3.R

object SmartCourseSelectionNotifier {
    private const val CHANNEL_ID = "smart_course_selection"
    private const val NOTIFICATION_ID = 3301

    fun show(context: Context, title: String, text: String, indeterminate: Boolean = false) {
        if (!canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(nm)
        val pi = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_schedule)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setColor(0xFF5B5FE6.toInt())
            .setSilent(true)
            .setOngoing(indeterminate)
            .setShowWhen(false)
            .apply {
                if (indeterminate) setProgress(0, 0, true)
            }
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun canPost(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "智能选课", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示智能选课网页连接和数据上传状态"
                setShowBadge(false)
            },
        )
    }
}
