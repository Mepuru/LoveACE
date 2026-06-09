import Foundation
import CommonCrypto
import os

private let logger = Logger(subsystem: "tech.loveace.loveaceios", category: "AUFEConnection")

actor AUFEConnection {
    let userId: String
    private let ecPassword: String
    private let password: String

    var client: HTTPClient!
    var simpleClient: HTTPClient!
    var noRedirectClient: HTTPClient!

    private(set) var twfId: String?
    private var ecLogged = false
    private var uaapLogged = false

    static let serverURL = "https://vpn2.aufe.edu.cn"
    static let uaapLoginURL =
        "http://uaap-aufe-edu-cn.vpn2.aufe.edu.cn:8118/cas/login?service=http%3A%2F%2Fjwcxk2.aufe.edu.cn%2Fj_spring_cas_security_check"
    static let timeout: TimeInterval = 60

    init(userId: String, ecPassword: String, password: String) {
        self.userId = userId
        self.ecPassword = ecPassword
        self.password = password
    }

    func startClient() {
        let storage = HTTPCookieStorage.shared
        storage.cookieAcceptPolicy = .always
        if let oldCookies = storage.cookies {
            for c in oldCookies { storage.deleteCookie(c) }
        }
        client = HTTPClient(baseUrl: Self.serverURL, timeoutInterval: Self.timeout, cookieStorage: storage)
        simpleClient = HTTPClient(baseUrl: Self.serverURL, timeoutInterval: Self.timeout, cookieStorage: storage)
        noRedirectClient = HTTPClient(baseUrl: Self.serverURL, timeoutInterval: Self.timeout,
                                      followRedirects: false, cookieStorage: storage)
    }

    func setOnSessionExpired(_ callback: @escaping @Sendable () -> Void) async {
        await client.setSessionExpired(callback)
        await simpleClient.setSessionExpired(callback)
    }

    // MARK: - Heartbeat

    func heartbeat() async -> Bool {
        do {
            let (data, _) = try await client.get("\(Self.serverURL)/por/login_auth.csp?apiversion=1")
            let body = String(data: data, encoding: .utf8) ?? ""
            let alive = body.contains("<TwfID>")
            logger.debug("💓 Heartbeat: \(alive ? "alive" : "expired")")
            return alive
        } catch {
            logger.warning("💓 Heartbeat failed: \(error.localizedDescription)")
            return false
        }
    }

    // MARK: - Reconnect

    func reconnect() async -> Bool {
        logger.info("🔄 Reconnecting...")
        startClient()
        let ec = await ecLogin()
        guard ec.success else { logger.error("🔄 EC re-login failed"); return false }
        let uaap = await uaapLogin()
        guard uaap.success else { logger.error("🔄 UAAP re-login failed"); return false }
        logger.info("✅ Reconnect succeeded")
        return true
    }

    // MARK: - EC Login

    func ecLogin() async -> ECLoginStatus {
        do {
            return try await performEcLogin()
        } catch {
            logger.error("EC login error: \(error.localizedDescription)")
            return ECLoginStatus(failUnknownError: true)
        }
    }

    private func performEcLogin() async throws -> ECLoginStatus {
        logger.info("🔑 EC Login Step 1: fetching auth params...")
        let (data, _) = try await client.get("\(Self.serverURL)/por/login_auth.csp?apiversion=1")
        let body = String(data: data, encoding: .utf8) ?? ""
        if body.isEmpty {
            logger.error("❌ EC auth response is empty")
            return ECLoginStatus(failNetworkError: true)
        }
        logger.info("📄 EC auth response: \(body.prefix(300))")

        guard let twfMatch = body.range(of: "(?<=<TwfID>).+?(?=</TwfID>)", options: .regularExpression) else {
            logger.error("❌ TwfID not found in response")
            return ECLoginStatus(failNotFoundTwfid: true)
        }
        twfId = String(body[twfMatch])
        logger.info("✅ TwfID: \(self.twfId ?? "nil")")

        guard let rsaKeyMatch = body.range(of: "(?<=<RSA_ENCRYPT_KEY>).+?(?=</RSA_ENCRYPT_KEY>)", options: .regularExpression) else {
            logger.error("❌ RSA key not found")
            return ECLoginStatus(failNotFoundRsaKey: true)
        }
        let rsaKey = String(body[rsaKeyMatch])
        logger.info("✅ RSA key length: \(rsaKey.count)")

        guard let rsaExpMatch = body.range(of: "(?<=<RSA_ENCRYPT_EXP>).+?(?=</RSA_ENCRYPT_EXP>)", options: .regularExpression) else {
            logger.error("❌ RSA exponent not found")
            return ECLoginStatus(failNotFoundRsaExp: true)
        }
        let rsaExp = String(body[rsaExpMatch])
        logger.info("✅ RSA exponent: \(rsaExp)")

        guard let csrfMatch = body.range(of: "(?<=<CSRF_RAND_CODE>).+?(?=</CSRF_RAND_CODE>)", options: .regularExpression) else {
            logger.error("❌ CSRF code not found")
            return ECLoginStatus(failNotFoundCsrfCode: true)
        }
        let csrfCode = String(body[csrfMatch])
        logger.info("✅ CSRF code: \(csrfCode)")

        let passwordToEncrypt = "\(ecPassword)_\(csrfCode)"
        let encryptedPassword = CryptoHelper.rsaEncrypt(plaintext: passwordToEncrypt, modulusHex: rsaKey, exponentStr: rsaExp)
        logger.info("🔐 Encrypted password length: \(encryptedPassword.count), empty: \(encryptedPassword.isEmpty)")

        logger.info("🔑 EC Login Step 2: posting credentials...")
        let (loginData, _) = try await client.post(
            "\(Self.serverURL)/por/login_psw.csp?anti_replay=1&encrypt=1&type=cs",
            formData: [
                "svpn_rand_code": "",
                "mitm": "",
                "svpn_req_randcode": csrfCode,
                "svpn_name": userId,
                "svpn_password": encryptedPassword
            ],
            headers: ["Cookie": "TWFID=\(twfId!)"]
        )
        let loginBody = String(data: loginData, encoding: .utf8) ?? ""
        logger.info("📄 EC login response: \(loginBody.prefix(500))")

        if loginBody.contains("<Result>1</Result>") {
            await client.setCookie(name: "TWFID", value: twfId!, domain: ".vpn2.aufe.edu.cn")
            ecLogged = true
            await simpleClient.copyCookies(from: client)
            await noRedirectClient.copyCookies(from: client)
            logger.info("✅ EC Login succeeded")
            return ECLoginStatus(success: true)
        } else if loginBody.contains("Invalid username or password!") {
            logger.error("❌ EC Login: invalid credentials")
            return ECLoginStatus(failInvalidCredentials: true)
        } else if loginBody.contains("[CDATA[maybe attacked]]") || loginBody.contains("CAPTCHA required") {
            logger.error("❌ EC Login: maybe attacked / captcha required")
            return ECLoginStatus(failMaybeAttacked: true)
        }
        logger.error("❌ EC Login: unknown error, response snippet: \(loginBody.prefix(200))")
        return ECLoginStatus(failUnknownError: true)
    }

    // MARK: - UAAP Login

    func uaapLogin() async -> UAAPLoginStatus {
        do {
            return try await performUaapLogin()
        } catch {
            logger.error("UAAP login error: \(error.localizedDescription)")
            return UAAPLoginStatus(failUnknownError: true)
        }
    }

    private func performUaapLogin() async throws -> UAAPLoginStatus {
        logger.info("🔑 UAAP Login Step 1: fetching CAS page...")
        let (data, _) = try await client.get(Self.uaapLoginURL)
        let body = String(data: data, encoding: .utf8) ?? ""
        if body.isEmpty {
            logger.error("❌ UAAP CAS page is empty")
            return UAAPLoginStatus(failNetworkError: true)
        }

        guard let ltMatch = body.range(of: #"(?<=name="lt" value=").+?(?=")"#, options: .regularExpression) else {
            logger.error("❌ lt not found. Body snippet: \(body.prefix(500))")
            return UAAPLoginStatus(failNotFoundLt: true)
        }
        let ltValue = String(body[ltMatch])
        logger.info("✅ lt value: \(ltValue.prefix(20))... (len=\(ltValue.count))")

        guard let execMatch = body.range(of: #"(?<=name="execution" value=").+?(?=")"#, options: .regularExpression) else {
            logger.error("❌ execution not found")
            return UAAPLoginStatus(failNotFoundExecution: true)
        }
        let executionValue = String(body[execMatch])
        logger.info("✅ execution: \(executionValue.prefix(20))...")

        let encryptedPassword = CryptoHelper.desEncrypt(plaintext: password, key: ltValue)
        logger.info("🔐 DES encrypted password: \(encryptedPassword.prefix(30))... (len=\(encryptedPassword.count))")

        logger.info("🔑 UAAP Login Step 2: posting credentials...")
        let (loginData, loginResponse) = try await client.post(
            Self.uaapLoginURL,
            formData: [
                "username": userId,
                "password": encryptedPassword,
                "lt": ltValue,
                "execution": executionValue,
                "_eventId": "submit",
                "submit": "LOGIN"
            ]
        )
        let loginBody = String(data: loginData, encoding: .utf8) ?? ""
        let responseUrl = loginResponse.url?.absoluteString ?? ""
        logger.info("📄 UAAP response URL: \(responseUrl)")
        logger.info("📄 UAAP response size: \(loginBody.count), contains '用户名或密码错误': \(loginBody.contains("用户名或密码错误")), contains 'ticket': \(responseUrl.contains("ticket="))")

        if loginBody.contains("Invalid username or password") || loginBody.contains("用户名或密码错误") {
            logger.error("❌ UAAP Login: invalid credentials")
            return UAAPLoginStatus(failInvalidCredentials: true)
        }
        if responseUrl.hasPrefix("http://jwcxk2") || responseUrl.contains("ticket=") {
            uaapLogged = true
            await simpleClient.copyCookies(from: client)
            await noRedirectClient.copyCookies(from: client)
            logger.info("✅ UAAP Login succeeded")
            return UAAPLoginStatus(success: true)
        }
        logger.error("❌ UAAP Login: unknown error. responseUrl=\(responseUrl), body snippet: \(loginBody.prefix(300))")
        return UAAPLoginStatus(failUnknownError: true)
    }

    var isHealthy: Bool { ecLogged && uaapLogged }
}

