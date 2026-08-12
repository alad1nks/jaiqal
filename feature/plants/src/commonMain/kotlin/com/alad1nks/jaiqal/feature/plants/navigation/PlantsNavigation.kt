package com.alad1nks.jaiqal.feature.plants.navigation

import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.alad1nks.jaiqal.feature.plants.presentation.CreatePlantScreen
import com.alad1nks.jaiqal.feature.plants.presentation.EditPlantScreen
import com.alad1nks.jaiqal.feature.plants.presentation.PlantDetailsScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object PlantsRoute
@Serializable data class PlantDetailsRoute(val plantId: String)
@Serializable data object CreatePlantRoute
@Serializable data class EditPlantRoute(val plantId: String)

fun NavController.navigateToPlantDetails(plantId: String) = navigate(PlantDetailsRoute(plantId))
fun NavController.navigateToCreatePlant() = navigate(CreatePlantRoute)

fun NavGraphBuilder.plantDetailScreens(
    navController: NavController,
    onFeatureUnavailable: suspend () -> Unit,
) {
    composable<PlantDetailsRoute>(
        deepLinks = listOf(navDeepLink<PlantDetailsRoute>(basePath = "jaiqal://plants")),
    ) { entry ->
        val route = entry.toRoute<PlantDetailsRoute>()
        val scope = rememberCoroutineScope()
        PlantDetailsScreen(
            plantId = route.plantId,
            onBack = navController::popBackStack,
            onEdit = { navController.navigate(EditPlantRoute(route.plantId)) },
            onClaimDevice = { scope.launch { onFeatureUnavailable() } },
            onCalibrate = { scope.launch { onFeatureUnavailable() } },
        )
    }
    composable<CreatePlantRoute> {
        CreatePlantScreen(
            onBack = navController::popBackStack,
            onSaved = { plantId ->
                navController.navigate(PlantDetailsRoute(plantId)) {
                    popUpTo<CreatePlantRoute> { inclusive = true }
                }
            },
        )
    }
    composable<EditPlantRoute> { entry ->
        val route = entry.toRoute<EditPlantRoute>()
        EditPlantScreen(
            plantId = route.plantId,
            onBack = navController::popBackStack,
            onSaved = { navController.popBackStack() },
        )
    }
}
