package com.alad1nks.jaiqal.di

import android.app.Activity
import android.content.Context
import com.alad1nks.jaiqal.core.auth.AndroidFirebaseAuthProvider
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.UnavailableAuthProvider
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.DefaultBackendConfig
import com.alad1nks.jaiqal.core.network.createApiHttpClient
import com.alad1nks.jaiqal.core.database.AndroidDatabaseDriverFactory
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import io.ktor.client.engine.okhttp.OkHttp
import android.util.Log
import com.alad1nks.jaiqal.AndroidAppContext
import com.alad1nks.jaiqal.core.config.AppInfo
import com.alad1nks.jaiqal.createAndroidCrashReporter

fun createAndroidAppConfiguration(
    activity: Activity,
    backendBaseUrl: String,
    environmentName: String,
    enableNetworkLogging: Boolean,
    appVersion: String,
    isDebug: Boolean,
    privacyPolicyUrl: String?,
): AppBootstrap {
    val context = activity.applicationContext
    AndroidAppContext.value = context.applicationContext
    val backendConfig = DefaultBackendConfig(AppEnvironment.from(environmentName), backendBaseUrl)
    val authProvider = createAndroidAuthProvider(activity)
    val firebaseConfigured = FirebaseApp.getApps(context).isNotEmpty()
    val httpClient = createApiHttpClient(
        engine = OkHttp,
        enableDebugLogging = enableNetworkLogging,
        networkLogger = { Log.d("JaiqalNetwork", it) },
    )
    return createAppConfiguration(
        backendConfig,
        authProvider,
        httpClient,
        AndroidDatabaseDriverFactory(context),
        AppInfo(appVersion, "Android ${android.os.Build.VERSION.RELEASE}", isDebug, privacyPolicyUrl),
        createAndroidCrashReporter(firebaseConfigured),
    )
}

private fun createAndroidAuthProvider(activity: Activity): AuthProvider {
    val context = activity.applicationContext
    val initialized = runCatching {
        FirebaseApp.initializeApp(context) ?: FirebaseApp.getApps(context).firstOrNull()
    }.getOrNull() ?: return UnavailableAuthProvider()
    return AndroidFirebaseAuthProvider(
        firebaseAuth = FirebaseAuth.getInstance(initialized),
        context = context,
        activity = activity,
        googleServerClientId = context.firebaseStringResource("default_web_client_id"),
    )
}

@Suppress("DiscouragedApi")
private fun Context.firebaseStringResource(name: String): String? {
    val resourceId = resources.getIdentifier(name, "string", packageName)
    if (resourceId == 0) return null
    return runCatching { resources.getString(resourceId) }.getOrNull()?.takeIf(String::isNotBlank)
}
