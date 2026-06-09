package tech.loveace.appv3.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.loveace.appv3.data.model.AACCreditCategory
import tech.loveace.appv3.data.model.AACCreditInfo
import tech.loveace.appv3.data.service.AACService

data class AACUiState(
    val isLoading: Boolean = false,
    val creditInfo: AACCreditInfo? = null,
    val categories: List<AACCreditCategory> = emptyList(),
    val error: String? = null,
)

class AACViewModel : ViewModel() {
    private var service: AACService? = null
    private val _uiState = MutableStateFlow(AACUiState())
    val uiState: StateFlow<AACUiState> = _uiState.asStateFlow()

    fun init(service: AACService) { this.service = service }

    fun loadAll() {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = AACUiState(isLoading = true)
            val infoResult = svc.getCreditInfo()
            val listResult = svc.getCreditList()
            _uiState.value = AACUiState(
                creditInfo = infoResult.data,
                categories = listResult.data ?: emptyList(),
                error = infoResult.error ?: listResult.error,
            )
        }
    }
}
