import Foundation

final class ScheduleStore: Sendable {
    private let defaults = UserDefaults.standard
    private static let prefsName = "schedule_cache"

    nonisolated var activeUserId: String {
        get { UserDefaults.standard.string(forKey: "schedule_active_user") ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: "schedule_active_user") }
    }

    private func key(_ base: String) -> String {
        let uid = activeUserId
        return uid.isEmpty ? base : "\(uid)_\(base)"
    }

    func saveCourses(_ courses: [ScheduleCourse]) {
        if let data = try? JSONEncoder().encode(courses) {
            defaults.set(data, forKey: key("courses_json"))
            defaults.set(Date().timeIntervalSince1970, forKey: key("updated_at"))
        }
    }

    func loadCourses() -> [ScheduleCourse] {
        guard let data = defaults.data(forKey: key("courses_json")) else { return [] }
        return (try? JSONDecoder().decode([ScheduleCourse].self, from: data)) ?? []
    }

    func hasCourses() -> Bool { defaults.data(forKey: key("courses_json")) != nil }

    func saveSemesterJSON(_ rawJSON: String) {
        defaults.set(rawJSON, forKey: key("semester_json"))
        defaults.set(Date().timeIntervalSince1970, forKey: key("semester_updated_at"))
    }

    func loadSemesterJSON() -> String? { defaults.string(forKey: key("semester_json")) }

    func clear() {
        defaults.removeObject(forKey: key("courses_json"))
        defaults.removeObject(forKey: key("updated_at"))
        defaults.removeObject(forKey: key("semester_json"))
        defaults.removeObject(forKey: key("semester_updated_at"))
    }
}
