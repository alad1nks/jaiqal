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
import com.alad1nks.jaiqal.core.designsystem.component.LoadingState
import com.alad1nks.jaiqal.core.designsystem.theme.ThemeMode
import com.alad1nks.jaiqal.core.network.BackendConfig
import com.alad1nks.jaiqal.feature.alerts.presentation.AlertsPlaceholderScreen
import com.alad1nks.jaiqal.feature.auth.presentation.ForgotPasswordPlaceholderScreen
import com.alad1nks.jaiqal.feature.auth.presentation.LoginPlaceholderScreen
import com.alad1nks.jaiqal.feature.auth.presentation.RegisterPlaceholderScreen
import com.alad1nks.jaiqal.feature.plants.presentation.PlantsPlaceholderScreen
import com.alad1nks.jaiqal.feature.settings.presentation.SettingsPlaceholderScreen
import jaiqal.app.shared.generated.resources.Res
import jaiqal.app.shared.generated.resources.loading
import jaiqal.app.shared.generated.resources.plant_details_placeholder
import org.jetbrains.compose.resources.stringResource

@Composable
fun JaiqalNavHost(
    appState: JaiqalAppState,
    contentPadding: PaddingValues,
    backendConfig: BackendConfig,
    themeMode: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
) {
    NavHost(
        navController = appState.navController,
        startDestination = SplashRoute,
        modifier = Modifier.padding(contentPadding),
    ) {
        composable<SplashRoute> {
            LoadingState(stringResource(Res.string.loading), Modifier.fillMaxSize())
        }
        authGraph(appState)
        mainGraph(appState, backendConfig, themeMode, onThemeSelected)
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
            LoginPlaceholderScreen(
                onRegister = { appState.navController.navigate(RegisterRoute) },
                onForgotPassword = { appState.navController.navigate(ForgotPasswordRoute) },
            )
        }
        composable<RegisterRoute> { RegisterPlaceholderScreen(appState.navController::popBackStack) }
        composable<ForgotPasswordRoute> { ForgotPasswordPlaceholderScreen(appState.navController::popBackStack) }
    }
}

private fun NavGraphBuilder.mainGraph(
    appState: JaiqalAppState,
    backendConfig: BackendConfig,
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
                    SettingsPlaceholderScreen(backendConfig, themeMode, onThemeSelected)
                }
            }
        }
    }
}
