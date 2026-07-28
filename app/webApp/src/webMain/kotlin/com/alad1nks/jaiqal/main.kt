package com.alad1nks.jaiqal

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.alad1nks.jaiqal.di.createUnavailableAppConfiguration

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val configuration = createUnavailableAppConfiguration("http://localhost:8080", "local")
    ComposeViewport {
        App(configuration)
    }
}
