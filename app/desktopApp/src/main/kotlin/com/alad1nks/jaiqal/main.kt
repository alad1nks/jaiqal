package com.alad1nks.jaiqal

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Jaiqal",
    ) {
        App()
    }
}