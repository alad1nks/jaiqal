package com.alad1nks.jaiqal.di

import com.alad1nks.jaiqal.app.AppViewModel
import com.alad1nks.jaiqal.core.network.BackendConfig
import org.koin.core.KoinApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module

fun appModule(backendConfig: BackendConfig) = module {
    single<BackendConfig> { backendConfig }
    viewModel { AppViewModel() }
}

fun createKoinApplication(backendConfig: BackendConfig): KoinApplication = koinApplication {
    modules(appModule(backendConfig))
}
