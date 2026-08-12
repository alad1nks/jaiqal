package com.alad1nks.jaiqal.core.preferences

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.alad1nks.jaiqal.core.database.JaiqalDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class AppPreferencesTest {
    @Test
    fun themeAndLanguageSurviveRepositoryRecreation() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            JaiqalDatabase.Schema.create(driver)
            SqlDelightAppPreferences(JaiqalDatabase(driver)).apply {
                setTheme(AppThemePreference.DARK)
                setLanguage(AppLanguage.KAZAKH)
            }

            assertEquals(
                AppPreferenceState(AppThemePreference.DARK, AppLanguage.KAZAKH),
                SqlDelightAppPreferences(JaiqalDatabase(driver)).state.first(),
            )
        } finally {
            driver.close()
        }
    }
}
