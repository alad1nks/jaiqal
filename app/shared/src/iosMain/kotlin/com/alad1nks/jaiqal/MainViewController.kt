package com.alad1nks.jaiqal

import androidx.compose.ui.window.ComposeUIViewController
import com.alad1nks.jaiqal.core.auth.IosFirebaseAuthBridge
import com.alad1nks.jaiqal.core.auth.IosFirebaseAuthProvider
import com.alad1nks.jaiqal.core.auth.UnavailableAuthProvider
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.DefaultBackendConfig
import com.alad1nks.jaiqal.core.network.NetworkLogger
import com.alad1nks.jaiqal.core.network.createApiHttpClient
import com.alad1nks.jaiqal.core.database.IosDatabaseDriverFactory
import com.alad1nks.jaiqal.core.config.AppInfo
import com.alad1nks.jaiqal.di.createAppConfiguration
import io.ktor.client.engine.darwin.Darwin

fun MainViewController(
    backendBaseUrl: String,
    environmentName: String,
    firebaseAuthBridge: IosFirebaseAuthBridge?,
    crashReporterBridge: IosCrashReporterBridge?,
    enableNetworkLogging: Boolean,
    appVersion: String,
    isDebug: Boolean,
    privacyPolicyUrl: String?,
): platform.UIKit.UIViewController {
    val backendConfig = DefaultBackendConfig(AppEnvironment.from(environmentName), backendBaseUrl)
    val authProvider = firebaseAuthBridge?.let(::IosFirebaseAuthProvider) ?: UnavailableAuthProvider()
    val httpClient = createApiHttpClient(
        engine = Darwin,
        enableDebugLogging = enableNetworkLogging,
        networkLogger = NetworkLogger(::println),
    )
    val configuration = createAppConfiguration(
        backendConfig,
        authProvider,
        httpClient,
        IosDatabaseDriverFactory(),
        AppInfo(appVersion, getPlatform().name, isDebug, privacyPolicyUrl),
        createIosCrashReporter(crashReporterBridge),
    )
    return ComposeUIViewController { App(configuration) }
}
