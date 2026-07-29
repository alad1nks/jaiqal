package com.alad1nks.jaiqal.di

import com.alad1nks.jaiqal.app.AppViewModel
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.ApiCurrentUserGateway
import com.alad1nks.jaiqal.core.auth.CurrentUserGateway
import com.alad1nks.jaiqal.core.auth.UnavailableCurrentUserGateway
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.network.ApiClient
import com.alad1nks.jaiqal.core.network.AuthenticatedRequestExecutor
import com.alad1nks.jaiqal.core.network.BackendConfig
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.FirebaseAuthenticatedRequestExecutor
import com.alad1nks.jaiqal.core.network.KtorApiClient
import com.alad1nks.jaiqal.core.network.SessionErrorStore
import com.alad1nks.jaiqal.feature.auth.presentation.AuthViewModel
import com.alad1nks.jaiqal.feature.auth.presentation.VerifyEmailViewModel
import com.alad1nks.jaiqal.feature.settings.presentation.SettingsViewModel
import org.koin.core.KoinApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import io.ktor.client.HttpClient

fun appModule(
    backendConfig: BackendConfig,
    authProvider: AuthProvider,
    httpClient: HttpClient?,
) = module {
    single<BackendConfig> { backendConfig }
    single<AppEnvironment> { backendConfig.environment }
    single<AuthProvider> { authProvider }
    single { SessionErrorStore() }
    if (httpClient != null) {
        single { httpClient }
        single<AuthenticatedRequestExecutor> { FirebaseAuthenticatedRequestExecutor(get(), get()) }
        single<ApiClient> { KtorApiClient(get(), get(), get()) }
        single<CurrentUserGateway> { ApiCurrentUserGateway(get()) }
    } else {
        single<CurrentUserGateway> { UnavailableCurrentUserGateway() }
    }
    single { UserSessionStore() }
    viewModel { AppViewModel(get(), get(), get(), get()) }
    viewModel { AuthViewModel(get()) }
    viewModel { VerifyEmailViewModel(get()) }
    viewModel { SettingsViewModel(get(), get(), get()) }
}

fun createKoinApplication(
    backendConfig: BackendConfig,
    authProvider: AuthProvider,
    httpClient: HttpClient? = null,
): KoinApplication = koinApplication {
    modules(appModule(backendConfig, authProvider, httpClient))
}