// MARK: - HTTPClient extension for session expired

extension HTTPClient {
    func setSessionExpired(_ callback: @escaping @Sendable () -> Void) {
        self.onSessionExpired = callback
    }
}

// MARK: - CryptoHelper

enum CryptoHelper {
    static func rsaEncrypt(plaintext: String, modulusHex: String, exponentStr: String) -> String {
        guard let plainData = plaintext.data(using: .utf8) else { return "" }
        guard let modulusData = hexToData(modulusHex) else { return "" }
        guard let exponent = UInt32(exponentStr) else { return "" }

        var expBytes = withUnsafeBytes(of: exponent.bigEndian) { Array($0) }
        while expBytes.first == 0 && expBytes.count > 1 { expBytes.removeFirst() }
        let exponentBytes = Data(expBytes)

        let keyData = buildDERPublicKey(modulus: modulusData, exponent: exponentBytes)

        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass as String: kSecAttrKeyClassPublic,
            kSecAttrKeySizeInBits as String: modulusData.count * 8,
        ]
        var error: Unmanaged<CFError>?
        guard let secKey = SecKeyCreateWithData(keyData as CFData, attributes as CFDictionary, &error) else {
            return ""
        }
        guard let encrypted = SecKeyCreateEncryptedData(secKey, .rsaEncryptionPKCS1, plainData as CFData, &error) else {
            return ""
        }
        return (encrypted as Data).map { String(format: "%02x", $0) }.joined()
    }

    private static func hexToData(_ hex: String) -> Data? {
        var data = Data()
        var idx = hex.startIndex
        while idx < hex.endIndex {
            let next = hex.index(idx, offsetBy: 2, limitedBy: hex.endIndex) ?? hex.endIndex
            guard next != idx else { break }
            guard let byte = UInt8(hex[idx..<next], radix: 16) else { return nil }
            data.append(byte)
            idx = next
        }
        return data
    }

    private static func buildDERPublicKey(modulus: Data, exponent: Data) -> Data {
        var modulusBytes = modulus
        if modulusBytes.first! >= 0x80 { modulusBytes.insert(0x00, at: 0) }
        var exponentBytes = exponent
        if exponentBytes.first! >= 0x80 { exponentBytes.insert(0x00, at: 0) }

        let modTLV = derInteger(modulusBytes)
        let expTLV = derInteger(exponentBytes)
        let sequence = derSequence(modTLV + expTLV)

        let bitString = Data([0x03]) + derLength(sequence.count + 1) + Data([0x00]) + sequence
        let algorithmOID: [UInt8] = [
            0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86, 0x48, 0x86,
            0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00
        ]
        let outerSequence = derSequence(Data(algorithmOID) + bitString)
        return outerSequence
    }

    private static func derInteger(_ data: Data) -> Data {
        Data([0x02]) + derLength(data.count) + data
    }

    private static func derSequence(_ data: Data) -> Data {
        Data([0x30]) + derLength(data.count) + data
    }

    private static func derLength(_ length: Int) -> Data {
        if length < 0x80 { return Data([UInt8(length)]) }
        if length < 0x100 { return Data([0x81, UInt8(length)]) }
        return Data([0x82, UInt8(length >> 8), UInt8(length & 0xFF)])
    }

    static func desEncrypt(plaintext: String, key: String) -> String {
        guard let plainData = plaintext.data(using: .utf8) else { return "" }
        var keyBytes = Array(key.utf8)
        if keyBytes.count > 8 { keyBytes = Array(keyBytes.prefix(8)) }
        while keyBytes.count < 8 { keyBytes.append(0) }

        var tripleKey = [UInt8](repeating: 0, count: 24)
        for i in 0..<8 { tripleKey[i] = keyBytes[i]; tripleKey[i+8] = keyBytes[i]; tripleKey[i+16] = keyBytes[i] }

        let bufferSize = plainData.count + kCCBlockSize3DES
        var buffer = [UInt8](repeating: 0, count: bufferSize)
        var numBytesEncrypted: size_t = 0

        let status = plainData.withUnsafeBytes { plainBytes in
            CCCrypt(CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithm3DES),
                    CCOptions(kCCOptionPKCS7Padding | kCCOptionECBMode),
                    tripleKey, kCCKeySize3DES, nil,
                    plainBytes.baseAddress, plainData.count,
                    &buffer, bufferSize, &numBytesEncrypted)
        }

        guard status == kCCSuccess else { return "" }
        return Data(buffer.prefix(numBytesEncrypted)).base64EncodedString()
    }
}
