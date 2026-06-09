package tech.loveace.appv3.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.loveace.appv3.data.model.CompetitionFullResponse
import tech.loveace.appv3.data.service.CompetitionService

data class CompetitionUiState(
    val isLoading: Boolean = false,
    val data: CompetitionFullResponse? = null,
    val error: String? = null,
)

class CompetitionViewModel : ViewModel() {
    private var service: CompetitionService? = null
    private val _uiState = MutableStateFlow(CompetitionUiState())
    val uiState: StateFlow<CompetitionUiState> = _uiState.asStateFlow()

    fun init(service: CompetitionService) { this.service = service }

    fun loadCompetitionInfo() {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = CompetitionUiState(isLoading = true)
            val result = svc.getCompetitionInfo()
            _uiState.value = if (result.success) CompetitionUiState(data = result.data)
            else CompetitionUiState(error = result.error)
        }
    }
}
