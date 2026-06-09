package tech.loveace.appv3.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tech.loveace.appv3.data.model.ScheduleCourse
import tech.loveace.appv3.data.model.ScheduleTimePlace
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ── 小组件数据（从缓存读取，不做网络请求） ──

// ── JSON 模型 ──

@Serializable
data class WidgetSemesterData(
    val version: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val semesters: List<WidgetSemesterItem> = emptyList(),
)

@Serializable
data class WidgetSemesterItem(
    val code: String,
    val name: String,
    @SerialName("start_date") val startDate: String,
    val weeks: Int = 18,
)

private val TERM_MAP = mapOf("1" to "秋季学期", "2" to "春季学期")

fun WidgetSemesterItem.displayName(): String {
    val parts = code.split("-")
    if (parts.size == 3) {
        val termText = TERM_MAP[parts[2]] ?: "第${parts[2]}学期"
        return "${parts[0]}-${parts[1]} $termText"
    }
    return name.ifEmpty { code }
}

// ── 节次时间映射 ──

private val SESSION_TIMES = mapOf(
    1 to "08:00", 2 to "08:55", 3 to "10:10", 4 to "11:05",
    5 to "14:00", 6 to "14:55", 7 to "16:10", 8 to "17:05",
    9 to "19:00", 10 to "19:55", 11 to "20:50",
)

fun sessionTimeRange(start: Int, end: Int): String {
    val s = SESSION_TIMES[start] ?: "第${start}节"
    val e = SESSION_TIMES[end]?.let { t ->
        val h = t.substringBefore(":").toInt()
        val m = t.substringAfter(":").toInt() + 45
        "%02d:%02d".format(h + m / 60, m % 60)
    } ?: "第${end}节"
    return "$s-$e"
}

// ── 课程配色（与 ScheduleScreen 一致） ──

data class WidgetCourseColor(val bg: Color, val border: Color, val text: Color)

val WIDGET_COURSE_COLORS = listOf(
    WidgetCourseColor(Color(0x2E0078D4), Color(0x800078D4), Color(0xFF0063B1)),
    WidgetCourseColor(Color(0x2E107C10), Color(0x80107C10), Color(0xFF0E6E0E)),
    WidgetCourseColor(Color(0x2E881798), Color(0x80881798), Color(0xFF6B1076)),
    WidgetCourseColor(Color(0x2ECA5010), Color(0x80CA5010), Color(0xFFA34D0A)),
    WidgetCourseColor(Color(0x2E0063B1), Color(0x800063B1), Color(0xFF004E8C)),
    WidgetCourseColor(Color(0x2EE3008C), Color(0x80E3008C), Color(0xFFB3006E)),
    WidgetCourseColor(Color(0x2E008575), Color(0x80008575), Color(0xFF006A5D)),
    WidgetCourseColor(Color(0x2E8E562E), Color(0x808E562E), Color(0xFF6E4224)),
    WidgetCourseColor(Color(0x2E0099BC), Color(0x800099BC), Color(0xFF007A96)),
    WidgetCourseColor(Color(0x2E7A7574), Color(0x807A7574), Color(0xFF3B3A39)),
)

val WIDGET_COURSE_COLORS_DARK = listOf(
    WidgetCourseColor(Color(0x2E4CA6E8), Color(0x804CA6E8), Color(0xFF8DC9F9)),
    WidgetCourseColor(Color(0x2E4CAF50), Color(0x804CAF50), Color(0xFF81C784)),
    WidgetCourseColor(Color(0x2EBA68C8), Color(0x80BA68C8), Color(0xFFCE93D8)),
    WidgetCourseColor(Color(0x2EFF9800), Color(0x80FF9800), Color(0xFFFFCC80)),
    WidgetCourseColor(Color(0x2E42A5F5), Color(0x8042A5F5), Color(0xFF90CAF9)),
    WidgetCourseColor(Color(0x2EEC407A), Color(0x80EC407A), Color(0xFFF48FB1)),
    WidgetCourseColor(Color(0x2E26A69A), Color(0x8026A69A), Color(0xFF80CBC4)),
    WidgetCourseColor(Color(0x2EA1887F), Color(0x80A1887F), Color(0xFFBCAAA4)),
    WidgetCourseColor(Color(0x2E29B6F6), Color(0x8029B6F6), Color(0xFF81D4FA)),
    WidgetCourseColor(Color(0x2E9E9E9E), Color(0x809E9E9E), Color(0xFFBDBDBD)),
)

// ── 小组件课程条目 ──

data class WidgetCourseEntry(
    val courseName: String,
    val location: String,
    val teacherName: String = "",
    val sessionStart: Int,
    val sessionEnd: Int,
    val timeText: String,       // "08:00-09:40"
    val sessionText: String,    // "1-2节"
    val colorIndex: Int = 0,
) {
    val rowSpan get() = sessionEnd - sessionStart + 1
}

