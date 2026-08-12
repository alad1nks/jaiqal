package com.alad1nks.jaiqal.di

import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.UnavailableAuthProvider
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.BackendConfig
import com.alad1nks.jaiqal.core.network.DefaultBackendConfig
import com.alad1nks.jaiqal.core.database.DatabaseDriverFactory
import com.alad1nks.jaiqal.core.config.AppInfo
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
    databaseDriverFactory: DatabaseDriverFactory?,
    appInfo: AppInfo = AppInfo("1.0", "Preview", true, null),
): AppBootstrap = AppBootstrap(
    koinConfiguration {
        modules(appModules(backendConfig, authProvider, httpClient, databaseDriverFactory, appInfo))
    },
)

fun createUnavailableAppConfiguration(
    backendBaseUrl: String,
    environmentName: String,
): AppBootstrap = createAppConfiguration(
    backendConfig = DefaultBackendConfig(AppEnvironment.from(environmentName), backendBaseUrl),
    authProvider = UnavailableAuthProvider(),
    httpClient = null,
    databaseDriverFactory = null,
    appInfo = AppInfo("1.0", "Preview", true, null),
)
