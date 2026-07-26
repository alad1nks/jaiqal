package com.alad1nks.jaiqal

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.alad1nks.jaiqal.app.navigation.Destination
import com.alad1nks.jaiqal.core.designsystem.JaiqalTheme
import com.alad1nks.jaiqal.presentation.*
import jaiqal.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Shared root used unchanged by Android, iOS, desktop and web entry points. */
@Composable fun App(darkTheme:Boolean=false) { JaiqalTheme(darkTheme) { val nav=rememberNavController();NavHost(nav,Destination.SignIn){
 composable<Destination.SignIn>{AuthScreen(false,{_,_->nav.navigate(Destination.Plants){popUpTo(Destination.SignIn){inclusive=true}}},{nav.navigate(Destination.SignUp)})}
 composable<Destination.SignUp>{AuthScreen(true,{_,_->nav.navigate(Destination.Plants){popUpTo(Destination.SignIn){inclusive=true}}},{nav.popBackStack()})}
 composable<Destination.Plants>{PlantsScreen({nav.navigate(Destination.AddPlant)},{nav.navigate(Destination.PlantDetails(it))})}
 composable<Destination.AddPlant>{FormScreen(stringResource(Res.string.add_plant)){nav.popBackStack()}}
 composable<Destination.EditPlant>{FormScreen(stringResource(Res.string.save)){nav.popBackStack()}}
 composable<Destination.PlantDetails>{entry->val route=entry.toRoute<Destination.PlantDetails>();PlantDetailsScreen(route.plantId){nav.navigate(Destination.DeviceCalibration(route.plantId))}}
 composable<Destination.ClaimDevice>{FormScreen(stringResource(Res.string.claim_device)){nav.popBackStack()}}
 composable<Destination.DeviceCalibration>{SimpleScreen(stringResource(Res.string.calibrate))}
 composable<Destination.Alerts>{SimpleScreen(stringResource(Res.string.alerts))}
 composable<Destination.AlertRules>{SimpleScreen(stringResource(Res.string.alerts))}
 composable<Destination.Settings>{SimpleScreen(stringResource(Res.string.settings))}
 composable<Destination.Splash>{Text(stringResource(Res.string.loading))}
 } } }
