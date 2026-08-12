package com.alad1nks.jaiqal.core.preferences

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.alad1nks.jaiqal.core.database.JaiqalDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

enum class AppThemePreference { SYSTEM, LIGHT, DARK }
enum class AppLanguage { SYSTEM, KAZAKH, RUSSIAN, ENGLISH }

data class AppPreferenceState(
    val theme: AppThemePreference = AppThemePreference.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
)

interface AppPreferences {
    val state: Flow<AppPreferenceState>
    suspend fun setTheme(theme: AppThemePreference)
    suspend fun setLanguage(language: AppLanguage)
}

class SqlDelightAppPreferences(database: JaiqalDatabase) : AppPreferences {
    private val queries = database.cacheMetadataQueries

    override val state: Flow<AppPreferenceState> = queries.selectAppPreferences()
        .asFlow()
        .mapToList(Dispatchers.Default)
        .map { rows ->
            val values = rows.associate { it.preference_key to it.preference_value }
            AppPreferenceState(
                theme = values[THEME_KEY].asTheme(),
                language = values[LANGUAGE_KEY].asLanguage(),
            )
        }

    override suspend fun setTheme(theme: AppThemePreference) {
        queries.upsertAppPreference(THEME_KEY, theme.name)
    }

    override suspend fun setLanguage(language: AppLanguage) {
        queries.upsertAppPreference(LANGUAGE_KEY, language.name)
    }

    private fun String?.asTheme() = runCatching { AppThemePreference.valueOf(this.orEmpty()) }
        .getOrDefault(AppThemePreference.SYSTEM)
    private fun String?.asLanguage() = runCatching { AppLanguage.valueOf(this.orEmpty()) }
        .getOrDefault(AppLanguage.SYSTEM)

    private companion object {
        const val THEME_KEY = "theme"
        const val LANGUAGE_KEY = "language"
    }
}

class InMemoryAppPreferences : AppPreferences {
    private val mutableState = MutableStateFlow(AppPreferenceState())
    override val state: Flow<AppPreferenceState> = mutableState
    override suspend fun setTheme(theme: AppThemePreference) = mutableState.update { it.copy(theme = theme) }
    override suspend fun setLanguage(language: AppLanguage) = mutableState.update { it.copy(language = language) }
}
