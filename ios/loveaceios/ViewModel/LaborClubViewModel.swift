import Foundation

@MainActor @Observable
final class LaborClubViewModel {
    var isLoading = false
    var progress: LaborClubProgressInfo?
    var joinedActivities: [LaborClubActivity] = []
    var clubs: [LaborClubInfo] = []
    var signInResult: SignInResponse?
    var applyResult: String?
    var error: String?
    var ongoingActivities: [LaborClubActivity] = []
    var finishedActivities: [LaborClubActivity] = []
    var availableActivities: [LaborClubActivity] = []
    var fullActivities: [LaborClubActivity] = []
    var notStartedActivities: [LaborClubActivity] = []
    var expiredActivities: [LaborClubActivity] = []
    private(set) var service: LaborClubService?

    func initialize(service: LaborClubService) { self.service = service }

    func loadAll() {
        guard let svc = service else { return }
        Task {
            isLoading = true; error = nil
            let progressResult = await svc.getProgress()
            let joinedResult = await svc.getJoinedActivities()
            let clubsResult = await svc.getJoinedClubs()
            var joined = joinedResult.data ?? []
            let clubsList = clubsResult.data ?? []
            for i in joined.indices {
                let signResult = await svc.getSignList(activityId: joined[i].activityId)
                if signResult.success { joined[i].signList = signResult.data }
            }
            var allActivities: [LaborClubActivity] = []
            for club in clubsList {
                let result = await svc.getClubActivities(clubId: club.clubInfoId)
                if result.success { allActivities.append(contentsOf: result.data ?? []) }
            }
            progress = progressResult.data
            joinedActivities = joined; clubs = clubsList
            error = progressResult.error ?? joinedResult.error
            categorize(joined: joined, all: allActivities)
            isLoading = false
        }
    }

    private func categorize(joined: [LaborClubActivity], all: [LaborClubActivity]) {
        let now = Date()
        let joinedIds = Set(joined.map { $0.activityId })

        var ongoing: [LaborClubActivity] = [], finished: [LaborClubActivity] = []
        for a in joined {
            if let start = Self.parseDate(a.startTime), start > now { ongoing.append(a) } else { finished.append(a) }
        }

        var avail: [LaborClubActivity] = [], full: [LaborClubActivity] = []
        var notStarted: [LaborClubActivity] = [], expired: [LaborClubActivity] = []
        for a in all where !joinedIds.contains(a.activityId) {
            guard let signStart = Self.parseDate(a.signUpStartTime),
                  let signEnd = Self.parseDate(a.signUpEndTime),
                  let end = Self.parseDate(a.endTime) else { expired.append(a); continue }
            if end < now {
                expired.append(a)
            } else if signStart > now {
                notStarted.append(a)
            } else if signEnd > now {
                if a.memberNum >= a.peopleNum { full.append(a) } else { avail.append(a) }
            } else {
                if a.memberNum >= a.peopleNum { full.append(a) } else { expired.append(a) }
            }
        }
        ongoingActivities = ongoing; finishedActivities = finished
        availableActivities = avail; fullActivities = full
        notStartedActivities = notStarted.sorted { $0.signUpStartTime < $1.signUpStartTime }
        expiredActivities = expired
    }

    private static func parseDate(_ str: String) -> Date? {
        let cleaned = str.replacingOccurrences(of: "T", with: " ")
            .components(separatedBy: ".").first ?? str
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        for format in ["yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"] {
            fmt.dateFormat = format
            if let d = fmt.date(from: cleaned) { return d }
        }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime]
        return iso.date(from: str)
    }

    func applyActivity(activityId: String) {
        guard let svc = service else { return }
        Task {
            let result = await svc.applyActivity(activityId: activityId)
            applyResult = result.success ? "报名成功" : (result.error ?? "报名失败")
            if result.success { loadAll() }
        }
    }

    func scanSignIn(qrData: String) {
        guard let svc = service else { return }
        let baseLng = 117.424733, baseLat = 32.905237, jitter = 0.0001
        let lng = baseLng + Double.random(in: -jitter...jitter)
        let lat = baseLat + Double.random(in: -jitter...jitter)
        Task {
            isLoading = true; signInResult = nil
            let result = await svc.scanSignIn(qrData: qrData, location: "\(lng),\(lat)")
            signInResult = result.data; error = result.error; isLoading = false
            if result.data?.isSuccess == true { loadAll() }
        }
    }

    func clearSignInResult() { signInResult = nil }
    func clearApplyResult() { applyResult = nil }
}
