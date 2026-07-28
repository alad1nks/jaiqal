package com.alad1nks.jaiqal.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.navigation
import androidx.navigation.toRoute
import com.alad1nks.jaiqal.app.JaiqalAppState
import com.alad1nks.jaiqal.app.SessionState
import com.alad1nks.jaiqal.core.designsystem.component.ErrorState
import com.alad1nks.jaiqal.core.designsystem.component.LoadingState
import com.alad1nks.jaiqal.core.designsystem.theme.ThemeMode
import com.alad1nks.jaiqal.feature.alerts.presentation.AlertsPlaceholderScreen
import com.alad1nks.jaiqal.feature.auth.presentation.ForgotPasswordScreen
import com.alad1nks.jaiqal.feature.auth.presentation.LoginScreen
import com.alad1nks.jaiqal.feature.auth.presentation.RegisterScreen
import com.alad1nks.jaiqal.feature.auth.presentation.VerifyEmailScreen
import com.alad1nks.jaiqal.feature.plants.presentation.PlantsPlaceholderScreen
import com.alad1nks.jaiqal.feature.settings.presentation.SettingsPlaceholderScreen
import jaiqal.app.shared.generated.resources.Res
import jaiqal.app.shared.generated.resources.loading
import jaiqal.app.shared.generated.resources.backend_auth_error_message
import jaiqal.app.shared.generated.resources.backend_auth_error_title
import jaiqal.app.shared.generated.resources.plant_details_placeholder
import jaiqal.app.shared.generated.resources.retry
import org.jetbrains.compose.resources.stringResource

@Composable
fun JaiqalNavHost(
    appState: JaiqalAppState,
    contentPadding: PaddingValues,
    themeMode: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    sessionState: SessionState,
    onRetrySession: () -> Unit,
) {
    NavHost(
        navController = appState.navController,
        startDestination = SplashRoute,
        modifier = Modifier.padding(contentPadding),
    ) {
        composable<SplashRoute> {
            if (sessionState == SessionState.ERROR) {
                ErrorState(
                    title = stringResource(Res.string.backend_auth_error_title),
                    message = stringResource(Res.string.backend_auth_error_message),
                    retryLabel = stringResource(Res.string.retry),
                    onRetry = onRetrySession,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LoadingState(stringResource(Res.string.loading), Modifier.fillMaxSize())
            }
        }
        authGraph(appState)
        mainGraph(appState, themeMode, onThemeSelected)
        composable<PlantDetailsRoute>(
            deepLinks = listOf(navDeepLink<PlantDetailsRoute>(basePath = "jaiqal://plants")),
        ) { entry ->
            val route = entry.toRoute<PlantDetailsRoute>()
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.plant_details_placeholder, route.plantId),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

private fun NavGraphBuilder.authGraph(appState: JaiqalAppState) {
    navigation<AuthGraph>(startDestination = LoginRoute) {
        composable<LoginRoute> {
            LoginScreen(
                onRegister = { appState.navController.navigate(RegisterRoute) },
                onForgotPassword = { appState.navController.navigate(ForgotPasswordRoute) },
            )
        }
        composable<RegisterRoute> { RegisterScreen(appState.navController::popBackStack) }
        composable<ForgotPasswordRoute> { ForgotPasswordScreen(appState.navController::popBackStack) }
        composable<VerifyEmailRoute> { VerifyEmailScreen() }
    }
}

private fun NavGraphBuilder.mainGraph(
    appState: JaiqalAppState,
    themeMode: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
) {
    fun navigate(section: MainSection) {
        val route = when (section) {
            MainSection.PLANTS -> PlantsRoute
            MainSection.ALERTS -> AlertsRoute
            MainSection.SETTINGS -> SettingsRoute
        }
        appState.navController.navigate(route) {
            popUpTo<MainGraph> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    navigation<MainGraph>(startDestination = PlantsRoute) {
        composable<PlantsRoute> {
            MainScaffold(MainSection.PLANTS, ::navigate) { padding ->
                Box(Modifier.withMainContentPadding(padding)) { PlantsPlaceholderScreen() }
            }
        }
        composable<AlertsRoute> {
            MainScaffold(MainSection.ALERTS, ::navigate) { padding ->
                Box(Modifier.withMainContentPadding(padding)) { AlertsPlaceholderScreen() }
            }
        }
        composable<SettingsRoute> {
            MainScaffold(MainSection.SETTINGS, ::navigate) { padding ->
                Box(Modifier.withMainContentPadding(padding)) {
                    SettingsPlaceholderScreen(themeMode, onThemeSelected)
                }
            }
        }
    }
}