// ── 周课表网格 ──

data class WidgetGridCell(
    val entry: WidgetCourseEntry,
    val rowSpan: Int,
)

fun buildWidgetGrid(
    weekCourses: Map<Int, List<WidgetCourseEntry>>,
): Map<String, WidgetGridCell> {
    val grid = mutableMapOf<String, WidgetGridCell>()
    for ((day, entries) in weekCourses) {
        for (entry in entries) {
            val cell = WidgetGridCell(entry, entry.rowSpan)
            for (s in entry.sessionStart..entry.sessionEnd) {
                grid["$day-$s"] = cell
            }
        }
    }
    return grid
}

fun widgetGridMaxSession(grid: Map<String, WidgetGridCell>): Int {
    var max = 0
    for ((_, cell) in grid) {
        val end = cell.entry.sessionEnd
        if (end > max) max = end
    }
    return max.coerceIn(8, 12)
}

fun isWidgetCellCovered(grid: Map<String, WidgetGridCell>, day: Int, session: Int): Boolean {
    for (s in (session - 1) downTo 1) {
        val cell = grid["$day-$s"] ?: continue
        if (s + cell.rowSpan > session) return true
    }
    return false
}

// ── 小组件状态 ──

sealed class WidgetState {
    data class Vacation(
        val nextSemesterName: String? = null,
        val nextStartDate: String? = null,
        val daysUntilStart: Long? = null,
    ) : WidgetState()

    data class InSession(
        val semesterName: String,
        val currentWeek: Int,
        val totalWeeks: Int,
        val dayOfWeek: Int,
        val dayOfWeekText: String,
        val dateText: String,
        val weekDays: List<WeekDayInfo>,
        val isEnding: Boolean,
        val todayCourses: List<WidgetCourseEntry>,
        val weekCourses: Map<Int, List<WidgetCourseEntry>>, // dayOfWeek(1-7) -> courses
        val hasCourseData: Boolean,
    ) : WidgetState()

    /** 学期中，有课程数据，但今天没课 */
    data class NoCourseToday(
        val semesterName: String,
        val currentWeek: Int,
        val totalWeeks: Int,
        val dayOfWeek: Int,
        val dayOfWeekText: String,
        val dateText: String,
        val weekDays: List<WeekDayInfo>,
        val isEnding: Boolean,
        val weekCourses: Map<Int, List<WidgetCourseEntry>>,
        val nextCourse: WidgetCourseEntry? = null,
        val nextCourseDay: String? = null,
    ) : WidgetState()

    /** 尚未缓存数据，需要先打开 App */
    data object NoCache : WidgetState()

    /** App 未登录 */
    data object NotLoggedIn : WidgetState()

    data object Error : WidgetState()
}

data class WeekDayInfo(
    val dayOfWeek: String,
    val dayOfMonth: Int,
    val isToday: Boolean,
    val isPast: Boolean,
    val courseCount: Int = 0,
)

// ── 数据获取 ──

private val json = Json { ignoreUnknownKeys = true }
private val WEEKDAY_NAMES = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val WEEKDAY_SHORT = arrayOf("一", "二", "三", "四", "五", "六", "日")

fun fetchWidgetState(context: Context): WidgetState {
    return try {
        val semJson = WidgetDataStore.loadSemesterJson(context)
        if (semJson == null) {
            android.util.Log.w("WidgetData", "No cached semester data")
            return WidgetState.NoCache
        }
        val semData = json.decodeFromString<WidgetSemesterData>(semJson)
        val coursesJson = WidgetDataStore.loadCoursesJson(context)
        val courses: List<ScheduleCourse> = if (coursesJson != null) {
            try { json.decodeFromString(coursesJson) } catch (_: Exception) { emptyList() }
        } else emptyList()
        android.util.Log.d("WidgetData", "File cache: ${semData.semesters.size} semesters, ${courses.size} courses")
        computeWidgetState(semData, courses)
    } catch (e: Exception) {
        android.util.Log.e("WidgetData", "fetchWidgetState failed", e)
        WidgetState.Error
    }
}

/** 判断课程在第 weekNum 周是否有课 */
private fun ScheduleTimePlace.runsInWeek(weekNum: Int): Boolean {
    if (weekNum < 1 || weekNum > classWeek.length) return false
    // classWeek 是 24 位二进制字符串，0-indexed: index 0 = 第1周
    val idx = weekNum - 1
    return idx < classWeek.length && classWeek[idx] == '1'
}

