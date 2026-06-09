import Foundation

final class ProfileStore: Sendable {
    private let defaults = UserDefaults(suiteName: "user_profile") ?? .standard

    nonisolated var activeUserId: String {
        get { UserDefaults.standard.string(forKey: "profile_active_user") ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: "profile_active_user") }
    }

    private func key(_ base: String) -> String {
        let uid = activeUserId
        return uid.isEmpty ? base : "\(uid)_\(base)"
    }

    var nickname: String {
        get { defaults.string(forKey: key("nickname")) ?? "" }
        set { defaults.set(newValue, forKey: key("nickname")) }
    }

    var avatarURI: String? {
        get { defaults.string(forKey: key("avatar_uri")) }
        set { defaults.set(newValue, forKey: key("avatar_uri")) }
    }

    var homeImageURI: String? {
        get { defaults.string(forKey: key("home_image_uri")) }
        set { defaults.set(newValue, forKey: key("home_image_uri")) }
    }

    var laborImageURI: String? {
        get { defaults.string(forKey: key("labor_image_uri")) }
        set { defaults.set(newValue, forKey: key("labor_image_uri")) }
    }

    func clear() {
        defaults.removeObject(forKey: key("nickname"))
        defaults.removeObject(forKey: key("avatar_uri"))
        defaults.removeObject(forKey: key("home_image_uri"))
        defaults.removeObject(forKey: key("labor_image_uri"))
    }
}
