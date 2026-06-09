package tech.loveace.appv3.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.loveace.appv3.data.model.*
import tech.loveace.appv3.data.service.YKTService
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class YKTUiState(
    val isLoading: Boolean = false,
    val balance: CardBalance? = null,
    val transactions: List<TransactionRecord> = emptyList(),
    val isTransactionsLoading: Boolean = false,
    val transactionsError: String? = null,
    val error: String? = null,
    // 充值解锁
    val isPaymentUnlocked: Boolean = false,
    // 电费充值
    val studentInfo: StudentInfo? = null,
    val dorms: List<SelectOption> = emptyList(),
    val buildings: List<SelectOption> = emptyList(),
    val floors: List<SelectOption> = emptyList(),
    val rooms: List<SelectOption> = emptyList(),
    val selectedDorm: SelectOption? = null,
    val selectedBuilding: SelectOption? = null,
    val selectedFloor: SelectOption? = null,
    val selectedRoom: SelectOption? = null,
    val loadingOptions: Boolean = false,
    val isPaying: Boolean = false,
    val paymentResult: UtilityPaymentResult? = null,
    val purchaseHistory: ElectricPurchaseQueryResult? = null,
)

class YKTViewModel : ViewModel() {
    private var service: YKTService? = null
    private val _uiState = MutableStateFlow(YKTUiState())
    val uiState: StateFlow<YKTUiState> = _uiState.asStateFlow()

    fun init(service: YKTService) { this.service = service }

    fun loadAll() {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            svc.initSession()
            // 先加载余额
            val balanceResult = svc.getBalance()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                balance = balanceResult.data,
                error = balanceResult.error,
            )
            // 余额加载成功后，异步加载消费记录（不阻塞UI）
            if (balanceResult.data != null) {
                loadTransactions()
            }
        }
    }

    private fun loadTransactions() {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTransactionsLoading = true, transactionsError = null)
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val end = LocalDate.now().format(fmt)
            val start = LocalDate.now().minusDays(30).format(fmt)
            val txResult = svc.getTransactions(start, end)
            _uiState.value = _uiState.value.copy(
                isTransactionsLoading = false,
                transactions = txResult.data ?: emptyList(),
                transactionsError = txResult.error,
            )
        }
    }

    // ── 解锁/锁定 ──

    fun unlockPayment() {
        _uiState.value = _uiState.value.copy(isPaymentUnlocked = true)
        loadStudentInfo()
    }

    fun lockPayment() {
        _uiState.value = _uiState.value.copy(
            isPaymentUnlocked = false, studentInfo = null,
            dorms = emptyList(), buildings = emptyList(), floors = emptyList(), rooms = emptyList(),
            selectedDorm = null, selectedBuilding = null, selectedFloor = null, selectedRoom = null,
            purchaseHistory = null,
        )
    }

    private fun loadStudentInfo() {
        val svc = service ?: return
        viewModelScope.launch {
            val result = svc.getPageInfo()
            if (result.success && result.data != null) {
                _uiState.value = _uiState.value.copy(studentInfo = result.data)
                loadDorms()
            }
        }
    }

    private fun loadDorms() {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingOptions = true)
            val result = svc.getDormList()
            _uiState.value = _uiState.value.copy(dorms = result.data ?: emptyList(), loadingOptions = false)
        }
    }

    fun selectDorm(option: SelectOption) {
        val svc = service ?: return
        _uiState.value = _uiState.value.copy(
            selectedDorm = option, selectedBuilding = null, selectedFloor = null, selectedRoom = null,
            buildings = emptyList(), floors = emptyList(), rooms = emptyList(), loadingOptions = true,
        )
        viewModelScope.launch {
            val result = svc.getBuildingList(option.value, option.name)
            _uiState.value = _uiState.value.copy(buildings = result.data ?: emptyList(), loadingOptions = false)
        }
    }

    fun selectBuilding(option: SelectOption) {
        val svc = service ?: return
        val dorm = _uiState.value.selectedDorm ?: return
        _uiState.value = _uiState.value.copy(
            selectedBuilding = option, selectedFloor = null, selectedRoom = null,
            floors = emptyList(), rooms = emptyList(), loadingOptions = true,
        )
        viewModelScope.launch {
            val result = svc.getFloorList(dorm.value, option.value, dorm.name)
            _uiState.value = _uiState.value.copy(floors = result.data ?: emptyList(), loadingOptions = false)
        }
    }

    fun selectFloor(option: SelectOption) {
        val svc = service ?: return
        val dorm = _uiState.value.selectedDorm ?: return
        val building = _uiState.value.selectedBuilding ?: return
        _uiState.value = _uiState.value.copy(
            selectedFloor = option, selectedRoom = null, rooms = emptyList(), loadingOptions = true,
        )
        viewModelScope.launch {
            val result = svc.getRoomList(dorm.value, building.value, option.value, dorm.name)
            _uiState.value = _uiState.value.copy(rooms = result.data ?: emptyList(), loadingOptions = false)
        }
    }

    fun selectRoom(option: SelectOption) {
        _uiState.value = _uiState.value.copy(selectedRoom = option)
    }

    fun payElectricity(amount: Int) {
        val svc = service ?: return
        val state = _uiState.value
        val info = state.studentInfo ?: return
        val dorm = state.selectedDorm ?: return
        val building = state.selectedBuilding ?: return
        val floor = state.selectedFloor ?: return
        val room = state.selectedRoom ?: return

        _uiState.value = state.copy(isPaying = true, paymentResult = null)
        viewModelScope.launch {
            val request = UtilityPaymentRequest(
                roomId = room.value, dormId = dorm.value, dormName = dorm.name,
                buildName = building.name, floorName = floor.name, roomName = room.name,
                accId = info.accId, balances = "%.2f".format(info.balance), money = amount,
            )
            val result = svc.payElectricity(request)
            _uiState.value = _uiState.value.copy(
                isPaying = false,
                paymentResult = result.data ?: UtilityPaymentResult(false, result.error ?: "充值失败"),
            )
            if (result.data?.success == true) loadAll()
        }
    }

    fun clearPaymentResult() {
        _uiState.value = _uiState.value.copy(paymentResult = null)
    }

    fun loadPurchaseHistory() {
        val svc = service ?: return
        viewModelScope.launch {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val end = LocalDate.now().format(fmt)
            val start = LocalDate.now().minusDays(30).format(fmt)
            val result = svc.getPurchaseHistory(start, end)
            _uiState.value = _uiState.value.copy(purchaseHistory = result.data)
        }
    }
}
