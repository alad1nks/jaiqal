package com.alad1nks.jaiqal.app.navigation

import kotlinx.serialization.Serializable

@Serializable data object SplashRoute
@Serializable data object AuthGraph
@Serializable data object LoginRoute
@Serializable data object RegisterRoute
@Serializable data object ForgotPasswordRoute
@Serializable data object VerifyEmailRoute
@Serializable data object MainGraph
@Serializable data object PlantsRoute
@Serializable data object AlertsRoute
@Serializable data object SettingsRoute
@Serializable data class PlantDetailsRoute(val plantId: String)
