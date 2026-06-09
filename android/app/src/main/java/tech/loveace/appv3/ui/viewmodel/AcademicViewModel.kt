package tech.loveace.appv3.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.loveace.appv3.data.model.*
import tech.loveace.appv3.data.service.JWCService

data class AcademicUiState(
    val isLoading: Boolean = false,
    val academicInfo: AcademicInfo? = null,
    val terms: List<TermItem> = emptyList(),
    val selectedTerm: TermItem? = null,
    val scores: TermScoreResponse? = null,
    val scoresLoading: Boolean = false,
    val error: String? = null,
)

class AcademicViewModel : ViewModel() {
    private var service: JWCService? = null

    private val _uiState = MutableStateFlow(AcademicUiState())
    val uiState: StateFlow<AcademicUiState> = _uiState.asStateFlow()

    fun init(service: JWCService) {
        this.service = service
    }

    fun loadAcademicInfo() {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = svc.getAcademicInfo()
            _uiState.value = if (result.success) {
                _uiState.value.copy(isLoading = false, academicInfo = result.data)
            } else {
                _uiState.value.copy(isLoading = false, error = result.error)
            }
        }
    }

    fun loadTerms() {
        val svc = service ?: return
        viewModelScope.launch {
            val result = svc.getAllTerms()
            if (result.success && result.data != null) {
                val terms = result.data
                val current = terms.firstOrNull { it.isCurrent } ?: terms.firstOrNull()
                _uiState.value = _uiState.value.copy(terms = terms, selectedTerm = current)
                current?.let { loadScores(it.termCode) }
            }
        }
    }

    fun selectTerm(term: TermItem) {
        _uiState.value = _uiState.value.copy(selectedTerm = term)
        loadScores(term.termCode)
    }

    fun loadScores(termCode: String) {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(scoresLoading = true)
            val result = svc.getTermScore(termCode)
            _uiState.value = if (result.success) {
                _uiState.value.copy(scoresLoading = false, scores = result.data)
            } else {
                _uiState.value.copy(scoresLoading = false, error = result.error)
            }
        }
    }

    companion object {
        private const val TAG = "AcademicViewModel"
    }
}
