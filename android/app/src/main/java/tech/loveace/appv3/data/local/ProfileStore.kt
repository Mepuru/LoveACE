package tech.loveace.appv3.data.local

import android.content.Context
import android.net.Uri

/**
 * 用户个人资料存储 - 假头像和昵称
 * 按用户学号隔离存储
 */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("user_profile", Context.MODE_PRIVATE)

    /** 当前活跃用户 ID（学号），由外部设置 */
    var activeUserId: String = ""

    private fun key(base: String) = if (activeUserId.isNotEmpty()) "${activeUserId}_$base" else base

    var nickname: String
        get() = prefs.getString(key(KEY_NICKNAME), "") ?: ""
        set(value) = prefs.edit().putString(key(KEY_NICKNAME), value).apply()

    var avatarUri: String?
        get() = prefs.getString(key(KEY_AVATAR_URI), null)
        set(value) = prefs.edit().putString(key(KEY_AVATAR_URI), value).apply()

    var homeImageUri: String?
        get() = prefs.getString(key(KEY_HOME_IMAGE_URI), null)
        set(value) = prefs.edit().putString(key(KEY_HOME_IMAGE_URI), value).apply()

    var laborImageUri: String?
        get() = prefs.getString(key(KEY_LABOR_IMAGE_URI), null)
        set(value) = prefs.edit().putString(key(KEY_LABOR_IMAGE_URI), value).apply()

    fun clear() {
        // 只清除当前用户的数据
        prefs.edit()
            .remove(key(KEY_NICKNAME))
            .remove(key(KEY_AVATAR_URI))
            .remove(key(KEY_HOME_IMAGE_URI))
            .remove(key(KEY_LABOR_IMAGE_URI))
            .apply()
    }

    companion object {
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_AVATAR_URI = "avatar_uri"
        private const val KEY_HOME_IMAGE_URI = "home_image_uri"
        private const val KEY_LABOR_IMAGE_URI = "labor_image_uri"
    }
}
