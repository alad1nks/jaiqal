package com.alad1nks.jaiqal.app

import com.alad1nks.jaiqal.core.designsystem.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class AppViewModelTest {
    @Test
    fun startupAndThemeStateAreExplicit() {
        val viewModel = AppViewModel()
        assertEquals(SessionState.LOADING, viewModel.state.value.session)

        viewModel.finishStartup(authenticated = false)
        viewModel.setTheme(ThemeMode.DARK)

        assertEquals(SessionState.UNAUTHENTICATED, viewModel.state.value.session)
        assertEquals(ThemeMode.DARK, viewModel.state.value.themeMode)
    }
}
