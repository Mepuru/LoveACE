package tech.loveace.appv3.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.loveace.appv3.data.model.PlanCompletionInfo
import tech.loveace.appv3.data.model.PlanOption
import tech.loveace.appv3.data.service.PlanService

data class PlanUiState(
    val isLoading: Boolean = false,
    val planInfo: PlanCompletionInfo? = null,
    val planOptions: List<PlanOption> = emptyList(),
    val allPlans: Map<String, PlanCompletionInfo> = emptyMap(),
    val selectedTabIndex: Int = 0,
    val error: String? = null,
)

class PlanViewModel : ViewModel() {
    private var service: PlanService? = null
    private val _uiState = MutableStateFlow(PlanUiState())
    val uiState: StateFlow<PlanUiState> = _uiState.asStateFlow()

    fun init(service: PlanService) { this.service = service }

    fun loadPlan() {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val result = svc.getPlanCompletion(null)

            if (result.success && result.data != null) {
                // 单方案用户
                _uiState.value = _uiState.value.copy(
                    isLoading = false, planInfo = result.data,
                )
            } else if (result.error?.startsWith("MULTI_PLAN") == true) {
                // 多方案：options 已缓存在 Service 里，不需要再请求
                val options = svc.cachedOptions
                if (options.isEmpty()) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "获取培养方案选项失败")
                    return@launch
                }
                _uiState.value = _uiState.value.copy(planOptions = options)

                // 先加载第一个方案显示，其余懒加载
                val firstOpt = options.first()
                delay(500) // rate limit 保护
                val firstResult = svc.getPlanCompletion(firstOpt.planId)
                if (firstResult.success && firstResult.data != null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        planInfo = firstResult.data,
                        allPlans = mapOf(firstOpt.planId to firstResult.data),
                        selectedTabIndex = 0,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = firstResult.error)
                }
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false, error = result.error)
            }
        }
    }

    /** 切换 tab，如果该方案还没加载过则懒加载 */
    fun selectTab(index: Int) {
        val svc = service ?: return
        val state = _uiState.value
        if (state.planOptions.size <= 1) return
        val safeIndex = index.coerceIn(0, state.planOptions.lastIndex)
        val planId = state.planOptions[safeIndex].planId

        // 已缓存，直接切换
        val cached = state.allPlans[planId]
        if (cached != null) {
            _uiState.value = state.copy(selectedTabIndex = safeIndex, planInfo = cached)
            return
        }

        // 懒加载
        _uiState.value = state.copy(selectedTabIndex = safeIndex, isLoading = true)
        viewModelScope.launch {
            delay(500) // rate limit 保护
            val result = svc.getPlanCompletion(planId)
            if (result.success && result.data != null) {
                val updated = _uiState.value
                _uiState.value = updated.copy(
                    isLoading = false,
                    planInfo = result.data,
                    allPlans = updated.allPlans + (planId to result.data),
                )
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false, error = result.error)
            }
        }
    }
}
