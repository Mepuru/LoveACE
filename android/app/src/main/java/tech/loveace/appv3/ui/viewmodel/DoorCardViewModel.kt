package tech.loveace.appv3.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import tech.loveace.appv3.data.ble.BleCommandHelper
import tech.loveace.appv3.data.ble.DoorLockBleManager
import tech.loveace.appv3.data.local.DoorCardStore
import tech.loveace.appv3.data.model.*
import tech.loveace.appv3.data.service.DoorCardService

data class DoorCardUiState(
    // 绑定状态
    val isBound: Boolean = false,
    val isBinding: Boolean = false,
    val bindError: String? = null,
    // 用户信息
    val userInfo: DoorCardUserInfo? = null,
    // 房间列表
    val isLoadingRooms: Boolean = false,
    val rooms: List<DoorCardRoom> = emptyList(),
    val roomsError: String? = null,
    // 当前选中房间
    val selectedRoom: DoorCardRoom? = null,
    // BLE 状态
    val bleState: BleConnectionState = BleConnectionState.Disconnected,
    // 操作状态
    val operationMessage: String? = null,
    val isOperating: Boolean = false,
)

class DoorCardViewModel(application: Application) : AndroidViewModel(application) {

    private val store = DoorCardStore(application)
    private val service = DoorCardService()
    private val bleManager = DoorLockBleManager(application)

    private val _uiState = MutableStateFlow(DoorCardUiState())
    val uiState: StateFlow<DoorCardUiState> = _uiState.asStateFlow()

    private var appUserId: String = ""

