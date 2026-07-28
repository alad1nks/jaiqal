package com.alad1nks.jaiqal

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.alad1nks.jaiqal.app.JaiqalApp
import com.alad1nks.jaiqal.di.AppBootstrap
import com.alad1nks.jaiqal.di.createUnavailableAppConfiguration
import org.koin.compose.KoinApplication

@Composable
fun App(bootstrap: AppBootstrap) {
    KoinApplication(configuration = bootstrap.dependencyConfiguration) {
        JaiqalApp()
    }
}

@Preview
@Composable
private fun AppPreview() {
    App(createUnavailableAppConfiguration("http://localhost:8080", "local"))
}
