package cc.jaxy.anlobehub.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.common.preferences.AppLanguage
import cc.jaxy.anlobehub.core.common.preferences.AppTheme
import cc.jaxy.anlobehub.core.data.preferences.UiPreferencesStore
import cc.jaxy.anlobehub.core.data.session.AuthRepository
import cc.jaxy.anlobehub.core.data.session.AuthState
import cc.jaxy.anlobehub.core.data.session.ServerStore
import cc.jaxy.anlobehub.core.data.user.UserProfile
import cc.jaxy.anlobehub.core.data.user.UserRepository
import cc.jaxy.anlobehub.core.designsystem.text.UiText
import cc.jaxy.anlobehub.core.designsystem.text.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val serverStore: ServerStore,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val uiPreferencesStore: UiPreferencesStore,
) : ViewModel() {

    data class UiState(
        val baseUrl: String? = null,
        val profile: UserProfile? = null,
        val loading: Boolean = false,
        val error: UiText? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _signedOutEvent = MutableSharedFlow<Unit>()
    val signedOutEvent: SharedFlow<Unit> = _signedOutEvent.asSharedFlow()

    private val _switchServerEvent = MutableSharedFlow<Unit>()
    val switchServerEvent: SharedFlow<Unit> = _switchServerEvent.asSharedFlow()

    init {
        // Show instantly from caches (profile cache wins: it carries the
        // avatar URL, so Coil hits memory and the avatar shows in 0 frames).
        viewModelScope.launch {
            val baseUrl = runCatching { serverStore.baseUrl.first() }.getOrNull()
            val cached = runCatching { authRepository.authState.first() }.getOrNull()
            val cachedProfile = userRepository.cachedProfile()
                ?: (cached as? AuthState.SignedIn)?.let {
                    UserProfile(
                        userId = it.userId,
                        email = it.email,
                        fullName = it.name,
                        avatar = it.avatar,
                    )
                }
            _uiState.value = _uiState.value.copy(
                baseUrl = baseUrl,
                profile = cachedProfile,
                loading = false,
                error = if (baseUrl.isNullOrBlank()) UiText.Res(R.string.no_server) else null,
            )
            if (!baseUrl.isNullOrBlank()) refreshProfile(baseUrl, silent = true)
        }
    }

    fun load() {
        viewModelScope.launch {
            val baseUrl = runCatching { serverStore.baseUrl.first() }.getOrNull()
            if (baseUrl.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(
                    baseUrl = null,
                    loading = false,
                    error = UiText.Res(R.string.no_server),
                )
                return@launch
            }
            // Keep showing cached content; only show spinner when nothing to show.
            if (_uiState.value.profile == null) {
                _uiState.value = _uiState.value.copy(baseUrl = baseUrl, loading = true, error = null)
            } else {
                _uiState.value = _uiState.value.copy(baseUrl = baseUrl, error = null)
            }
            refreshProfile(baseUrl, silent = false)
        }
    }

    private suspend fun refreshProfile(baseUrl: String, silent: Boolean) {
        when (val result = userRepository.getUserState(baseUrl)) {
            is AnResult.Ok -> _uiState.value = _uiState.value.copy(
                baseUrl = baseUrl,
                profile = result.value,
                loading = false,
                error = null,
            )
            is AnResult.Err -> {
                // Silent refresh failure must not wipe cached content.
                if (silent && _uiState.value.profile != null) {
                    _uiState.value = _uiState.value.copy(baseUrl = baseUrl, loading = false)
                } else {
                    _uiState.value = _uiState.value.copy(
                        baseUrl = baseUrl,
                        loading = false,
                        error = result.error.toUiText(),
                    )
                }
            }
        }
    }

    /**
     * 退出登录。保留 [ServerStore] 中的服务器地址便于重登，
     * 导航由父通过 [signedOutEvent] 接管。
     */
    fun signOut() {
        viewModelScope.launch {
            val baseUrl = runCatching { serverStore.baseUrl.first() }.getOrNull().orEmpty()
            authRepository.signOut(baseUrl)
            userRepository.clearCache()
            _signedOutEvent.emit(Unit)
        }
    }
    /** 切换服务器：清空 [ServerStore] 后由父通过 [switchServerEvent] 导航。 */
    fun switchServer() {
        viewModelScope.launch {
            serverStore.clear()
            userRepository.clearCache()
            _switchServerEvent.emit(Unit)
        }
    }

    val language: StateFlow<AppLanguage> = uiPreferencesStore.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppLanguage.SYSTEM)

    val theme: StateFlow<AppTheme> = uiPreferencesStore.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppTheme.SYSTEM)

    val dynamicColor: StateFlow<Boolean> = uiPreferencesStore.dynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { uiPreferencesStore.setLanguage(language) }
    }

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { uiPreferencesStore.setTheme(theme) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { uiPreferencesStore.setDynamicColor(enabled) }
    }
}
