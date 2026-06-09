import Foundation
import Security

final class CredentialStore: Sendable {
    private static let serviceName = "tech.loveace.loveaceios.credentials"
    private static let rememberedServiceName = "tech.loveace.loveaceios.remembered"

    func save(_ credentials: UserCredentials) {
        setKeychainValue(credentials.userId, forKey: "user_id", service: Self.serviceName)
        setKeychainValue(credentials.ecPassword, forKey: "ec_password", service: Self.serviceName)
        setKeychainValue(credentials.password, forKey: "password", service: Self.serviceName)
    }

    func load() -> UserCredentials? {
        guard let userId = getKeychainValue(forKey: "user_id", service: Self.serviceName),
              let ecPassword = getKeychainValue(forKey: "ec_password", service: Self.serviceName),
              let password = getKeychainValue(forKey: "password", service: Self.serviceName) else { return nil }
        return UserCredentials(userId: userId, ecPassword: ecPassword, password: password)
    }

    func clear() {
        deleteKeychainValue(forKey: "user_id", service: Self.serviceName)
        deleteKeychainValue(forKey: "ec_password", service: Self.serviceName)
        deleteKeychainValue(forKey: "password", service: Self.serviceName)
    }

    func hasCredentials() -> Bool { getKeychainValue(forKey: "user_id", service: Self.serviceName) != nil }

    func saveRemembered(_ credentials: UserCredentials) {
        setKeychainValue(credentials.userId, forKey: "user_id", service: Self.rememberedServiceName)
        setKeychainValue(credentials.ecPassword, forKey: "ec_password", service: Self.rememberedServiceName)
        setKeychainValue(credentials.password, forKey: "password", service: Self.rememberedServiceName)
        UserDefaults.standard.set(true, forKey: "remember_password_enabled")
    }

    func loadRemembered() -> UserCredentials? {
        guard UserDefaults.standard.bool(forKey: "remember_password_enabled") else { return nil }
        guard let userId = getKeychainValue(forKey: "user_id", service: Self.rememberedServiceName),
              let ecPassword = getKeychainValue(forKey: "ec_password", service: Self.rememberedServiceName),
              let password = getKeychainValue(forKey: "password", service: Self.rememberedServiceName) else { return nil }
        return UserCredentials(userId: userId, ecPassword: ecPassword, password: password)
    }

    func clearRemembered() {
        deleteKeychainValue(forKey: "user_id", service: Self.rememberedServiceName)
        deleteKeychainValue(forKey: "ec_password", service: Self.rememberedServiceName)
        deleteKeychainValue(forKey: "password", service: Self.rememberedServiceName)
        UserDefaults.standard.removeObject(forKey: "remember_password_enabled")
    }

    // MARK: - Keychain Helpers

    private func setKeychainValue(_ value: String, forKey key: String, service: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
        var newItem = query
        newItem[kSecValueData as String] = data
        SecItemAdd(newItem as CFDictionary, nil)
    }

    private func getKeychainValue(forKey key: String, service: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func deleteKeychainValue(forKey key: String, service: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }
}
