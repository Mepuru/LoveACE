package tech.loveace.appv3.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.loveace.appv3.data.model.ElectricityInfo
import tech.loveace.appv3.data.service.ISIMService

data class RoomSelection(val code: String, val name: String)

data class ElectricityUiState(
    val isLoading: Boolean = false,
    val buildings: List<Map<String, String>> = emptyList(),
    val floors: List<Map<String, String>> = emptyList(),
    val rooms: List<Map<String, String>> = emptyList(),
    val selectedBuilding: RoomSelection? = null,
    val selectedFloor: RoomSelection? = null,
    val selectedRoom: RoomSelection? = null,
    val electricityInfo: ElectricityInfo? = null,
    val error: String? = null,
    // 已绑定的房间
    val boundRoomCode: String? = null,
    val boundRoomDisplay: String? = null,
)

class ElectricityViewModel(application: Application) : AndroidViewModel(application) {
    private var service: ISIMService? = null
    private val _uiState = MutableStateFlow(ElectricityUiState())
    val uiState: StateFlow<ElectricityUiState> = _uiState.asStateFlow()

    private val prefs = application.getSharedPreferences("electricity_room", Context.MODE_PRIVATE)

    fun init(service: ISIMService) {
        this.service = service
        // 恢复已绑定的房间
        val code = prefs.getString("room_code", null)
        val display = prefs.getString("room_display", null)
        if (code != null && display != null) {
            _uiState.value = _uiState.value.copy(boundRoomCode = code, boundRoomDisplay = display)
        }
    }

    /** 自动加载：如果已绑定房间，直接查询电费 */
    fun autoLoad() {
        val code = _uiState.value.boundRoomCode ?: return
        val display = _uiState.value.boundRoomDisplay
        loadElectricityInfo(code, display)
    }

    fun loadBuildings() {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = svc.getBuildings()
            _uiState.value = if (result.success)
                _uiState.value.copy(isLoading = false, buildings = result.data ?: emptyList())
            else _uiState.value.copy(isLoading = false, error = result.error)
        }
    }

    fun selectBuilding(code: String, name: String) {
        _uiState.value = _uiState.value.copy(
            selectedBuilding = RoomSelection(code, name),
            selectedFloor = null, selectedRoom = null,
            floors = emptyList(), rooms = emptyList(),
        )
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = svc.getFloors(code)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                floors = if (result.success) result.data ?: emptyList() else emptyList(),
            )
        }
    }

    fun selectFloor(code: String, name: String) {
        _uiState.value = _uiState.value.copy(
            selectedFloor = RoomSelection(code, name),
            selectedRoom = null, rooms = emptyList(),
        )
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = svc.getRooms(code)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                rooms = if (result.success) result.data ?: emptyList() else emptyList(),
            )
        }
    }

    fun selectRoom(code: String, name: String) {
        _uiState.value = _uiState.value.copy(selectedRoom = RoomSelection(code, name))
    }

    /** 确认绑定房间并查询电费 */
    fun confirmBinding() {
        val building = _uiState.value.selectedBuilding ?: return
        val floor = _uiState.value.selectedFloor ?: return
        val room = _uiState.value.selectedRoom ?: return
        val display = "${building.name} ${floor.name} ${room.name}"
        // 保存绑定
        prefs.edit().putString("room_code", room.code).putString("room_display", display).apply()
        _uiState.value = _uiState.value.copy(boundRoomCode = room.code, boundRoomDisplay = display)
        loadElectricityInfo(room.code, display)
    }

    fun loadElectricityInfo(roomCode: String, displayText: String? = null) {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = svc.getElectricityInfo(roomCode, displayText)
            _uiState.value = if (result.success)
                _uiState.value.copy(isLoading = false, electricityInfo = result.data)
            else _uiState.value.copy(isLoading = false, error = result.error)
        }
    }

    /** 清除绑定 */
    fun clearBinding() {
        prefs.edit().clear().apply()
        _uiState.value = ElectricityUiState()
    }
}
