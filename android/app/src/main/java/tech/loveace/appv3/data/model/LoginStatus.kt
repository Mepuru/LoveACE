package tech.loveace.appv3.data.model

data class ECLoginStatus(
    val success: Boolean = false,
    val failNotFoundTwfid: Boolean = false,
    val failNotFoundRsaKey: Boolean = false,
    val failNotFoundRsaExp: Boolean = false,
    val failNotFoundCsrfCode: Boolean = false,
    val failInvalidCredentials: Boolean = false,
    val failMaybeAttacked: Boolean = false,
    val failNetworkError: Boolean = false,
    val failUnknownError: Boolean = false,
) {
    val errorMessage: String
        get() = when {
            failNotFoundTwfid -> "未找到TwfID"
            failNotFoundRsaKey -> "未找到RSA密钥"
            failNotFoundRsaExp -> "未找到RSA指数"
            failNotFoundCsrfCode -> "未找到CSRF代码"
            failInvalidCredentials -> "用户名或密码错误"
            failMaybeAttacked -> "登录频繁，请稍后重试"
            failNetworkError -> "网络连接错误"
            failUnknownError -> "未知错误"
            else -> ""
        }
}

data class UAAPLoginStatus(
    val success: Boolean = false,
    val failNotFoundLt: Boolean = false,
    val failNotFoundExecution: Boolean = false,
    val failInvalidCredentials: Boolean = false,
    val failNetworkError: Boolean = false,
    val failUnknownError: Boolean = false,
) {
    val errorMessage: String
        get() = when {
            failNotFoundLt -> "未找到lt参数"
            failNotFoundExecution -> "未找到execution参数"
            failInvalidCredentials -> "用户名或密码错误"
            failNetworkError -> "网络连接错误"
            failUnknownError -> "未知错误"
            else -> ""
        }
}
