import Foundation

@MainActor @Observable
final class YKTViewModel {
    var isLoading = false
    var balance: CardBalance?
    var transactions: [TransactionRecord] = []
    var isTransactionsLoading = false
    var error: String?
    var isPaymentUnlocked = false
    var studentInfo: StudentInfo?
    var dorms: [SelectOption] = []
    var buildings: [SelectOption] = []
    var floors: [SelectOption] = []
    var rooms: [SelectOption] = []
    var selectedDorm: SelectOption?
    var selectedBuilding: SelectOption?
    var selectedFloor: SelectOption?
    var selectedRoom: SelectOption?
    var loadingOptions = false
    var isPaying = false
    var paymentResult: UtilityPaymentResult?
    var purchaseHistory: ElectricPurchaseQueryResult?
    private var service: YKTService?

    func initialize(service: YKTService) { self.service = service }

    func loadAll() {
        guard let svc = service else { return }
        Task {
            isLoading = true; error = nil
            await svc.initSession()
            let result = await svc.getBalance()
            balance = result.data; error = result.error; isLoading = false
            if result.data != nil { loadTransactions() }
        }
    }

    private func loadTransactions() {
        guard let svc = service else { return }
        Task {
            isTransactionsLoading = true
            let fmt = DateFormatter(); fmt.dateFormat = "yyyy-MM-dd"
            let end = fmt.string(from: Date())
            let start = fmt.string(from: Calendar.current.date(byAdding: .day, value: -30, to: Date())!)
            let result = await svc.getTransactions(startDate: start, endDate: end)
            transactions = result.data ?? []; isTransactionsLoading = false
        }
    }

    func unlockPayment() { isPaymentUnlocked = true; loadStudentInfo() }
    func lockPayment() {
        isPaymentUnlocked = false; studentInfo = nil
        dorms = []; buildings = []; floors = []; rooms = []
        selectedDorm = nil; selectedBuilding = nil; selectedFloor = nil; selectedRoom = nil
        purchaseHistory = nil
    }

    private func loadStudentInfo() {
        guard let svc = service else { return }
        Task {
            let result = await svc.getPageInfo()
            if result.success { studentInfo = result.data; loadDorms() }
        }
    }

    private func loadDorms() {
        guard let svc = service else { return }
        Task { loadingOptions = true; dorms = (await svc.getDormList()).data ?? []; loadingOptions = false }
    }

    func selectDorm(_ option: SelectOption) {
        guard let svc = service else { return }
        selectedDorm = option; selectedBuilding = nil; selectedFloor = nil; selectedRoom = nil
        buildings = []; floors = []; rooms = []
        Task { loadingOptions = true; buildings = (await svc.getBuildingList(dormId: option.value, dormName: option.name)).data ?? []; loadingOptions = false }
    }

    func selectBuilding(_ option: SelectOption) {
        guard let svc = service, let dorm = selectedDorm else { return }
        selectedBuilding = option; selectedFloor = nil; selectedRoom = nil; floors = []; rooms = []
        Task { loadingOptions = true; floors = (await svc.getFloorList(dormId: dorm.value, buildingId: option.value, dormName: dorm.name)).data ?? []; loadingOptions = false }
    }

    func selectFloor(_ option: SelectOption) {
        guard let svc = service, let dorm = selectedDorm, let building = selectedBuilding else { return }
        selectedFloor = option; selectedRoom = nil; rooms = []
        Task { loadingOptions = true; rooms = (await svc.getRoomList(dormId: dorm.value, buildingId: building.value, floorId: option.value, dormName: dorm.name)).data ?? []; loadingOptions = false }
    }

    func selectRoom(_ option: SelectOption) { selectedRoom = option }

    func payElectricity(amount: Int) {
        guard let svc = service, let info = studentInfo, let dorm = selectedDorm,
              let building = selectedBuilding, let floor = selectedFloor, let room = selectedRoom else { return }
        Task {
            isPaying = true; paymentResult = nil
            let request = UtilityPaymentRequest(
                roomId: room.value, dormId: dorm.value, dormName: dorm.name,
                buildName: building.name, floorName: floor.name, roomName: room.name,
                accId: info.accId, balances: String(format: "%.2f", info.balance), money: amount)
            let result = await svc.payElectricity(request: request)
            paymentResult = result.data ?? UtilityPaymentResult(success: false, message: result.error ?? "充值失败")
            isPaying = false
            if result.data?.success == true { loadAll() }
        }
    }

    func clearPaymentResult() { paymentResult = nil }
}
