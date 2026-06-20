package tech.loveace.appv3.data.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import tech.loveace.appv3.data.model.*
import tech.loveace.appv3.data.network.AUFEConnection

/**
 * 学生课表服务 - 获取个人课表
 */
class StudentScheduleService(private val connection: AUFEConnection) {

    private var cachedDynamicPath: String? = null

    private val scheduleMutex = Mutex()
    private var cacheTermCode: String? = null
    private var cacheResult: UniResponse<StudentScheduleResponse>? = null
    private var cacheTimestamp: Long = 0

    suspend fun getStudentSchedule(termCode: String): UniResponse<StudentScheduleResponse> =
        scheduleMutex.withLock {
            val now = System.currentTimeMillis()
            val cached = cacheResult
            if (termCode == cacheTermCode && cached?.success == true &&
                now - cacheTimestamp < CACHE_TTL_MS) {
                Log.d(TAG, "Cache hit for schedule: $termCode")
                return@withLock cached
            }

            val result = withContext(Dispatchers.IO) {
                try {
                    val resultData = fetchScheduleWithRetry(termCode)
                    UniResponse.success(resultData)
                } catch (e: Exception) {
                    cachedDynamicPath = null
                    Log.e(TAG, "getStudentSchedule failed", e)
                    UniResponse.failure(e.message ?: "获取课表失败", retryable = true)
                }
            }

            if (result.success) {
                cacheTermCode = termCode
                cacheResult = result
                cacheTimestamp = System.currentTimeMillis()
            }

            result
        }

