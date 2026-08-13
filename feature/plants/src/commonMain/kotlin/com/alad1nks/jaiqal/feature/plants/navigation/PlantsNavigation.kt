package com.alad1nks.jaiqal.feature.plants.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.alad1nks.jaiqal.feature.plants.presentation.CreatePlantScreen
import com.alad1nks.jaiqal.feature.plants.presentation.EditPlantScreen
import com.alad1nks.jaiqal.feature.plants.presentation.PlantDetailsScreen
import kotlinx.serialization.Serializable

@Serializable data object PlantsRoute
@Serializable data class PlantDetailsRoute(val plantId: String)
@Serializable data object CreatePlantRoute
@Serializable data class EditPlantRoute(val plantId: String)

const val PLANT_DEEP_LINK_BASE = "jaiqal://plants"

fun plantDeepLink(plantId: String): String = "$PLANT_DEEP_LINK_BASE/$plantId"

fun NavController.navigateToPlantDetails(plantId: String) = navigate(PlantDetailsRoute(plantId))
fun NavController.navigateToCreatePlant() = navigate(CreatePlantRoute)

fun NavGraphBuilder.plantDetailScreens(
    navController: NavController,
    onClaimDevice: (String) -> Unit,
    onDeviceDetails: (String) -> Unit,
    onCalibrate: (String) -> Unit,
) {
    composable<PlantDetailsRoute>(
        deepLinks = listOf(navDeepLink<PlantDetailsRoute>(basePath = PLANT_DEEP_LINK_BASE)),
    ) { entry ->
        val route = entry.toRoute<PlantDetailsRoute>()
        PlantDetailsScreen(
            plantId = route.plantId,
            onBack = navController::popBackStack,
            onEdit = { navController.navigate(EditPlantRoute(route.plantId)) },
            onClaimDevice = { onClaimDevice(route.plantId) },
            onDeviceDetails = onDeviceDetails,
            onCalibrate = onCalibrate,
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
