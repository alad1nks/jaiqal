package com.alad1nks.jaiqal.di

import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.UnavailableAuthProvider
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.BackendConfig
import com.alad1nks.jaiqal.core.network.DefaultBackendConfig
import io.ktor.client.HttpClient
import org.koin.dsl.KoinConfiguration
import org.koin.dsl.koinConfiguration

class AppBootstrap internal constructor(
    internal val dependencyConfiguration: KoinConfiguration,
)

fun createAppConfiguration(
    backendConfig: BackendConfig,
    authProvider: AuthProvider,
    httpClient: HttpClient?,
): AppBootstrap = AppBootstrap(
    koinConfiguration {
        modules(appModule(backendConfig, authProvider, httpClient))
    },
)

fun createUnavailableAppConfiguration(
    backendBaseUrl: String,
    environmentName: String,
): AppBootstrap = createAppConfiguration(
    backendConfig = DefaultBackendConfig(AppEnvironment.from(environmentName), backendBaseUrl),
    authProvider = UnavailableAuthProvider(),
    httpClient = null,
)
