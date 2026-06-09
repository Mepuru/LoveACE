import Foundation

@MainActor @Observable
final class CompetitionViewModel {
    var isLoading = false
    var data: CompetitionFullResponse?
    var error: String?
    private var service: CompetitionService?

    func initialize(service: CompetitionService) { self.service = service }

    func loadCompetitionInfo() {
        guard let svc = service else { return }
        Task {
            isLoading = true; error = nil
            let result = await svc.getCompetitionInfo()
            if result.success { data = result.data } else { error = result.error }
            isLoading = false
        }
    }
}
