package tech.loveace.appv3.ui.viewmodel

import android.app.Application
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tech.loveace.appv3.data.local.ScheduleStore
import tech.loveace.appv3.data.model.ScheduleCourse
import tech.loveace.appv3.data.model.TermItem
import tech.loveace.appv3.data.service.JWCService
import tech.loveace.appv3.data.service.StudentScheduleService
import tech.loveace.appv3.widget.SemesterDayWidget
import tech.loveace.appv3.widget.SemesterWeekWidget
import tech.loveace.appv3.widget.WidgetDataStore

data class ScheduleUiState(
    val isLoading: Boolean = false,
    val terms: List<TermItem> = emptyList(),
    val selectedTerm: TermItem? = null,
    val courses: List<ScheduleCourse> = emptyList(),
    val totalUnits: Double = 0.0,
    val error: String? = null,
)

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {
    private var jwcService: JWCService? = null
    private var scheduleService: StudentScheduleService? = null
    private val scheduleStore = ScheduleStore(application)
    private val json = Json { ignoreUnknownKeys = true }
    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    fun init(jwcService: JWCService, scheduleService: StudentScheduleService) {
        this.jwcService = jwcService
        this.scheduleService = scheduleService
    }

    /** 设置当前用户 ID，切换用户隔离存储 */
    fun setActiveUserId(userId: String) {
        scheduleStore.activeUserId = userId
    }

    fun loadTerms() {
        val svc = jwcService ?: return
        viewModelScope.launch {
            val result = svc.getAllTerms()
            if (result.success && result.data != null) {
                val terms = result.data
                val current = terms.firstOrNull { it.isCurrent } ?: terms.firstOrNull()
                _uiState.value = _uiState.value.copy(terms = terms, selectedTerm = current)
                current?.let { loadSchedule(it.termCode) }
            }
        }
    }

    fun selectTerm(term: TermItem) {
        _uiState.value = _uiState.value.copy(selectedTerm = term)
        loadSchedule(term.termCode)
    }

    fun loadSchedule(termCode: String) {
        val svc = scheduleService ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = svc.getStudentSchedule(termCode)
            if (result.success && result.data != null) {
                val courses = result.data.courses
                val shouldSyncWidget = _uiState.value.terms
                    .firstOrNull { it.termCode == termCode }
                    ?.isCurrent == true
                scheduleStore.saveCourses(courses)
                if (shouldSyncWidget) {
                    WidgetDataStore.saveCourses(getApplication(), json.encodeToString(courses))
                    // 请求刷新 widget
                    SemesterDayWidget().updateAll(getApplication())
                    SemesterWeekWidget().updateAll(getApplication())
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    courses = courses,
                    totalUnits = result.data.allUnits,
                )
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false, error = result.error)
            }
        }
    }
}
