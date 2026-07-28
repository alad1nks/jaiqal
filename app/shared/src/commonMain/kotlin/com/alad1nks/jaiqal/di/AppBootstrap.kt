package com.alad1nks.jaiqal.di

import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.CurrentUserGateway
import com.alad1nks.jaiqal.core.auth.UnavailableAuthProvider
import com.alad1nks.jaiqal.core.auth.UnavailableCurrentUserGateway
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.BackendConfig
import com.alad1nks.jaiqal.core.network.DefaultBackendConfig
import org.koin.dsl.KoinConfiguration
import org.koin.dsl.koinConfiguration

class AppBootstrap internal constructor(
    internal val dependencyConfiguration: KoinConfiguration,
)

fun createAppConfiguration(
    backendConfig: BackendConfig,
    authProvider: AuthProvider,
    currentUserGateway: CurrentUserGateway,
): AppBootstrap = AppBootstrap(
    koinConfiguration {
        modules(appModule(backendConfig, authProvider, currentUserGateway))
    },
)

fun createUnavailableAppConfiguration(
    backendBaseUrl: String,
    environmentName: String,
): AppBootstrap = createAppConfiguration(
    backendConfig = DefaultBackendConfig(AppEnvironment.from(environmentName), backendBaseUrl),
    authProvider = UnavailableAuthProvider(),
    currentUserGateway = UnavailableCurrentUserGateway(),
)
