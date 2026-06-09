package tech.loveace.appv3.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.loveace.appv3.data.local.ProfileStore

data class ProfileState(
    val nickname: String = "",
    val avatarUri: String? = null,
    val homeImageUri: String? = null,
    val laborImageUri: String? = null,
)

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ProfileStore(application)
    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    /** 设置当前用户 ID，切换用户隔离存储 */
    fun setActiveUserId(userId: String) {
        store.activeUserId = userId
        _state.value = ProfileState(store.nickname, store.avatarUri, store.homeImageUri, store.laborImageUri)
    }

    fun setNickname(name: String) {
        store.nickname = name
        _state.value = _state.value.copy(nickname = name)
    }

    fun setAvatarUri(uri: String?) {
        store.avatarUri = uri
        _state.value = _state.value.copy(avatarUri = uri)
    }

    fun setHomeImageUri(uri: String?) {
        store.homeImageUri = uri
        _state.value = _state.value.copy(homeImageUri = uri)
    }

    fun setLaborImageUri(uri: String?) {
        store.laborImageUri = uri
        _state.value = _state.value.copy(laborImageUri = uri)
    }
}
