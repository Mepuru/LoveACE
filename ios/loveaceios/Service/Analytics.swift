import CryptoKit
import Foundation
import UIKit

final class Analytics {
    static let shared = Analytics()

    private let defaults = UserDefaults.standard
    private let clientIdKey = "loveace.analytics.client_id"
    private let encoder = JSONEncoder()
    private let isoFormatter = ISO8601DateFormatter()

    private var clientId: String
    private var gradePrefix: String?
    private var studentHash: String?

    private init() {
        if let saved = defaults.string(forKey: clientIdKey) {
            clientId = saved
        } else {
            clientId = UUID().uuidString
            defaults.set(clientId, forKey: clientIdKey)
        }
    }

    func setUser(_ userId: String) {
        let prefix = String(userId.prefix(4))
        gradePrefix = prefix.count == 4 && prefix.allSatisfy(\.isNumber) ? prefix : nil
        studentHash = AnalyticsSecrets.hashSalt.isEmpty ? nil : md5Hex(userId + AnalyticsSecrets.hashSalt)
    }

    func clearUser() {
        gradePrefix = nil
        studentHash = nil
    }

    func trackAppStart(launchSource: String) {
        track("app_start", properties: ["launch_source": .string(launchSource)])
    }

    func trackLoginSuccess(userId: String) {
        setUser(userId)
        track("login_success")
    }

    func trackLoginFailed(userId: String, reason: String) {
        if !userId.isEmpty { setUser(userId) }
        track("login_failed", properties: ["reason": .string(reason)])
    }

    func trackSessionExpired(reason: String) {
        track("session_expired", properties: ["reason": .string(reason)])
    }

    func trackSessionReconnectSuccess() {
        track("session_reconnect_success", properties: ["result": "success"])
    }

    func trackSessionReconnectFailed() {
        track("session_reconnect_failed", properties: ["result": "failed"])
    }

    func trackScreen(_ screen: String) {
        track("screen_view", properties: ["screen": .string(screen)])
    }

    func trackFeature(_ feature: String, action: String = "open") {
        track("feature_action", properties: ["feature": .string(feature), "action": .string(action)])
    }

    private func track(_ name: String, properties: [String: AnalyticsValue] = [:]) {
        guard !AnalyticsSecrets.endpoint.isEmpty,
              !AnalyticsSecrets.apiKey.isEmpty,
              !AnalyticsSecrets.signingSecret.isEmpty,
              let url = URL(string: AnalyticsSecrets.endpoint) else { return }

        let event = AnalyticsEvent(name: name, time: isoFormatter.string(from: Date()), properties: properties)
        let payload = AnalyticsPayload(
            clientId: clientId,
            platform: "ios",
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "",
            build: Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "",
            osVersion: UIDevice.current.systemVersion,
            deviceModel: UIDevice.current.model,
            gradePrefix: gradePrefix,
            studentHash: studentHash,
            events: [event]
        )

        guard let body = try? encoder.encode(payload) else { return }
        Task.detached(priority: .utility) {
            await Self.post(body: body, to: url)
        }
    }

    private static func post(body: Data, to url: URL) async {
        let timestamp = String(Int(Date().timeIntervalSince1970))
        let nonce = UUID().uuidString
        let bodyHash = sha256Hex(body)
        let signature = hmacSha256Hex(secret: AnalyticsSecrets.signingSecret, message: "\(timestamp).\(nonce).\(bodyHash)")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(AnalyticsSecrets.apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue(timestamp, forHTTPHeaderField: "X-LoveACE-Timestamp")
        request.setValue(nonce, forHTTPHeaderField: "X-LoveACE-Nonce")
        request.setValue(signature, forHTTPHeaderField: "X-LoveACE-Signature")

        _ = try? await URLSession.shared.data(for: request)
    }

    private func md5Hex(_ value: String) -> String {
        let digest = Insecure.MD5.hash(data: Data(value.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private static func sha256Hex(_ data: Data) -> String {
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private static func hmacSha256Hex(secret: String, message: String) -> String {
        let key = SymmetricKey(data: Data(secret.utf8))
        let code = HMAC<SHA256>.authenticationCode(for: Data(message.utf8), using: key)
        return code.map { String(format: "%02x", $0) }.joined()
    }
}

private struct AnalyticsPayload: Encodable {
    let clientId: String
    let platform: String
    let appVersion: String
    let build: String
    let osVersion: String
    let deviceModel: String
    let gradePrefix: String?
    let studentHash: String?
    let events: [AnalyticsEvent]

    enum CodingKeys: String, CodingKey {
        case clientId = "client_id"
        case platform
        case appVersion = "app_version"
        case build
        case osVersion = "os_version"
        case deviceModel = "device_model"
        case gradePrefix = "grade_prefix"
        case studentHash = "student_hash"
        case events
    }
}

private struct AnalyticsEvent: Encodable {
    let name: String
    let time: String
    let properties: [String: AnalyticsValue]
}

enum AnalyticsValue: Encodable, ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByFloatLiteral, ExpressibleByBooleanLiteral {
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)

    init(stringLiteral value: String) { self = .string(value) }
    init(integerLiteral value: Int) { self = .int(value) }
    init(floatLiteral value: Double) { self = .double(value) }
    init(booleanLiteral value: Bool) { self = .bool(value) }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .int(let value): try container.encode(value)
        case .double(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        }
    }
}
