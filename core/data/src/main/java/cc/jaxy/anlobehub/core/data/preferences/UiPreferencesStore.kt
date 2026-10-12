package cc.jaxy.anlobehub.core.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.jaxy.anlobehub.core.common.preferences.AppLanguage
import cc.jaxy.anlobehub.core.common.preferences.AppTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

internal val Context.uiPrefsDataStore by preferencesDataStore(name = "ui_prefs")

/**
 * Persists UI preferences (language + theme). Defaults follow the system.
 */
@Singleton
class UiPreferencesStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val languageKey = stringPreferencesKey("app_language")
    private val themeKey = stringPreferencesKey("app_theme")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")

    companion object {
        private val staticLanguageKey = stringPreferencesKey("app_language")

        /**
         * Blocking read for [android.app.Activity.attachBaseContext], which runs
         * before Hilt injection. Returns null when following the system.
         */
        fun readLanguageTagBlocking(context: Context): String? {
            return try {
                runBlocking {
                    context.uiPrefsDataStore.data.map { it[staticLanguageKey] }.first()
                }.takeUnless { it.isNullOrBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }

    val language: Flow<AppLanguage> =
        context.uiPrefsDataStore.data.map { AppLanguage.fromTag(it[languageKey]) }

    val theme: Flow<AppTheme> =
        context.uiPrefsDataStore.data.map { AppTheme.fromName(it[themeKey]) }

    val dynamicColor: Flow<Boolean> =
        context.uiPrefsDataStore.data.map { it[dynamicColorKey] ?: false }

    suspend fun setLanguage(language: AppLanguage) {
        context.uiPrefsDataStore.edit {
            if (language == AppLanguage.SYSTEM) {
                it.remove(languageKey)
            } else {
                it[languageKey] = language.tag.orEmpty()
            }
        }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.uiPrefsDataStore.edit { it[dynamicColorKey] = enabled }
    }

    suspend fun setTheme(theme: AppTheme) {
        context.uiPrefsDataStore.edit {
            if (theme == AppTheme.SYSTEM) {
                it.remove(themeKey)
            } else {
                it[themeKey] = theme.name
            }
        }
    }
}
