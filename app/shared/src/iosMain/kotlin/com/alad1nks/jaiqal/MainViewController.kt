package com.alad1nks.jaiqal

import androidx.compose.ui.window.ComposeUIViewController
import com.alad1nks.jaiqal.core.auth.IosFirebaseAuthBridge
import com.alad1nks.jaiqal.core.auth.IosFirebaseAuthProvider
import com.alad1nks.jaiqal.core.auth.KtorCurrentUserGateway
import com.alad1nks.jaiqal.core.auth.UnavailableAuthProvider
import com.alad1nks.jaiqal.core.auth.createAuthHttpClient
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.DefaultBackendConfig
import com.alad1nks.jaiqal.di.createAppConfiguration
import io.ktor.client.engine.darwin.Darwin

fun MainViewController(
    backendBaseUrl: String,
    environmentName: String,
    firebaseAuthBridge: IosFirebaseAuthBridge?,
): platform.UIKit.UIViewController {
    val backendConfig = DefaultBackendConfig(AppEnvironment.from(environmentName), backendBaseUrl)
    val authProvider = firebaseAuthBridge?.let(::IosFirebaseAuthProvider) ?: UnavailableAuthProvider()
    val currentUserGateway = KtorCurrentUserGateway(createAuthHttpClient(Darwin), backendConfig)
    val configuration = createAppConfiguration(backendConfig, authProvider, currentUserGateway)
    return ComposeUIViewController { App(configuration) }
}
