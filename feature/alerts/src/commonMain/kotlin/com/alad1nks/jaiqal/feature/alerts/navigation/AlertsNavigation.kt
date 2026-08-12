package com.alad1nks.jaiqal.feature.alerts.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.alad1nks.jaiqal.feature.alerts.presentation.AlertRulesScreen
import kotlinx.serialization.Serializable

@Serializable data object AlertsRoute
@Serializable data class AlertRulesRoute(val plantId: String? = null)

fun NavController.navigateToAlertRules(plantId: String? = null) = navigate(AlertRulesRoute(plantId))

fun NavGraphBuilder.alertRuleScreens(navController: NavController) {
    composable<AlertRulesRoute> { entry ->
        AlertRulesScreen(
            initialPlantId = entry.toRoute<AlertRulesRoute>().plantId,
            onBack = navController::popBackStack,
        )
    }
}
