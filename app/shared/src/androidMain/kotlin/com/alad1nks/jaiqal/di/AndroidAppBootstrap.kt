package com.alad1nks.jaiqal.di

import android.content.Context
import com.alad1nks.jaiqal.core.auth.AndroidFirebaseAuthProvider
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.UnavailableAuthProvider
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.DefaultBackendConfig
import com.alad1nks.jaiqal.core.network.NetworkLogger
import com.alad1nks.jaiqal.core.network.createApiHttpClient
import com.alad1nks.jaiqal.core.database.AndroidDatabaseDriverFactory
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import io.ktor.client.engine.okhttp.OkHttp
import android.util.Log

fun createAndroidAppConfiguration(
    context: Context,
    backendBaseUrl: String,
    environmentName: String,
    enableNetworkLogging: Boolean,
): AppBootstrap {
    val backendConfig = DefaultBackendConfig(AppEnvironment.from(environmentName), backendBaseUrl)
    val authProvider = createAndroidAuthProvider(context)
    val httpClient = createApiHttpClient(
        engine = OkHttp,
        enableDebugLogging = enableNetworkLogging,
        networkLogger = NetworkLogger { Log.d("JaiqalNetwork", it) },
    )
    return createAppConfiguration(
        backendConfig,
        authProvider,
        httpClient,
        AndroidDatabaseDriverFactory(context),
    )
}

private fun createAndroidAuthProvider(context: Context): AuthProvider {
    val initialized = runCatching {
        FirebaseApp.initializeApp(context) ?: FirebaseApp.getApps(context).firstOrNull()
    }.getOrNull() ?: return UnavailableAuthProvider()
    return AndroidFirebaseAuthProvider(FirebaseAuth.getInstance(initialized))
}
