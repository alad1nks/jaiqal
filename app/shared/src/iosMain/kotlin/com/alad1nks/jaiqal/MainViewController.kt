package com.alad1nks.jaiqal

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController(
    backendBaseUrl: String,
    environmentName: String,
) = ComposeUIViewController {
    App(backendBaseUrl, environmentName)
}
