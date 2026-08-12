package com.alad1nks.jaiqal.feature.auth.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.alad1nks.jaiqal.feature.auth.presentation.ForgotPasswordScreen
import com.alad1nks.jaiqal.feature.auth.presentation.LoginScreen
import com.alad1nks.jaiqal.feature.auth.presentation.RegisterScreen
import com.alad1nks.jaiqal.feature.auth.presentation.VerifyEmailScreen
import kotlinx.serialization.Serializable

@Serializable data object AuthGraph
@Serializable data object LoginRoute
@Serializable data object RegisterRoute
@Serializable data object ForgotPasswordRoute
@Serializable data object VerifyEmailRoute

fun NavGraphBuilder.authGraph(navController: NavController) {
    navigation<AuthGraph>(startDestination = LoginRoute) {
        composable<LoginRoute> {
            LoginScreen(
                onRegister = { navController.navigate(RegisterRoute) },
                onForgotPassword = { navController.navigate(ForgotPasswordRoute) },
            )
        }
        composable<RegisterRoute> { RegisterScreen(navController::popBackStack) }
        composable<ForgotPasswordRoute> { ForgotPasswordScreen(navController::popBackStack) }
        composable<VerifyEmailRoute> { VerifyEmailScreen() }
    }
}
