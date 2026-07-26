package com.alad1nks.jaiqal.app.navigation
import kotlinx.serialization.Serializable
sealed interface Destination {
 @Serializable data object Splash:Destination; @Serializable data object SignIn:Destination; @Serializable data object SignUp:Destination
 @Serializable data object Plants:Destination; @Serializable data object Alerts:Destination; @Serializable data object Settings:Destination
 @Serializable data class PlantDetails(val plantId:String):Destination; @Serializable data object AddPlant:Destination
 @Serializable data class EditPlant(val plantId:String):Destination; @Serializable data class ClaimDevice(val plantId:String?=null):Destination
 @Serializable data class DeviceCalibration(val deviceId:String):Destination; @Serializable data class AlertRules(val plantId:String):Destination
}
