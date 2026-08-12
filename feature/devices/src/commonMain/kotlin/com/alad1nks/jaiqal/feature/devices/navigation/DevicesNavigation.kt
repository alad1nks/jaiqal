package com.alad1nks.jaiqal.feature.devices.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.alad1nks.jaiqal.feature.devices.presentation.CalibrationScreen
import com.alad1nks.jaiqal.feature.devices.presentation.ClaimDeviceScreen
import com.alad1nks.jaiqal.feature.devices.presentation.DeviceDetailsScreen
import kotlinx.serialization.Serializable

@Serializable data class ClaimDeviceRoute(val plantId: String? = null)
@Serializable data class DeviceDetailsRoute(val deviceId: String)
@Serializable data class CalibrationRoute(val deviceId: String)

fun NavController.navigateToClaimDevice(plantId: String? = null) = navigate(ClaimDeviceRoute(plantId))
fun NavController.navigateToDevice(deviceId: String) = navigate(DeviceDetailsRoute(deviceId))
fun NavController.navigateToCalibration(deviceId: String) = navigate(CalibrationRoute(deviceId))

fun NavGraphBuilder.deviceScreens(navController: NavController) {
    composable<ClaimDeviceRoute> { entry ->
        val route = entry.toRoute<ClaimDeviceRoute>()
        ClaimDeviceScreen(
            initialPlantId = route.plantId,
            onBack = navController::popBackStack,
            onClaimed = { deviceId ->
                navController.navigate(DeviceDetailsRoute(deviceId)) {
                    popUpTo<ClaimDeviceRoute> { inclusive = true }
                }
            },
        )
    }
    composable<DeviceDetailsRoute> { entry ->
        val route = entry.toRoute<DeviceDetailsRoute>()
        DeviceDetailsScreen(
            deviceId = route.deviceId,
            onBack = navController::popBackStack,
            onCalibrate = { navController.navigateToCalibration(route.deviceId) },
        )
    }
    composable<CalibrationRoute> { entry ->
        val route = entry.toRoute<CalibrationRoute>()
        CalibrationScreen(
            deviceId = route.deviceId,
            onCancel = navController::popBackStack,
            onSaved = navController::popBackStack,
        )
    }
}
