import Foundation

@MainActor @Observable
final class RepairViewModel {
    var isLoading = false
    var pending: [RepairOrder] = []
    var completed: [RepairOrder] = []
    var error: String?
    var isDetailLoading = false
    var detail: RepairOrderDetail?
    var detailError: String?
    var isFormLoading = false
    var formData: RepairFormData?
    var formError: String?
    var isSubmitting = false
    var submitSuccess = false
    var submitError: String?
    private var service: RepairService?

    func initialize(service: RepairService) { self.service = service }

    func loadOrders() {
        guard let svc = service else { return }
        Task {
            isLoading = true; error = nil
            let result = await svc.getAllOrders()
            if result.success, let data = result.data {
                pending = data.pending; completed = data.completed
            } else { error = result.error }
            isLoading = false
        }
    }

    func loadDetail(taskId: String) {
        guard let svc = service else { return }
        Task {
            isDetailLoading = true; detail = nil; detailError = nil
            let result = await svc.getOrderDetail(taskId: taskId)
            detail = result.data; detailError = result.error; isDetailLoading = false
        }
    }

    func loadFormData() {
        guard let svc = service else { return }
        Task {
            isFormLoading = true; formError = nil
            let result = await svc.getRepairFormData()
            formData = result.data; formError = result.error; isFormLoading = false
        }
    }

    func submitRepair(request: RepairSubmitRequest) {
        guard let svc = service else { return }
        Task {
            isSubmitting = true; submitSuccess = false; submitError = nil
            let result = await svc.submitRepair(request: request)
            submitSuccess = result.success; submitError = result.error; isSubmitting = false
        }
    }

    func clearDetail() { detail = nil; detailError = nil }
    func clearSubmitState() { submitSuccess = false; submitError = nil }
}
