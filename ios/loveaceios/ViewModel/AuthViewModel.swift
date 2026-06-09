import Foundation
import os

private let logger = Logger(subsystem: "tech.loveace.loveaceios", category: "AuthViewModel")

enum AuthState { case initial, loading, authenticated, unauthenticated, error }

@MainActor
@Observable
final class AuthViewModel {
    var state: AuthState = .initial
    var errorMessage: String?
    var userId: String = ""

    private let credentialStore = CredentialStore()
    private var heartbeatTask: Task<Void, Never>?
    private var isReconnecting = false

    private(set) var connection: AUFEConnection?
    private(set) var jwcService: JWCService?
    private(set) var yktService: YKTService?
    private(set) var isimService: ISIMService?
    private(set) var aacService: AACService?
    private(set) var laborClubService: LaborClubService?
    private(set) var competitionService: CompetitionService?
    private(set) var studentScheduleService: StudentScheduleService?
    private(set) var courseScheduleService: CourseScheduleService?
    private(set) var planService: PlanService?
    private(set) var repairService: RepairService?
    private(set) var teacherEvaluationService: TeacherEvaluationService?

    var isAuthenticated: Bool { state == .authenticated }

    func login(userId: String, ecPassword: String, password: String) {
        Task {
            state = .loading; errorMessage = nil
            let conn = AUFEConnection(userId: userId, ecPassword: ecPassword, password: password)
            await conn.startClient()

            let ecResult = await conn.ecLogin()
            guard ecResult.success else {
                let msg: String
                if ecResult.failInvalidCredentials { msg = "校园网关账号或密码错误" }
                else if ecResult.failMaybeAttacked { msg = "登录过于频繁，请稍后再试" }
                else if ecResult.failNetworkError { msg = "网络连接失败" }
                else { msg = "校园网关登录失败" }
                state = .error; errorMessage = msg; return
            }

            let uaapResult = await conn.uaapLogin()
            guard uaapResult.success else {
                let msg: String
                if uaapResult.failInvalidCredentials { msg = "教务系统密码错误" }
                else if uaapResult.failNetworkError { msg = "网络连接失败" }
                else { msg = "UAAP 登录失败" }
                state = .error; errorMessage = msg; return
            }

            connection = conn
            initServices(conn)
            startHeartbeat()
            credentialStore.save(UserCredentials(userId: userId, ecPassword: ecPassword, password: password))
            credentialStore.saveRemembered(UserCredentials(userId: userId, ecPassword: ecPassword, password: password))
            self.userId = userId
            state = .authenticated
            logger.info("Login successful: \(userId)")
        }
    }

    func restoreSession() {
        guard let creds = credentialStore.load() ?? credentialStore.loadRemembered() else {
            state = .unauthenticated; return
        }
        Task {
            state = .loading
            let conn = AUFEConnection(userId: creds.userId, ecPassword: creds.ecPassword, password: creds.password)
            await conn.startClient()
            let ec = await conn.ecLogin()
            guard ec.success else { state = .unauthenticated; return }
            let uaap = await conn.uaapLogin()
            guard uaap.success else { state = .unauthenticated; return }
            connection = conn
            initServices(conn)
            startHeartbeat()
            credentialStore.save(creds)
            userId = creds.userId
            state = .authenticated
            logger.info("Session restored: \(creds.userId)")
        }
    }

    func logout() {
        stopHeartbeat()
        clearServices()
        credentialStore.clear()
        state = .unauthenticated
        logger.info("Logged out")
    }

    func verifyPassword(_ input: String) -> Bool {
        guard let creds = credentialStore.load() else { return false }
        return input == creds.password || input == creds.ecPassword
    }

    func getRememberedCredentials() -> UserCredentials? { credentialStore.loadRemembered() }
    func clearSavedCredentials() { credentialStore.clear(); credentialStore.clearRemembered() }

    private func startHeartbeat() {
        stopHeartbeat()
        heartbeatTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(240))
                guard let conn = connection, !Task.isCancelled else { break }
                let alive = await conn.heartbeat()
                if !alive && !isReconnecting { await handleSessionExpired() }
            }
        }
    }

    private func stopHeartbeat() { heartbeatTask?.cancel(); heartbeatTask = nil }

    private func handleSessionExpired() async {
        guard !isReconnecting else { return }
        isReconnecting = true
        defer { isReconnecting = false }
        guard let conn = connection else { return }
        logger.info("Auto-reconnecting...")
        let success = await conn.reconnect()
        if success {
            initServices(conn)
            logger.info("Auto-reconnect succeeded")
        } else {
            stopHeartbeat()
            state = .error; errorMessage = "会话已过期，请重新登录"
        }
    }

    private func initServices(_ conn: AUFEConnection) {
        jwcService = JWCService(connection: conn)
        yktService = YKTService(connection: conn)
        isimService = ISIMService(connection: conn)
        aacService = AACService(connection: conn)
        laborClubService = LaborClubService(connection: conn)
        competitionService = CompetitionService(connection: conn)
        studentScheduleService = StudentScheduleService(connection: conn)
        courseScheduleService = CourseScheduleService(connection: conn)
        planService = PlanService(connection: conn)
        repairService = RepairService(connection: conn)
        teacherEvaluationService = TeacherEvaluationService(connection: conn)
    }

    private func clearServices() {
        connection = nil; jwcService = nil; yktService = nil; isimService = nil
        aacService = nil; laborClubService = nil; competitionService = nil
        studentScheduleService = nil; courseScheduleService = nil; planService = nil; repairService = nil
        teacherEvaluationService = nil
    }
}
