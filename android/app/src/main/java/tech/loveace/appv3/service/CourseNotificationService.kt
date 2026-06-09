package tech.loveace.appv3.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import tech.loveace.appv3.MainActivity
import tech.loveace.appv3.R
import tech.loveace.appv3.widget.WidgetState
import tech.loveace.appv3.widget.fetchWidgetState

class CourseNotificationService : Service() {

    companion object {
        private const val TAG = "CourseNotif"
        private const val CHANNEL_ID = "course_tip"
        private const val CHANNEL_REMINDER_ID = "course_reminder"
        private const val NOTIFICATION_ID = 10001
        private const val REMINDER_NOTIFICATION_ID = 10002
        private const val REFRESH_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CourseNotificationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CourseNotificationService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshNotification()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }
    private var lastRemindedKey: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshNotification()
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        getSystemService(NotificationManager::class.java)?.cancel(REMINDER_NOTIFICATION_ID)
        super.onDestroy()
    }

    // ── Channels ──

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "课程提示", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "常驻通知栏显示课程信息"
                setShowBadge(false)
            })
        }
        if (nm.getNotificationChannel(CHANNEL_REMINDER_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_REMINDER_ID, "课前提醒", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "上课前10分钟提醒"
            })
        }
    }

    // ── Core refresh ──

    private fun refreshNotification() {
        val result = try {
            resolveState(fetchWidgetState(this))
        } catch (e: Exception) {
            Log.e(TAG, "refreshNotification failed", e)
            ResolvedState("课程提示", "数据加载失败")
        }

        startForeground(NOTIFICATION_ID, buildMainNotification(result))

        if (result.postReminder) postReminder(result.reminderTitle, result.reminderText)
        if (result.clearReminder) dismissReminder()
    }

    // ── State resolution ──

    private data class ResolvedState(
        val title: String,
        val text: String,
        val inClass: Boolean = false,
        val preClass: Boolean = false,
        val progressMax: Int = 0,
        val progressCur: Int = 0,
        val postReminder: Boolean = false,
        val clearReminder: Boolean = false,
        val reminderTitle: String = "",
        val reminderText: String = "",
    )

    private fun resolveState(state: WidgetState): ResolvedState = when (state) {
        is WidgetState.InSession -> resolveInSession(state)
        is WidgetState.NoCourseToday -> {
            val next = state.nextCourse
            if (next != null) {
                val day = state.nextCourseDay ?: ""
                ResolvedState(next.courseName, "$day ${next.timeText} · ${next.location}".trim())
            } else {
                ResolvedState("今日无课", "${state.semesterName} 第${state.currentWeek}周")
            }
        }
        is WidgetState.Vacation -> {
            val d = state.daysUntilStart?.let { "还有${it}天开学" } ?: ""
            ResolvedState("假期中", "${state.nextSemesterName ?: ""} $d".trim())
        }
        is WidgetState.NoCache -> ResolvedState("课程提示", "请打开 App 同步数据")
        is WidgetState.NotLoggedIn -> ResolvedState("课程提示", "请先登录")
        is WidgetState.Error -> ResolvedState("课程提示", "数据异常")
    }

    private fun resolveInSession(state: WidgetState.InSession): ResolvedState {
        if (state.todayCourses.isEmpty()) {
            return ResolvedState("今日无课", "${state.semesterName} 第${state.currentWeek}周")
        }

        val now = java.time.LocalTime.now()

        val current = state.todayCourses.lastOrNull { entry ->
            val start = parseSessionTime(entry.sessionStart)
            val end = courseEndTime(entry.sessionEnd)
            start != null && end != null && !now.isBefore(start) && now.isBefore(end)
        }
        if (current != null) {
            val startTime = parseSessionTime(current.sessionStart)!!
            val endTime = courseEndTime(current.sessionEnd)!!
            val totalMin = java.time.Duration.between(startTime, endTime).toMinutes().toInt()
            val elapsedMin = java.time.Duration.between(startTime, now).toMinutes().toInt()
            val endStr = courseEndTimeStr(current.sessionEnd)

            val parts = mutableListOf("${endStr}下课")
            if (current.location.isNotEmpty()) parts += current.location
            if (current.teacherName.isNotEmpty()) parts += current.teacherName
            return ResolvedState(
                title = "${current.courseName}（上课中）",
                text = parts.joinToString(" · "),
                inClass = true,
                progressMax = totalMin,
                progressCur = elapsedMin,
                clearReminder = true,
            )
        }

        val next = state.todayCourses.firstOrNull { entry ->
            val start = parseSessionTime(entry.sessionStart)
            start != null && now.isBefore(start)
        }
        if (next != null) {
            val start = parseSessionTime(next.sessionStart)!!
            val minutesUntil = java.time.Duration.between(now, start).toMinutes()
            val sessionKey = "${java.time.LocalDate.now()}-${next.sessionStart}"

            val shouldRemind = minutesUntil in 0..10 && lastRemindedKey != sessionKey
            if (shouldRemind) lastRemindedKey = sessionKey

            val parts = mutableListOf(next.timeText)
            if (next.location.isNotEmpty()) parts += next.location
            if (next.teacherName.isNotEmpty()) parts += next.teacherName

            return ResolvedState(
                title = next.courseName,
                text = parts.joinToString(" · "),
                preClass = minutesUntil <= 10,
                postReminder = shouldRemind,
                reminderTitle = "${next.courseName} 即将开始",
                reminderText = buildString {
                    append("${minutesUntil}分钟后")
                    if (next.location.isNotEmpty()) append(" · ${next.location}")
                    if (next.teacherName.isNotEmpty()) append(" · ${next.teacherName}")
                },
            )
        }

        return ResolvedState("今日课程已结束", "共${state.todayCourses.size}节课")
    }

    // ── Notification builders ──

    private fun buildMainNotification(result: ResolvedState): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                putExtra("navigate_to", "schedule")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_schedule)
            .setContentTitle(result.title)
            .setContentText(result.text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFF5B5FE6.toInt())
            .setSilent(true)
            .setShowWhen(false)

        when {
            result.inClass && result.progressMax > 0 ->
                builder.setProgress(result.progressMax, result.progressCur, false)
            result.preClass ->
                builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun postReminder(title: String, text: String) {
        val pi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                putExtra("navigate_to", "schedule")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_REMINDER_ID)
            .setSmallIcon(R.drawable.ic_notif_schedule)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFF5B5FE6.toInt())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(REMINDER_NOTIFICATION_ID, n)
    }

    private fun dismissReminder() {
        getSystemService(NotificationManager::class.java)?.cancel(REMINDER_NOTIFICATION_ID)
    }

    // ── Time helpers ──

    private fun courseEndTime(sessionEnd: Int): java.time.LocalTime? =
        parseSessionTime(sessionEnd)?.plusMinutes(45)

    private fun courseEndTimeStr(sessionEnd: Int): String {
        val t = courseEndTime(sessionEnd) ?: return ""
        return "%02d:%02d".format(t.hour, t.minute)
    }

    private val sessionStartTimes = mapOf(
        1 to "08:00", 2 to "08:55", 3 to "10:10", 4 to "11:05",
        5 to "14:00", 6 to "14:55", 7 to "16:10", 8 to "17:05",
        9 to "19:00", 10 to "19:55", 11 to "20:50",
    )

    private fun parseSessionTime(session: Int): java.time.LocalTime? {
        val str = sessionStartTimes[session] ?: return null
        return try { java.time.LocalTime.parse(str) } catch (_: Exception) { null }
    }
}
