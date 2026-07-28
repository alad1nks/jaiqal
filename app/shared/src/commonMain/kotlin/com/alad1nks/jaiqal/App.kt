package com.alad1nks.jaiqal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.alad1nks.jaiqal.app.JaiqalApp
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.DefaultBackendConfig
import com.alad1nks.jaiqal.di.appModule
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration

@Composable
fun App(
    backendBaseUrl: String = getPlatform().localBackendBaseUrl,
    environmentName: String = "local",
) {
    val backendConfig = remember(environmentName, backendBaseUrl) {
        DefaultBackendConfig(
            environment = AppEnvironment.from(environmentName),
            baseUrl = backendBaseUrl,
        )
    }
    val dependencyConfiguration = remember(backendConfig) {
        koinConfiguration { modules(appModule(backendConfig)) }
    }
    KoinApplication(
        configuration = dependencyConfiguration,
    ) {
        JaiqalApp(backendConfig)
    }
}

@Preview
@Composable
private fun AppPreview() {
    App()
}
