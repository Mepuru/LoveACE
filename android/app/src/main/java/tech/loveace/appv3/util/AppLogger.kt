package tech.loveace.appv3.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用日志工具 — 写入文件，支持导出分享
 */
object AppLogger {

    private const val LOG_FILE = "app_debug.log"
    private const val MAX_SIZE = 2 * 1024 * 1024 // 2MB

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE)
        installCrashHandler()
    }

    private fun installCrashHandler() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val file = logFile ?: return@setDefaultUncaughtExceptionHandler
                // 崩溃日志始终写入
                FileWriter(file, true).use { w ->
                    w.appendLine("${dateFormat.format(Date())} [CRASH] Thread: ${thread.name}")
                    w.appendLine("${dateFormat.format(Date())} [CRASH] ${throwable::class.simpleName}: ${throwable.message}")
                    w.appendLine(throwable.stackTraceToString())
                    w.flush()
                }
            } catch (_: Exception) {}
            // 交还给系统默认处理（弹出崩溃对话框 / 杀进程）
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun log(tag: String, message: String) {
        val file = logFile ?: return
        try {
            trimIfNeeded(file)
            FileWriter(file, true).use { w ->
                w.appendLine("${dateFormat.format(Date())} [$tag] $message")
            }
        } catch (_: Exception) {}
    }

    fun logException(tag: String, throwable: Throwable) {
        log(tag, "EXCEPTION: ${throwable::class.simpleName}: ${throwable.message}")
        log(tag, throwable.stackTraceToString().take(2000))
    }

    fun getLogContent(): String {
        return try { logFile?.readText() ?: "(无日志)" } catch (_: Exception) { "(读取失败)" }
    }

    fun clearLogs() {
        try { logFile?.writeText("") } catch (_: Exception) {}
    }

    /** 分享日志文件 */
    fun shareLogFile(context: Context) {
        val file = logFile ?: return
        if (!file.exists() || file.length() == 0L) return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "导出日志"))
        } catch (_: Exception) {
            // fallback: 复制到外部缓存
            val export = File(context.externalCacheDir, "loveace_debug.log")
            file.copyTo(export, overwrite = true)
        }
    }

    private fun trimIfNeeded(file: File) {
        if (file.exists() && file.length() > MAX_SIZE) {
            val lines = file.readLines()
            file.writeText(lines.takeLast(lines.size / 2).joinToString("\n"))
        }
    }
}