private fun filterCoursesForDay(
    courses: List<ScheduleCourse>, weekNum: Int, dayOfWeek: Int,
    colorMap: Map<String, Int>,
): List<WidgetCourseEntry> {
    val entries = mutableListOf<WidgetCourseEntry>()
    for (course in courses) {
        for (tp in course.timeAndPlaceList) {
            if (tp.classDay == dayOfWeek && tp.runsInWeek(weekNum)) {
                val end = tp.classSessions + tp.continuingSession - 1
                entries.add(
                    WidgetCourseEntry(
                        courseName = course.courseName,
                        location = listOf(tp.teachingBuildingName, tp.classroomName)
                            .filter { it.isNotEmpty() }.joinToString(" "),
                        teacherName = course.attendClassTeacher,
                        sessionStart = tp.classSessions,
                        sessionEnd = end,
                        timeText = sessionTimeRange(tp.classSessions, end),
                        sessionText = "${tp.classSessions}-${end}节",
                        colorIndex = colorMap[course.courseCode] ?: 0,
                    )
                )
            }
        }
    }
    return entries.sortedBy { it.sessionStart }
}

private fun buildCourseColorMap(courses: List<ScheduleCourse>): Map<String, Int> {
    val map = mutableMapOf<String, Int>()
    var idx = 0
    for (course in courses) {
        if (course.courseCode !in map) {
            map[course.courseCode] = idx % WIDGET_COURSE_COLORS.size
            idx++
        }
    }
    return map
}

private fun computeWidgetState(data: WidgetSemesterData, courses: List<ScheduleCourse>): WidgetState {
    val today = LocalDate.now()
    val semesters = data.semesters.sortedBy { it.startDate }

    for (sem in semesters) {
        val start = LocalDate.parse(sem.startDate, DateTimeFormatter.ISO_LOCAL_DATE)
        val end = start.plusWeeks(sem.weeks.toLong()).minusDays(1)
        val display = sem.displayName()

        if (today.isBefore(start)) {
            return WidgetState.Vacation(
                nextSemesterName = display,
                nextStartDate = sem.startDate,
                daysUntilStart = ChronoUnit.DAYS.between(today, start),
            )
        }

        if (!today.isBefore(start) && !today.isAfter(end)) {
            val weekNum = (ChronoUnit.DAYS.between(start, today) / 7 + 1).toInt()
            val dow = today.dayOfWeek.value
            val remaining = sem.weeks - weekNum
            val hasCourseData = courses.isNotEmpty()
            val colorMap = if (hasCourseData) buildCourseColorMap(courses) else emptyMap()

            val todayCourses = if (hasCourseData) filterCoursesForDay(courses, weekNum, dow, colorMap) else emptyList()
            val weekCourses = if (hasCourseData) {
                (1..7).associateWith { d -> filterCoursesForDay(courses, weekNum, d, colorMap) }
            } else emptyMap()

            val mondayOfWeek = today.with(DayOfWeek.MONDAY)
            val weekDays = (0..6).map { offset ->
                val d = mondayOfWeek.plusDays(offset.toLong())
                val dayCourseCount = weekCourses[offset + 1]?.size ?: 0
                WeekDayInfo(
                    dayOfWeek = WEEKDAY_SHORT[offset],
                    dayOfMonth = d.dayOfMonth,
                    isToday = d == today,
                    isPast = d.isBefore(today),
                    courseCount = dayCourseCount,
                )
            }

            val isEnding = remaining <= 2

            if (hasCourseData && todayCourses.isEmpty()) {
                var nextEntry: WidgetCourseEntry? = null
                var nextDayLabel: String? = null
                for (d in (dow + 1)..7) {
                    val c = filterCoursesForDay(courses, weekNum, d, colorMap)
                    if (c.isNotEmpty()) { nextEntry = c.first(); nextDayLabel = WEEKDAY_NAMES[d - 1]; break }
                }
                if (nextEntry == null && weekNum < sem.weeks) {
                    for (d in 1..7) {
                        val c = filterCoursesForDay(courses, weekNum + 1, d, colorMap)
                        if (c.isNotEmpty()) { nextEntry = c.first(); nextDayLabel = "下${WEEKDAY_NAMES[d - 1]}"; break }
                    }
                }
                return WidgetState.NoCourseToday(
                    semesterName = display,
                    currentWeek = weekNum,
                    totalWeeks = sem.weeks,
                    dayOfWeek = dow,
                    dayOfWeekText = WEEKDAY_NAMES[dow - 1],
                    dateText = "${today.monthValue}月${today.dayOfMonth}日",
                    weekDays = weekDays,
                    isEnding = isEnding,
                    weekCourses = weekCourses,
                    nextCourse = nextEntry,
                    nextCourseDay = nextDayLabel,
                )
            }

            return WidgetState.InSession(
                semesterName = display,
                currentWeek = weekNum,
                totalWeeks = sem.weeks,
                dayOfWeek = dow,
                dayOfWeekText = WEEKDAY_NAMES[dow - 1],
                dateText = "${today.monthValue}月${today.dayOfMonth}日",
                weekDays = weekDays,
                isEnding = isEnding,
                todayCourses = todayCourses,
                weekCourses = weekCourses,
                hasCourseData = hasCourseData,
            )
        }
    }

    return WidgetState.Vacation()
}
