package cc.jaxy.anlobehub.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.anlobehub.core.common.preferences.AppLanguage
import cc.jaxy.anlobehub.core.common.preferences.AppTheme
import cc.jaxy.anlobehub.core.data.preferences.UiPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val uiPreferencesStore: UiPreferencesStore,
) : ViewModel() {
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
