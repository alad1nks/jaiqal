package com.alad1nks.jaiqal.core.di

import com.alad1nks.jaiqal.core.auth.ApiCurrentUserGateway
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.CurrentUserGateway
import com.alad1nks.jaiqal.core.auth.UnavailableCurrentUserGateway
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.cache.NoOpOfflineCache
import com.alad1nks.jaiqal.core.cache.OfflineCache
import com.alad1nks.jaiqal.core.cache.SqlDelightOfflineCache
import com.alad1nks.jaiqal.core.cache.SyncCoordinator
import com.alad1nks.jaiqal.core.database.DatabaseDriverFactory
import com.alad1nks.jaiqal.core.database.JaiqalDatabase
import com.alad1nks.jaiqal.core.network.ApiClient
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.AuthenticatedRequestExecutor
import com.alad1nks.jaiqal.core.network.BackendConfig
import com.alad1nks.jaiqal.core.network.FirebaseAuthenticatedRequestExecutor
import com.alad1nks.jaiqal.core.network.KtorApiClient
import com.alad1nks.jaiqal.core.network.SessionErrorStore
import com.alad1nks.jaiqal.core.preferences.AppPreferences
import com.alad1nks.jaiqal.core.preferences.InMemoryAppPreferences
import com.alad1nks.jaiqal.core.preferences.SqlDelightAppPreferences
import com.alad1nks.jaiqal.core.push.PushTokenRegistrar
import com.alad1nks.jaiqal.core.push.UnavailablePushTokenRegistrar
import io.ktor.client.HttpClient
import org.koin.dsl.module

fun coreDataModule(
    backendConfig: BackendConfig,
    authProvider: AuthProvider,
    httpClient: HttpClient?,
    databaseDriverFactory: DatabaseDriverFactory?,
) = module {
    single<BackendConfig> { backendConfig }
    single<AppEnvironment> { backendConfig.environment }
    single<AuthProvider> { authProvider }
    single { SessionErrorStore() }
    single { SyncCoordinator() }
    single { UserSessionStore() }
    single<PushTokenRegistrar> { UnavailablePushTokenRegistrar() }

    if (databaseDriverFactory != null) {
        single { databaseDriverFactory.create() }
        single { JaiqalDatabase(get()) }
        single<OfflineCache> { SqlDelightOfflineCache(get()) }
        single<AppPreferences> { SqlDelightAppPreferences(get()) }
    } else {
        single<OfflineCache> { NoOpOfflineCache }
        single<AppPreferences> { InMemoryAppPreferences() }
    }

    if (httpClient != null) {
        single { httpClient }
        single<AuthenticatedRequestExecutor> { FirebaseAuthenticatedRequestExecutor(get(), get()) }
        single<ApiClient> { KtorApiClient(get(), get(), get()) }
        single<CurrentUserGateway> { ApiCurrentUserGateway(get()) }
    } else {
        single<CurrentUserGateway> { UnavailableCurrentUserGateway() }
    }
}
