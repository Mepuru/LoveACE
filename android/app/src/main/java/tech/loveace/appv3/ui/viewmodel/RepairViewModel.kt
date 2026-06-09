package tech.loveace.appv3.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.loveace.appv3.data.model.*
import tech.loveace.appv3.data.service.RepairService

data class RepairUiState(
    val isLoading: Boolean = false,
    val pending: List<RepairOrder> = emptyList(),
    val completed: List<RepairOrder> = emptyList(),
    val error: String? = null,
    // 详情
    val isDetailLoading: Boolean = false,
    val detail: RepairOrderDetail? = null,
    val detailError: String? = null,
    // 报修表单
    val isFormLoading: Boolean = false,
    val formData: RepairFormData? = null,
    val formError: String? = null,
    // 图片上传
    val uploadedImageUrls: List<String> = emptyList(),
    val isUploading: Boolean = false,
    val uploadError: String? = null,
    // 提交
    val isSubmitting: Boolean = false,
    val submitSuccess: Boolean = false,
    val submitError: String? = null,
)

class RepairViewModel : ViewModel() {
    private var service: RepairService? = null
    private val _uiState = MutableStateFlow(RepairUiState())
    val uiState: StateFlow<RepairUiState> = _uiState.asStateFlow()

    fun init(service: RepairService) { this.service = service }

    fun loadOrders() {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = svc.getAllOrders()
            _uiState.value = if (result.success && result.data != null) {
                _uiState.value.copy(
                    isLoading = false,
                    pending = result.data.pending,
                    completed = result.data.completed,
                )
            } else {
                _uiState.value.copy(isLoading = false, error = result.error)
            }
        }
    }

    fun loadDetail(taskId: String) {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDetailLoading = true, detail = null, detailError = null)
            val result = svc.getOrderDetail(taskId)
            _uiState.value = if (result.success && result.data != null) {
                _uiState.value.copy(isDetailLoading = false, detail = result.data)
            } else {
                _uiState.value.copy(isDetailLoading = false, detailError = result.error)
            }
        }
    }

    fun loadFormData() {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFormLoading = true, formError = null)
            val result = svc.getRepairFormData()
            _uiState.value = if (result.success && result.data != null) {
                _uiState.value.copy(isFormLoading = false, formData = result.data)
            } else {
                _uiState.value.copy(isFormLoading = false, formError = result.error)
            }
        }
    }

    fun submitRepair(request: RepairSubmitRequest) {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, submitSuccess = false, submitError = null)
            val result = svc.submitRepair(request)
            _uiState.value = if (result.success) {
                _uiState.value.copy(isSubmitting = false, submitSuccess = true)
            } else {
                _uiState.value.copy(isSubmitting = false, submitError = result.error)
            }
        }
    }

    fun clearDetail() {
        _uiState.value = _uiState.value.copy(detail = null, detailError = null)
    }

    fun clearSubmitState() {
        _uiState.value = _uiState.value.copy(submitSuccess = false, submitError = null)
    }

    fun uploadImage(base64Data: String) {
        val svc = service ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, uploadError = null)
            val result = svc.uploadImage(base64Data)
            _uiState.value = if (result.success && result.data != null) {
                _uiState.value.copy(
                    isUploading = false,
                    uploadedImageUrls = _uiState.value.uploadedImageUrls + result.data,
                )
            } else {
                _uiState.value.copy(isUploading = false, uploadError = result.error)
            }
        }
    }

    fun removeImage(url: String) {
        _uiState.value = _uiState.value.copy(
            uploadedImageUrls = _uiState.value.uploadedImageUrls.filter { it != url },
        )
    }

    fun clearImages() {
        _uiState.value = _uiState.value.copy(uploadedImageUrls = emptyList())
    }
}
