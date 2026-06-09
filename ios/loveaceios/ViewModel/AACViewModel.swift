import Foundation

@MainActor @Observable
final class AACViewModel {
    var isLoading = false
    var creditInfo: AACCreditInfo?
    var categories: [AACCreditCategory] = []
    var error: String?
    private var service: AACService?

    func initialize(service: AACService) { self.service = service }

    func loadAll() {
        guard let svc = service else { return }
        Task {
            isLoading = true; error = nil
            let infoResult = await svc.getCreditInfo()
            let listResult = await svc.getCreditList()
            creditInfo = infoResult.data
            categories = listResult.data ?? []
            error = infoResult.error ?? listResult.error
            isLoading = false
        }
    }
}
