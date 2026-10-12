package cc.jaxy.anlobehub.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cc.jaxy.anlobehub.core.common.preferences.AppTheme
import cc.jaxy.anlobehub.core.data.preferences.UiPreferencesStore
import cc.jaxy.anlobehub.core.designsystem.theme.AnlobehubTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ThemeViewModel @Inject constructor(
    uiPreferences: UiPreferencesStore,
) : ViewModel() {
    val theme: StateFlow<AppTheme> = uiPreferences.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppTheme.SYSTEM)
    val dynamicColor: StateFlow<Boolean> = uiPreferences.dynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

/**
 * Theme entry that honors the stored [AppTheme] preference.
 *
 * SYSTEM follows [isSystemInDarkTheme]; LIGHT/DARK force that mode.
 */
@Composable
fun AnlobehubAppTheme(
    viewModel: ThemeViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    AnlobehubTheme(
        dynamicColor = dynamicColor,
        darkTheme = when (theme) {
            AppTheme.SYSTEM -> systemDark
            AppTheme.LIGHT -> false
            AppTheme.DARK -> true
        },
        content = content,
    )
}
