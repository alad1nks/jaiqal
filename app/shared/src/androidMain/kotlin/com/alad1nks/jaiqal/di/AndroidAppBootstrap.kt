package com.alad1nks.jaiqal.di

import android.content.Context
import com.alad1nks.jaiqal.core.auth.AndroidFirebaseAuthProvider
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.KtorCurrentUserGateway
import com.alad1nks.jaiqal.core.auth.UnavailableAuthProvider
import com.alad1nks.jaiqal.core.auth.createAuthHttpClient
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.DefaultBackendConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import io.ktor.client.engine.okhttp.OkHttp

fun createAndroidAppConfiguration(
    context: Context,
    backendBaseUrl: String,
    environmentName: String,
): AppBootstrap {
    val backendConfig = DefaultBackendConfig(AppEnvironment.from(environmentName), backendBaseUrl)
    val authProvider = createAndroidAuthProvider(context)
    val currentUserGateway = KtorCurrentUserGateway(createAuthHttpClient(OkHttp), backendConfig)
    return createAppConfiguration(backendConfig, authProvider, currentUserGateway)
}

private fun createAndroidAuthProvider(context: Context): AuthProvider {
    val initialized = runCatching {
        FirebaseApp.initializeApp(context) ?: FirebaseApp.getApps(context).firstOrNull()
    }.getOrNull() ?: return UnavailableAuthProvider()
    return AndroidFirebaseAuthProvider(FirebaseAuth.getInstance(initialized))
}
