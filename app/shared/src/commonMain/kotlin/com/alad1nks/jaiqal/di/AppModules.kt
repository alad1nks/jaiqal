package com.alad1nks.jaiqal.di

import com.alad1nks.jaiqal.app.AppViewModel
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.database.DatabaseDriverFactory
import com.alad1nks.jaiqal.core.config.AppInfo
import com.alad1nks.jaiqal.core.di.coreDataModule
import com.alad1nks.jaiqal.core.network.BackendConfig
import com.alad1nks.jaiqal.feature.auth.di.authModule
import com.alad1nks.jaiqal.feature.alerts.di.alertsModule
import com.alad1nks.jaiqal.feature.devices.di.devicesModule
import com.alad1nks.jaiqal.feature.plants.di.plantsModule
import com.alad1nks.jaiqal.feature.settings.di.settingsModule
import io.ktor.client.HttpClient
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module

private fun appModule(appInfo: AppInfo) = module {
    single { appInfo }
    viewModel { AppViewModel(get(), get(), get(), get(), get(), get()) }
}

internal fun appModules(
    backendConfig: BackendConfig,
    authProvider: AuthProvider,
    httpClient: HttpClient?,
    databaseDriverFactory: DatabaseDriverFactory?,
    appInfo: AppInfo,
): List<Module> = buildList {
    add(coreDataModule(backendConfig, authProvider, httpClient, databaseDriverFactory))
    add(appModule(appInfo))
    add(authModule)
    add(settingsModule)
    if (httpClient != null) {
        add(plantsModule)
        add(devicesModule)
        add(alertsModule)
    }
}

fun createKoinApplication(
    backendConfig: BackendConfig,
    authProvider: AuthProvider,
    httpClient: HttpClient? = null,
    databaseDriverFactory: DatabaseDriverFactory? = null,
    appInfo: AppInfo = AppInfo("1.0", "Test", true, null),
): KoinApplication = koinApplication {
    modules(appModules(backendConfig, authProvider, httpClient, databaseDriverFactory, appInfo))
}
