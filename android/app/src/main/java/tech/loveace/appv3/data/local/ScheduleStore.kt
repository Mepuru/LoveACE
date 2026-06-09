package tech.loveace.appv3.data.local

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tech.loveace.appv3.data.model.ScheduleCourse

/**
 * 课程表 & 学期信息缓存 — SharedPreferences
 * 供小组件读取，不需要登录和网络
 * 按用户学号隔离存储
 */
class ScheduleStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** 当前活跃用户 ID（学号），由外部设置 */
    var activeUserId: String = ""

    private fun key(base: String) = if (activeUserId.isNotEmpty()) "${activeUserId}_$base" else base

    // ── 课程缓存 ──

    fun saveCourses(courses: List<ScheduleCourse>) {
        prefs.edit()
            .putString(key(KEY_COURSES), json.encodeToString(courses))
            .putLong(key(KEY_UPDATED_AT), System.currentTimeMillis())
            .apply()
    }

    fun loadCourses(): List<ScheduleCourse> {
        val data = prefs.getString(key(KEY_COURSES), null) ?: return emptyList()
        return try { json.decodeFromString<List<ScheduleCourse>>(data) } catch (_: Exception) { emptyList() }
    }

    fun hasCourses(): Boolean = prefs.contains(key(KEY_COURSES))

    // ── 学期信息缓存 ──

    fun saveSemesterJson(rawJson: String) {
        prefs.edit()
            .putString(key(KEY_SEMESTER), rawJson)
            .putLong(key(KEY_SEMESTER_UPDATED), System.currentTimeMillis())
            .apply()
    }

    fun loadSemesterJson(): String? = prefs.getString(key(KEY_SEMESTER), null)

    fun clear() {
        // 只清除当前用户的数据
        prefs.edit()
            .remove(key(KEY_COURSES))
            .remove(key(KEY_UPDATED_AT))
            .remove(key(KEY_SEMESTER))
            .remove(key(KEY_SEMESTER_UPDATED))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "schedule_cache"
        private const val KEY_COURSES = "courses_json"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_SEMESTER = "semester_json"
        private const val KEY_SEMESTER_UPDATED = "semester_updated_at"
    }
}