    private fun fetchScheduleWithRetry(termCode: String): StudentScheduleResponse {
        val json = Json { ignoreUnknownKeys = true }
        if (cachedDynamicPath == null) fetchDynamicPath()
        var dynPath = cachedDynamicPath ?: throw Exception("未能获取动态路径参数")

        for (attempt in 1..MAX_RETRIES) {
            val indexUrl = "$BASE_URL/student/courseSelect/calendarSemesterCurriculum/index"
            val scheduleUrl = "$BASE_URL/student/courseSelect/thisSemesterCurriculum/$dynPath/ajaxStudentSchedule/past/callback"

            val response = connection.client.post(
                scheduleUrl,
                formData = mapOf("planCode" to termCode),
                headers = mapOf(
                    "Referer" to indexUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                ),
            )
            val body = response.body?.string() ?: throw Exception("响应为空")
            val data = json.parseToJsonElement(body).jsonObject

            val errorMsg = data["errorMessage"]?.jsonPrimitive?.contentOrNull ?: ""
            if (errorMsg.isEmpty()) {
                if (attempt > 1) Log.i(TAG, "刷新动态路径后重试成功")
                val allUnits = data["allUnits"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val dateList = data["dateList"]?.jsonArray ?: JsonArray(emptyList())
                val scheduleInfos = dateList.map { parseDateInfo(it.jsonObject, json) }
                return StudentScheduleResponse(
                    allUnits = allUnits,
                    errorMessage = "",
                    dateList = scheduleInfos,
                )
            }

            // 服务器返回错误，可能是动态路径过期，刷新后重试
            if (attempt < MAX_RETRIES) {
                Log.w(TAG, "服务器返回错误: $errorMsg，尝试刷新动态路径后重试")
                cachedDynamicPath = null
                fetchDynamicPath()
                dynPath = cachedDynamicPath ?: throw Exception("未能获取动态路径参数")
            } else {
                throw Exception("服务器返回错误: $errorMsg")
            }
        }
        throw Exception("获取课表失败")
    }

    private fun parseDateInfo(obj: JsonObject, json: Json): ScheduleDateInfo {
        val planCode = obj["programPlanCode"]?.jsonPrimitive?.contentOrNull ?: ""
        val planName = obj["programPlanName"]?.jsonPrimitive?.contentOrNull ?: ""
        val totalUnits = obj["totalUnits"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val courseList = obj["selectCourseList"]?.jsonArray ?: JsonArray(emptyList())

        val courses = courseList.map { parseCourse(it.jsonObject) }
        return ScheduleDateInfo(planCode, planName, totalUnits, courses)
    }

    private fun parseCourse(obj: JsonObject): ScheduleCourse {
        val idObj = obj["id"]?.jsonObject
        val courseId = ScheduleCourseId(
            executiveEducationPlanNumber = idObj?.get("executiveEducationPlanNumber")?.jsonPrimitive?.contentOrNull ?: "",
            coureNumber = idObj?.get("coureNumber")?.jsonPrimitive?.contentOrNull ?: "",
            coureSequenceNumber = idObj?.get("coureSequenceNumber")?.jsonPrimitive?.contentOrNull ?: "",
            studentNumber = idObj?.get("studentNumber")?.jsonPrimitive?.contentOrNull ?: "",
        )
        val timePlaceArr = obj["timeAndPlaceList"]?.let {
            if (it is JsonNull) JsonArray(emptyList()) else it.jsonArray
        } ?: JsonArray(emptyList())
        val timePlaces = timePlaceArr.map { parseTimePlace(it.jsonObject) }

        return ScheduleCourse(
            id = courseId,
            courseName = obj["courseName"]?.jsonPrimitive?.contentOrNull ?: "",
            unit = obj["unit"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            programPlanName = obj["programPlanName"]?.jsonPrimitive?.contentOrNull ?: "",
            attendClassTeacher = obj["attendClassTeacher"]?.jsonPrimitive?.contentOrNull ?: "",
            studyModeName = obj["studyModeName"]?.jsonPrimitive?.contentOrNull ?: "",
            coursePropertiesName = obj["coursePropertiesName"]?.jsonPrimitive?.contentOrNull ?: "",
            examTypeName = obj["examTypeName"]?.jsonPrimitive?.contentOrNull ?: "",
            courseCategoryName = obj["courseCategoryName"]?.jsonPrimitive?.contentOrNull,
            timeAndPlaceList = timePlaces,
            selectCourseStatusName = obj["selectCourseStatusName"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    private fun parseTimePlace(obj: JsonObject): ScheduleTimePlace {
        return ScheduleTimePlace(
            classWeek = obj["classWeek"]?.jsonPrimitive?.contentOrNull ?: "",
            classDay = obj["classDay"]?.jsonPrimitive?.intOrNull ?: 0,
            classSessions = obj["classSessions"]?.jsonPrimitive?.intOrNull ?: 0,
            continuingSession = obj["continuingSession"]?.jsonPrimitive?.intOrNull ?: 0,
            campusName = obj["campusName"]?.jsonPrimitive?.contentOrNull ?: "",
            teachingBuildingName = obj["teachingBuildingName"]?.jsonPrimitive?.contentOrNull ?: "",
            classroomName = obj["classroomName"]?.jsonPrimitive?.contentOrNull ?: "",
            weekDescription = obj["weekDescription"]?.jsonPrimitive?.contentOrNull ?: "",
            coursePropertiesName = obj["coursePropertiesName"]?.jsonPrimitive?.contentOrNull ?: "",
            coureName = obj["coureName"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    private fun fetchDynamicPath() {
        val indexUrl = "$BASE_URL/student/courseSelect/calendarSemesterCurriculum/index"
        val response = connection.client.get(indexUrl)
        val html = response.body?.string() ?: throw Exception("课表页面响应为空")
        val match = Regex("/student/courseSelect/thisSemesterCurriculum/([A-Za-z0-9]+)/ajaxStudentSchedule").find(html)
            ?: throw Exception("未能从页面中提取动态路径参数")
        cachedDynamicPath = match.groupValues[1]
    }

    companion object {
        private const val TAG = "StudentScheduleService"
        private const val CACHE_TTL_MS = 30_000L
        private const val MAX_RETRIES = 2
        const val BASE_URL = "http://jwcxk2-aufe-edu-cn.vpn2.aufe.edu.cn:8118"
    }
}
