package com.alad1nks.jaiqal.di

import com.alad1nks.jaiqal.app.AppViewModel
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.CurrentUserGateway
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.network.BackendConfig
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.feature.auth.presentation.AuthViewModel
import com.alad1nks.jaiqal.feature.auth.presentation.VerifyEmailViewModel
import com.alad1nks.jaiqal.feature.settings.presentation.SettingsViewModel
import org.koin.core.KoinApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module

fun appModule(
    backendConfig: BackendConfig,
    authProvider: AuthProvider,
    currentUserGateway: CurrentUserGateway,
) = module {
    single<BackendConfig> { backendConfig }
    single<AppEnvironment> { backendConfig.environment }
    single<AuthProvider> { authProvider }
    single<CurrentUserGateway> { currentUserGateway }
    single { UserSessionStore() }
    viewModel { AppViewModel(get(), get(), get()) }
    viewModel { AuthViewModel(get()) }
    viewModel { VerifyEmailViewModel(get()) }
    viewModel { SettingsViewModel(get(), get(), get()) }
}

fun createKoinApplication(
    backendConfig: BackendConfig,
    authProvider: AuthProvider,
    currentUserGateway: CurrentUserGateway,
): KoinApplication = koinApplication {
    modules(appModule(backendConfig, authProvider, currentUserGateway))
}
