import Foundation

@MainActor @Observable
final class PlanViewModel {
    var isLoading = false
    var planInfo: PlanCompletionInfo?
    var planOptions: [PlanOption] = []
    var allPlans: [String: PlanCompletionInfo] = [:]
    var selectedTabIndex = 0
    var error: String?
    private var service: PlanService?

    func initialize(service: PlanService) { self.service = service }

    func loadPlan() {
        guard let svc = service else { return }
        Task {
            isLoading = true; error = nil
            let result = await svc.getPlanCompletion()
            if result.success, let data = result.data {
                planInfo = data; isLoading = false
            } else if result.error == "MULTI_PLAN" {
                let options = await svc.cachedOptions
                guard !options.isEmpty else { isLoading = false; error = "获取培养方案选项失败"; return }
                planOptions = options
                let firstOpt = options[0]
                try? await Task.sleep(for: .milliseconds(500))
                let firstResult = await svc.getPlanCompletion(planId: firstOpt.planId)
                if firstResult.success, let data = firstResult.data {
                    planInfo = data; allPlans[firstOpt.planId] = data; selectedTabIndex = 0
                } else { error = firstResult.error }
                isLoading = false
            } else { error = result.error; isLoading = false }
        }
    }

    func selectTab(_ index: Int) {
        guard planOptions.count > 1 else { return }
        let safeIndex = min(max(index, 0), planOptions.count - 1)
        let planId = planOptions[safeIndex].planId
        if let cached = allPlans[planId] { selectedTabIndex = safeIndex; planInfo = cached; return }
        guard let svc = service else { return }
        selectedTabIndex = safeIndex; isLoading = true
        Task {
            try? await Task.sleep(for: .milliseconds(500))
            let result = await svc.getPlanCompletion(planId: planId)
            if result.success, let data = result.data { planInfo = data; allPlans[planId] = data }
            else { error = result.error }
            isLoading = false
        }
    }
}
