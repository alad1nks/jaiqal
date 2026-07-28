package com.alad1nks.jaiqal.app

import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alad1nks.jaiqal.app.navigation.AuthGraph
import com.alad1nks.jaiqal.app.navigation.JaiqalNavHost
import com.alad1nks.jaiqal.app.navigation.MainGraph
import com.alad1nks.jaiqal.app.navigation.SplashRoute
import com.alad1nks.jaiqal.app.navigation.VerifyEmailRoute
import com.alad1nks.jaiqal.core.designsystem.theme.JaiqalTheme
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun JaiqalApp(
    appViewModel: AppViewModel = koinViewModel(),
) {
    val uiState by appViewModel.state.collectAsStateWithLifecycle()
    val appState = rememberJaiqalAppState()

    LaunchedEffect(uiState.session) {
        val target = when (uiState.session) {
            SessionState.LOADING -> SplashRoute
            SessionState.UNAUTHENTICATED -> AuthGraph
            SessionState.EMAIL_VERIFICATION_REQUIRED -> VerifyEmailRoute
            SessionState.AUTHENTICATING_BACKEND -> SplashRoute
            SessionState.AUTHENTICATED -> MainGraph
            SessionState.ERROR -> SplashRoute
        }
        appState.navController.navigate(target) {
            popUpTo(appState.navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    JaiqalTheme(themeMode = uiState.themeMode) {
        Scaffold(snackbarHost = { SnackbarHost(appState.snackbarHostState) }) { contentPadding ->
            JaiqalNavHost(
                appState = appState,
                contentPadding = contentPadding,
                themeMode = uiState.themeMode,
                onThemeSelected = appViewModel::setTheme,
                sessionState = uiState.session,
                onRetrySession = appViewModel::retrySession,
            )
        }
    }
}
