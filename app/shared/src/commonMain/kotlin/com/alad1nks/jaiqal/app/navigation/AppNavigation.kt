package com.alad1nks.jaiqal.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.alad1nks.jaiqal.app.JaiqalAppState
import com.alad1nks.jaiqal.app.SessionState
import com.alad1nks.jaiqal.core.designsystem.component.ErrorState
import com.alad1nks.jaiqal.core.designsystem.component.LoadingState
import com.alad1nks.jaiqal.core.designsystem.theme.ThemeMode
import com.alad1nks.jaiqal.feature.alerts.navigation.AlertsRoute
import com.alad1nks.jaiqal.feature.alerts.navigation.alertRuleScreens
import com.alad1nks.jaiqal.feature.alerts.navigation.navigateToAlertRules
import com.alad1nks.jaiqal.feature.alerts.presentation.AlertsScreen
import com.alad1nks.jaiqal.feature.auth.navigation.authGraph
import com.alad1nks.jaiqal.feature.devices.navigation.deviceScreens
import com.alad1nks.jaiqal.feature.devices.navigation.navigateToCalibration
import com.alad1nks.jaiqal.feature.devices.navigation.navigateToClaimDevice
import com.alad1nks.jaiqal.feature.devices.navigation.navigateToDevice
import com.alad1nks.jaiqal.feature.plants.navigation.PlantsRoute
import com.alad1nks.jaiqal.feature.plants.navigation.navigateToCreatePlant
import com.alad1nks.jaiqal.feature.plants.navigation.navigateToPlantDetails
import com.alad1nks.jaiqal.feature.plants.navigation.plantDetailScreens
import com.alad1nks.jaiqal.feature.plants.presentation.PlantsScreen
import com.alad1nks.jaiqal.feature.settings.navigation.SettingsRoute
import com.alad1nks.jaiqal.feature.settings.presentation.SettingsPlaceholderScreen
import jaiqal.resources.generated.resources.Res
import jaiqal.resources.generated.resources.loading
import jaiqal.resources.generated.resources.backend_auth_error_message
import jaiqal.resources.generated.resources.backend_auth_error_title
import jaiqal.resources.generated.resources.retry
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
        authGraph(appState.navController)
        mainGraph(appState, themeMode, onThemeSelected)
        plantDetailScreens(
            navController = appState.navController,
            onClaimDevice = appState.navController::navigateToClaimDevice,
            onDeviceDetails = appState.navController::navigateToDevice,
            onCalibrate = appState.navController::navigateToCalibration,
        )
        deviceScreens(appState.navController)
        alertRuleScreens(appState.navController)
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
                Box(Modifier.withMainContentPadding(padding)) {
                    PlantsScreen(
                        onOpenPlant = appState.navController::navigateToPlantDetails,
                        onCreatePlant = appState.navController::navigateToCreatePlant,
                        onClaimDevice = { appState.navController.navigateToClaimDevice() },
                    )
                }
            }
        }
        composable<AlertsRoute> {
            MainScaffold(MainSection.ALERTS, ::navigate) { padding ->
                Box(Modifier.withMainContentPadding(padding)) {
                    AlertsScreen(onOpenRules = appState.navController::navigateToAlertRules)
                }
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
