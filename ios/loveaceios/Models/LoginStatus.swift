import Foundation

struct ECLoginStatus {
    let success: Bool
    let failNotFoundTwfid: Bool
    let failNotFoundRsaKey: Bool
    let failNotFoundRsaExp: Bool
    let failNotFoundCsrfCode: Bool
    let failInvalidCredentials: Bool
    let failMaybeAttacked: Bool
    let failNetworkError: Bool
    let failUnknownError: Bool

    var errorMessage: String {
        if failNotFoundTwfid { return "未找到TwfID" }
        if failNotFoundRsaKey { return "未找到RSA密钥" }
        if failNotFoundRsaExp { return "未找到RSA指数" }
        if failNotFoundCsrfCode { return "未找到CSRF代码" }
        if failInvalidCredentials { return "用户名或密码错误" }
        if failMaybeAttacked { return "登录频繁，请稍后重试" }
        if failNetworkError { return "网络连接错误" }
        if failUnknownError { return "未知错误" }
        return ""
    }

    init(success: Bool = false, failNotFoundTwfid: Bool = false,
         failNotFoundRsaKey: Bool = false, failNotFoundRsaExp: Bool = false,
         failNotFoundCsrfCode: Bool = false, failInvalidCredentials: Bool = false,
         failMaybeAttacked: Bool = false, failNetworkError: Bool = false,
         failUnknownError: Bool = false) {
        self.success = success; self.failNotFoundTwfid = failNotFoundTwfid
        self.failNotFoundRsaKey = failNotFoundRsaKey; self.failNotFoundRsaExp = failNotFoundRsaExp
        self.failNotFoundCsrfCode = failNotFoundCsrfCode
        self.failInvalidCredentials = failInvalidCredentials
        self.failMaybeAttacked = failMaybeAttacked; self.failNetworkError = failNetworkError
        self.failUnknownError = failUnknownError
    }
}

struct UAAPLoginStatus {
    let success: Bool
    let failNotFoundLt: Bool
    let failNotFoundExecution: Bool
    let failInvalidCredentials: Bool
    let failNetworkError: Bool
    let failUnknownError: Bool

    var errorMessage: String {
        if failNotFoundLt { return "未找到lt参数" }
        if failNotFoundExecution { return "未找到execution参数" }
        if failInvalidCredentials { return "用户名或密码错误" }
        if failNetworkError { return "网络连接错误" }
        if failUnknownError { return "未知错误" }
        return ""
    }

    init(success: Bool = false, failNotFoundLt: Bool = false,
         failNotFoundExecution: Bool = false, failInvalidCredentials: Bool = false,
         failNetworkError: Bool = false, failUnknownError: Bool = false) {
        self.success = success; self.failNotFoundLt = failNotFoundLt
        self.failNotFoundExecution = failNotFoundExecution
        self.failInvalidCredentials = failInvalidCredentials
        self.failNetworkError = failNetworkError; self.failUnknownError = failUnknownError
    }
}
