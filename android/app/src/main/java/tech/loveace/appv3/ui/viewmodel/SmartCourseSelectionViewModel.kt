package tech.loveace.appv3.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import tech.loveace.appv3.BuildConfig
import tech.loveace.appv3.data.model.PlanCompletionInfo
import tech.loveace.appv3.data.model.PlanOption
import tech.loveace.appv3.data.service.CourseScheduleService
import tech.loveace.appv3.data.service.JWCService
import tech.loveace.appv3.data.service.PlanService
import tech.loveace.appv3.data.service.StudentScheduleService
import tech.loveace.appv3.service.SmartCourseSelectionNotifier
import java.time.Instant

data class SmartCourseSelectionUiState(
    val webUrl: String = SmartCourseSelectionViewModel.SMART_SELECT_WEB_URL,
    val status: String = "等待扫码连接",
    val detail: String = "请先在电脑打开智能选课网页，再扫描网页上的二维码。",
    val isScanning: Boolean = false,
    val isConnected: Boolean = false,
    val isWorking: Boolean = false,
    val error: String? = null,
    val sessionId: String? = null,
)

class SmartCourseSelectionViewModel(application: Application) : AndroidViewModel(application) {
    private val client = OkHttpClient.Builder().build()
    private val json = Json { ignoreUnknownKeys = true }
    private val _uiState = MutableStateFlow(SmartCourseSelectionUiState())
    val uiState: StateFlow<SmartCourseSelectionUiState> = _uiState.asStateFlow()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null

    fun startScanning() {
        _uiState.value = _uiState.value.copy(isScanning = true, error = null)
    }

    fun cancelScanning() {
        _uiState.value = _uiState.value.copy(isScanning = false)
    }