    init {
        // 监听 BLE 连接状态
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                _uiState.value = _uiState.value.copy(bleState = state)
            }
        }
        // 监听 BLE 响应
        viewModelScope.launch {
            bleManager.lastResponse.filterNotNull().collect { hex ->
                handleBleResponse(hex)
            }
        }
    }

    /** 初始化：传入当前登录的学号 */
    fun init(userId: String) {
        appUserId = userId
        val creds = store.loadCredentials(userId)
        val userInfo = store.loadUserInfo(userId)
        _uiState.value = _uiState.value.copy(
            isBound = creds != null,
            userInfo = userInfo,
        )
        if (creds != null && userInfo != null) {
            loadRooms(userInfo.personId)
        }
    }

    // ==================== 绑定 ====================

    fun bind(userno: String, username: String, rawPassword: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBinding = true, bindError = null)
            val result = service.login(userno, username, rawPassword)
            if (result.success && result.data != null) {
                val creds = DoorCardCredentials(
                    username = username,
                    userno = userno,
                    password = rawPassword, // 存原始密码，service 内部做 MD5
                )
                store.saveCredentials(appUserId, creds)
                store.saveUserInfo(appUserId, result.data)
                _uiState.value = _uiState.value.copy(
                    isBinding = false,
                    isBound = true,
                    userInfo = result.data,
                )
                loadRooms(result.data.personId)
            } else {
                _uiState.value = _uiState.value.copy(
                    isBinding = false,
                    bindError = result.error ?: "绑定失败",
                )
            }
        }
    }

    fun unbind() {
        disconnectBle()
        store.unbind(appUserId)
        _uiState.value = DoorCardUiState()
    }

    // ==================== 房间列表 ====================

    fun loadRooms(personId: String? = null) {
        val pid = personId ?: _uiState.value.userInfo?.personId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingRooms = true, roomsError = null)
            val result = service.getRoomList(pid)
            if (result.success && result.data != null) {
                val firstRoom = result.data.firstOrNull()
                _uiState.value = _uiState.value.copy(
                    isLoadingRooms = false,
                    rooms = result.data,
                    selectedRoom = firstRoom, // 自动选中第一个房间
                )
            } else {
                _uiState.value = _uiState.value.copy(isLoadingRooms = false, roomsError = result.error)
            }
        }
    }

    // ==================== 房间选择 & BLE ====================

    fun selectRoom(room: DoorCardRoom) {
        disconnectBle()
        _uiState.value = _uiState.value.copy(
            selectedRoom = room,
            operationMessage = null,
        )
    }

    fun connectBle() {
        val room = _uiState.value.selectedRoom ?: _uiState.value.rooms.firstOrNull() ?: return
        if (_uiState.value.selectedRoom == null) {
            _uiState.value = _uiState.value.copy(selectedRoom = room)
        }
        bleManager.connect(room.btMac)
    }

    fun disconnectBle() {
        bleManager.disconnect()
        _uiState.value = _uiState.value.copy(
            selectedRoom = _uiState.value.selectedRoom,
            operationMessage = null,
        )
    }

    // ==================== 门锁操作 ====================

    fun openDoor() {
        val room = _uiState.value.selectedRoom ?: return
        _uiState.value = _uiState.value.copy(isOperating = true, operationMessage = "开锁中...")
        val cmd = BleCommandHelper.buildOpenDoorCommand(room.sKey, room.schoolId)
        if (!bleManager.sendCommand(cmd)) {
            _uiState.value = _uiState.value.copy(isOperating = false, operationMessage = "发送指令失败")
            return
        }
        // 上报日志
        viewModelScope.launch {
            val userInfo = _uiState.value.userInfo ?: return@launch
            service.reportOperationLog(userInfo.cardId, room.roomId, room.personId, room.schoolId, 9, "手机开锁")
        }
    }

    fun addCard() {
        val room = _uiState.value.selectedRoom ?: return
        val userInfo = _uiState.value.userInfo ?: return
        _uiState.value = _uiState.value.copy(isOperating = true, operationMessage = "发卡中...")
        val cmd = BleCommandHelper.buildAddCardCommand(userInfo.cardId, room.sn, room.sKey, room.endDateTime)
        if (!bleManager.sendCommand(cmd)) {
            _uiState.value = _uiState.value.copy(isOperating = false, operationMessage = "发送指令失败")
        }
    }

    fun freezeCard() {
        val room = _uiState.value.selectedRoom ?: return
        _uiState.value = _uiState.value.copy(isOperating = true, operationMessage = "冻结中...")
        val cmd = BleCommandHelper.buildFreezeCardCommand(room.sn, room.sKey, room.endDateTime)
        if (!bleManager.sendCommand(cmd)) {
            _uiState.value = _uiState.value.copy(isOperating = false, operationMessage = "发送指令失败")
        }
    }

    fun checkTime() {
        _uiState.value = _uiState.value.copy(isOperating = true, operationMessage = "校时中...")
        val cmd = BleCommandHelper.buildCheckTimeCommand()
        if (!bleManager.sendCommand(cmd)) {
            _uiState.value = _uiState.value.copy(isOperating = false, operationMessage = "发送指令失败")
        }
    }

    fun alwaysOpen() {
        val room = _uiState.value.selectedRoom ?: return
        _uiState.value = _uiState.value.copy(isOperating = true, operationMessage = "设置常开...")
        val cmd = BleCommandHelper.buildAlwaysOpenCommand(room.sKey, room.schoolId)
        if (!bleManager.sendCommand(cmd)) {
            _uiState.value = _uiState.value.copy(isOperating = false, operationMessage = "发送指令失败")
        }
    }

    fun alwaysOff() {
        val room = _uiState.value.selectedRoom ?: return
        _uiState.value = _uiState.value.copy(isOperating = true, operationMessage = "设置常闭...")
        val cmd = BleCommandHelper.buildAlwaysOffCommand(room.sKey, room.schoolId)
        if (!bleManager.sendCommand(cmd)) {
            _uiState.value = _uiState.value.copy(isOperating = false, operationMessage = "发送指令失败")
        }
    }

    fun checkDaily() {
        val room = _uiState.value.selectedRoom ?: return
        val userInfo = _uiState.value.userInfo ?: return
        _uiState.value = _uiState.value.copy(isOperating = true, operationMessage = "提交考勤...")
        viewModelScope.launch {
            val success = service.reportOperationLog(
                userInfo.cardId, room.roomId, room.personId, room.schoolId, 8, "考勤巡更"
            )
            _uiState.value = _uiState.value.copy(
                isOperating = false,
                operationMessage = if (success) "考勤提交成功" else "考勤提交失败",
            )
        }
    }

    // ==================== BLE 响应处理 ====================

    private fun handleBleResponse(hex: String) {
        Log.d(TAG, "BLE response: $hex")
        val parsed = BleCommandHelper.parseOpenDoorResponse(hex)
        if (parsed != null) {
            val (recordNum, power) = parsed
            if (recordNum == 0) {
                _uiState.value = _uiState.value.copy(isOperating = false, operationMessage = "开锁成功")
            } else {
                _uiState.value = _uiState.value.copy(isOperating = false, operationMessage = "开锁成功（有 $recordNum 条记录）")
            }
            // 更新电量
            val room = _uiState.value.selectedRoom
            if (room != null) {
                viewModelScope.launch { service.updatePower(room.roomId, power) }
            }
            return
        }
        // 其他响应（发卡、校时等成功）
        if (_uiState.value.isOperating) {
            viewModelScope.launch {
                delay(2000)
                val currentMsg = _uiState.value.operationMessage
                if (currentMsg != null && !currentMsg.contains("成功") && !currentMsg.contains("失败")) {
                    _uiState.value = _uiState.value.copy(isOperating = false, operationMessage = "操作完成")
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(operationMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.destroy()
    }

    companion object {
        private const val TAG = "DoorCardViewModel"
    }
}
