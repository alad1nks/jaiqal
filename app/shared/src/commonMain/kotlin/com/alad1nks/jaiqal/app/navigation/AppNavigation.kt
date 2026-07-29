package com.alad1nks.jaiqal.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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
import com.alad1nks.jaiqal.feature.plants.presentation.CreatePlantScreen
import com.alad1nks.jaiqal.feature.plants.presentation.EditPlantScreen
import com.alad1nks.jaiqal.feature.plants.presentation.PlantDetailsScreen
import com.alad1nks.jaiqal.feature.plants.presentation.PlantsScreen
import com.alad1nks.jaiqal.feature.settings.presentation.SettingsPlaceholderScreen
import jaiqal.app.shared.generated.resources.Res
import jaiqal.app.shared.generated.resources.loading
import jaiqal.app.shared.generated.resources.backend_auth_error_message
import jaiqal.app.shared.generated.resources.backend_auth_error_title
import jaiqal.app.shared.generated.resources.feature_next_step
import jaiqal.app.shared.generated.resources.retry
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch

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
            val scope = rememberCoroutineScope()
            val unavailable = stringResource(Res.string.feature_next_step)
            PlantDetailsScreen(
                plantId = route.plantId,
                onBack = appState.navController::popBackStack,
                onEdit = { appState.navController.navigate(EditPlantRoute(route.plantId)) },
                onClaimDevice = { scope.launch { appState.snackbarHostState.showSnackbar(unavailable) } },
                onCalibrate = { scope.launch { appState.snackbarHostState.showSnackbar(unavailable) } },
            )
        }
        composable<CreatePlantRoute> {
            CreatePlantScreen(
                onBack = appState.navController::popBackStack,
                onSaved = { plantId ->
                    appState.navController.navigate(PlantDetailsRoute(plantId)) {
                        popUpTo<CreatePlantRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<EditPlantRoute> { entry ->
            val route = entry.toRoute<EditPlantRoute>()
            EditPlantScreen(
                plantId = route.plantId,
                onBack = appState.navController::popBackStack,
                onSaved = { appState.navController.popBackStack() },
            )
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
                val scope = rememberCoroutineScope()
                val unavailable = stringResource(Res.string.feature_next_step)
                Box(Modifier.withMainContentPadding(padding)) {
                    PlantsScreen(
                        onOpenPlant = { appState.navController.navigate(PlantDetailsRoute(it)) },
                        onCreatePlant = { appState.navController.navigate(CreatePlantRoute) },
                        onClaimDevice = { scope.launch { appState.snackbarHostState.showSnackbar(unavailable) } },
                    )
                }
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