    fun connectAndUpload(
        qrData: String,
        userId: String,
        jwcService: JWCService?,
        studentScheduleService: StudentScheduleService?,
        courseScheduleService: CourseScheduleService?,
        planService: PlanService?,
    ) {
        val pairing = parsePairing(qrData)
        if (pairing == null) {
            setError("二维码无效", "请扫描电脑网页智能选课页面生成的二维码。")
            return
        }
        if (jwcService == null || studentScheduleService == null || courseScheduleService == null || planService == null) {
            setError("服务未就绪", "请确认已登录教务系统后重试。")
            return
        }

        closeSocket()
        _uiState.value = _uiState.value.copy(
            isScanning = false,
            isWorking = true,
            status = "正在连接网页",
            detail = "正在建立智能选课数据通道...",
            error = null,
            sessionId = pairing.sessionId,
        )
        notify("正在连接网页", "会话 ${pairing.sessionId}", true)

        val request = Request.Builder().url(pairing.wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                updateStatus("已连接网页", "正在准备上传教务数据...", connected = true, working = true)
                sendJson(buildJsonObject {
                    put("type", "hello")
                    put("role", "mobile")
                    put("platform", "android")
                    put("app_version", BuildConfig.VERSION_NAME)
                    put("build", BuildConfig.VERSION_CODE)
                })
                startHeartbeat()
                viewModelScope.launch {
                    uploadDatasets(userId, jwcService, studentScheduleService, courseScheduleService, planService)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                setError("连接失败", t.message ?: "无法连接智能选课网页")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                heartbeatJob?.cancel()
                updateStatus("连接已关闭", reason.ifBlank { "智能选课连接已结束" }, connected = false, working = false)
            }
        })
    }

    private suspend fun uploadDatasets(
        userId: String,
        jwcService: JWCService,
        studentScheduleService: StudentScheduleService,
        courseScheduleService: CourseScheduleService,
        planService: PlanService,
    ) {
        try {
            val terms = step("正在获取学期列表") {
                val response = jwcService.getAllTerms()
                if (!response.success || response.data.isNullOrEmpty()) throw Exception(response.error ?: "获取学期列表失败")
                response.data
            }
            val selectedTerm = terms.firstOrNull { it.isCurrent } ?: terms.first()

            sendJson(buildJsonObject {
                put("type", "upload_start")
                put("schema_version", 1)
                put("term_code", selectedTerm.termCode)
            })
            sendDataset("terms", terms)
            sendDataset("selected_term", selectedTerm)

            val schedule = step("正在获取当前课表") {
                val response = studentScheduleService.getStudentSchedule(selectedTerm.termCode)
                if (!response.success || response.data == null) throw Exception(response.error ?: "获取课表失败")
                response.data
            }
            sendDataset("student_schedule", schedule)

            val planBundle = step("正在获取培养方案") {
                loadPlan(planService)
            }
            sendDataset("plan_options", planBundle.options)
            planBundle.selectedPlanId?.let { sendDataset("selected_plan_id", mapOf("plan_id" to it)) }
            sendDataset("plan_completion", planBundle.plan)

            val courses = step("正在获取当前学期开课数据") {
                val response = courseScheduleService.queryAllCoursesForTerm(selectedTerm.termCode) { completed, total, records ->
                    updateStatus("正在获取当前学期开课数据", "已获取 $records 条记录（第 $completed/$total 页）", connected = true, working = true)
                }
                if (!response.success || response.data == null) throw Exception(response.error ?: "获取开课数据失败")
                response.data
            }
            sendDataset("available_courses", courses)

            sendJson(buildJsonObject {
                put("type", "upload_done")
                put("uploaded_at", Instant.now().toString())
            })
            updateStatus("上传完成", "已上传 ${courses.size} 条开课记录，可回到电脑网页开始智能选课。", connected = true, working = false)
            notify("智能选课数据已上传", "请回到电脑网页继续选课", false)
        } catch (e: Exception) {
            sendJson(buildJsonObject {
                put("type", "error")
                put("message", e.message ?: "上传失败")
            })
            setError("上传失败", e.message ?: "获取或上传教务数据失败")
        }
    }

    private suspend fun <T> step(label: String, block: suspend () -> T): T {
        updateStatus(label, "请保持应用在前台，上传期间不要退出。", connected = true, working = true)
        notify(label, "请保持应用在前台", true)
        return withContext(Dispatchers.IO) { block() }
    }

    private suspend fun loadPlan(planService: PlanService): PlanBundle {
        val direct = planService.getPlanCompletion(null)
        if (direct.success && direct.data != null) return PlanBundle(direct.data, emptyList(), null)
        if (direct.error != "MULTI_PLAN") throw Exception(direct.error ?: "获取培养方案失败")

        val options = planService.cachedOptions.ifEmpty {
            val optionResponse = planService.getPlanOptions()
            if (!optionResponse.success) throw Exception(optionResponse.error ?: "获取培养方案选项失败")
            optionResponse.data?.options.orEmpty()
        }
        val selected = options.firstOrNull { it.isCurrent } ?: options.firstOrNull() ?: throw Exception("没有可用培养方案")
        val selectedResponse = planService.getPlanCompletion(selected.planId)
        if (!selectedResponse.success || selectedResponse.data == null) {
            throw Exception(selectedResponse.error ?: "获取培养方案失败")
        }
        return PlanBundle(selectedResponse.data, options, selected.planId)
    }

    private inline fun <reified T> sendDataset(dataset: String, payload: T) {
        sendRaw(json.encodeToString(buildJsonObject {
            put("type", "upload_dataset")
            put("dataset", dataset)
            put("payload", json.parseToJsonElement(json.encodeToString(payload)))
        }))
    }

    private fun sendJson(obj: JsonObject) = sendRaw(obj.toString())

    private fun sendRaw(text: String) {
        webSocket?.send(text)
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (true) {
                delay(15_000)
                sendJson(buildJsonObject {
                    put("type", "heartbeat")
                    put("time", Instant.now().toString())
                })
            }
        }
    }

    fun closeSocket() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket?.close(1000, "closed_by_user")
        webSocket = null
        SmartCourseSelectionNotifier.clear(getApplication())
        _uiState.value = _uiState.value.copy(isConnected = false, isWorking = false)
    }

    private fun updateStatus(status: String, detail: String, connected: Boolean, working: Boolean) {
        _uiState.value = _uiState.value.copy(status = status, detail = detail, isConnected = connected, isWorking = working, error = null)
    }

    private fun setError(status: String, detail: String) {
        heartbeatJob?.cancel()
        _uiState.value = _uiState.value.copy(status = status, detail = detail, isScanning = false, isConnected = false, isWorking = false, error = detail)
        notify(status, detail, false)
    }

    private fun notify(title: String, text: String, working: Boolean) {
        SmartCourseSelectionNotifier.show(getApplication(), title, text, working)
    }

    private fun parsePairing(qrData: String): Pairing? {
        return try {
            val uri = Uri.parse(qrData)
            val sessionId = uri.getQueryParameter("session_id") ?: return null
            val token = uri.getQueryParameter("token") ?: uri.getQueryParameter("pairing_token") ?: return null
            Pairing(sessionId, token)
        } catch (_: Exception) {
            null
        }
    }

    override fun onCleared() {
        closeSocket()
        client.dispatcher.executorService.shutdown()
        super.onCleared()
    }

    private data class Pairing(val sessionId: String, val token: String) {
        val wsUrl = "$SMART_SELECT_WS_BASE?session_id=$sessionId&token=$token"
    }

    private data class PlanBundle(
        val plan: PlanCompletionInfo,
        val options: List<PlanOption>,
        val selectedPlanId: String?,
    )

    companion object {
        const val SMART_SELECT_WEB_URL = "https://analyst-api.linota.cn/smart-select"
        private const val SMART_SELECT_WS_BASE = "wss://analyst-api.loveace.top/v1/smart-select/ws/mobile"
    }
}
