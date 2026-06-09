package tech.loveace.appv3.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import tech.loveace.appv3.data.model.UserCredentials

/**
 * 加密凭证存储 - 使用 EncryptedSharedPreferences (AES-256)
 */
class CredentialStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "loveace_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(credentials: UserCredentials) {
        prefs.edit()
            .putString(KEY_USER_ID, credentials.userId)
            .putString(KEY_EC_PASSWORD, credentials.ecPassword)
            .putString(KEY_PASSWORD, credentials.password)
            .apply()
    }

    fun load(): UserCredentials? {
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val ecPassword = prefs.getString(KEY_EC_PASSWORD, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        return UserCredentials(userId, ecPassword, password)
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_EC_PASSWORD)
            .remove(KEY_PASSWORD)
            .apply()
    }

    fun hasCredentials(): Boolean = prefs.contains(KEY_USER_ID)

    // Remember password (separate from session credentials)
    fun saveRemembered(credentials: UserCredentials) {
        prefs.edit()
            .putString(KEY_REMEMBERED_USER_ID, credentials.userId)
            .putString(KEY_REMEMBERED_EC_PASSWORD, credentials.ecPassword)
            .putString(KEY_REMEMBERED_PASSWORD, credentials.password)
            .putBoolean(KEY_REMEMBER_ENABLED, true)
            .apply()
    }

    fun loadRemembered(): UserCredentials? {
        if (!prefs.getBoolean(KEY_REMEMBER_ENABLED, false)) return null
        val userId = prefs.getString(KEY_REMEMBERED_USER_ID, null) ?: return null
        val ecPassword = prefs.getString(KEY_REMEMBERED_EC_PASSWORD, null) ?: return null
        val password = prefs.getString(KEY_REMEMBERED_PASSWORD, null) ?: return null
        return UserCredentials(userId, ecPassword, password)
    }

    fun clearRemembered() {
        prefs.edit()
            .remove(KEY_REMEMBERED_USER_ID)
            .remove(KEY_REMEMBERED_EC_PASSWORD)
            .remove(KEY_REMEMBERED_PASSWORD)
            .remove(KEY_REMEMBER_ENABLED)
            .apply()
    }

    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EC_PASSWORD = "ec_password"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMEMBERED_USER_ID = "remembered_user_id"
        private const val KEY_REMEMBERED_EC_PASSWORD = "remembered_ec_password"
        private const val KEY_REMEMBERED_PASSWORD = "remembered_password"
        private const val KEY_REMEMBER_ENABLED = "remember_password_enabled"
    }
}
