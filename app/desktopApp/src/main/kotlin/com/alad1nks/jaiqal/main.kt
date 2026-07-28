package com.alad1nks.jaiqal

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.alad1nks.jaiqal.di.createUnavailableAppConfiguration

fun main() {
    val configuration = createUnavailableAppConfiguration("http://127.0.0.1:8080", "local")
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Jaiqal",
        ) {
            App(configuration)
        }
    }
}
