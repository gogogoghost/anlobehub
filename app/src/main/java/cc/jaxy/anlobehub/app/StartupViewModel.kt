package cc.jaxy.anlobehub.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.data.session.AuthRepository
import cc.jaxy.anlobehub.core.data.session.AuthState
import cc.jaxy.anlobehub.core.data.session.ServerStore
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Startup gate. [startDestination] resolves from local state only (DataStore
 * baseUrl + cached auth), so first paint never waits on network. The session
 * refresh runs in parallel; [authRedirect] fires only when the cached
 * session turns out invalid.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    @param:ApplicationContext private val context: android.content.Context,
    private val serverStore: ServerStore,
    private val authRepository: AuthRepository,
) : ViewModel() {

    sealed interface Redirect {
        data object Server : Redirect
        data class Login(val baseUrl: String) : Redirect
    }

    private val _startDestination = MutableStateFlow<Any?>(null)
    val startDestination: StateFlow<Any?> = _startDestination.asStateFlow()

    private val _authRedirect = MutableStateFlow<Redirect?>(null)
    val authRedirect: StateFlow<Redirect?> = _authRedirect.asStateFlow()

    fun consumeRedirect() {
        _authRedirect.value = null
    }

    init {
        viewModelScope.launch {
            val baseUrl = runCatching { serverStore.baseUrl.first() }.getOrNull()
            if (baseUrl.isNullOrBlank()) {
                _startDestination.value = ServerRoute
                return@launch
            }
            // Optimistic: go straight home; verify the session in parallel.
            _startDestination.value = AgentsRoute
            val cached = runCatching { authRepository.authState.first() }.getOrNull()
            val state = when (val result = authRepository.refresh(baseUrl)) {
                is AnResult.Ok -> result.value
                // Network error with a cached session: stay home (offline);
                // only a definitive signed-out pushes to Login.
                is AnResult.Err -> if (cached is AuthState.SignedIn) cached else AuthState.SignedOut
            }
            if (state !is AuthState.SignedIn) {
                _authRedirect.value = Redirect.Login(baseUrl)
            } else {
                // Prewarm avatar into memory so Settings shows it with zero flash.
                val avatar = state.avatar?.takeIf { it.isNotBlank() }
                if (avatar != null) {
                    val request = ImageRequest.Builder(context)
                        .data(avatar)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .diskCacheKey(avatar)
                        .build()
                    runCatching { SingletonImageLoader.get(context).enqueue(request) }
                }
            }
        }
    }
}
