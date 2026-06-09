package tech.loveace.appv3.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tech.loveace.appv3.data.model.DoorCardCredentials
import tech.loveace.appv3.data.model.DoorCardUserInfo

/**
 * 门卡凭证存储 — 按用户学号隔离
 * 使用 EncryptedSharedPreferences 加密存储
 */
class DoorCardStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "door_card_store",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** 保存门卡绑定凭证（按学号隔离） */
    fun saveCredentials(appUserId: String, credentials: DoorCardCredentials) {
        prefs.edit()
            .putString("${appUserId}_dc_creds", json.encodeToString(credentials))
            .apply()
    }

    /** 加载门卡绑定凭证 */
    fun loadCredentials(appUserId: String): DoorCardCredentials? {
        val data = prefs.getString("${appUserId}_dc_creds", null) ?: return null
        return try { json.decodeFromString<DoorCardCredentials>(data) } catch (_: Exception) { null }
    }

    /** 保存门卡用户信息 */
    fun saveUserInfo(appUserId: String, userInfo: DoorCardUserInfo) {
        prefs.edit()
            .putString("${appUserId}_dc_user", json.encodeToString(userInfo))
            .apply()
    }

    /** 加载门卡用户信息 */
    fun loadUserInfo(appUserId: String): DoorCardUserInfo? {
        val data = prefs.getString("${appUserId}_dc_user", null) ?: return null
        return try { json.decodeFromString<DoorCardUserInfo>(data) } catch (_: Exception) { null }
    }

    /** 是否已绑定 */
    fun isBound(appUserId: String): Boolean = prefs.contains("${appUserId}_dc_creds")

    /** 解绑（清除该用户的门卡数据） */
    fun unbind(appUserId: String) {
        prefs.edit()
            .remove("${appUserId}_dc_creds")
            .remove("${appUserId}_dc_user")
            .apply()
    }
}
