package com.alad1nks.jaiqal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App(
                backendBaseUrl = BuildConfig.API_BASE_URL,
                environmentName = BuildConfig.APP_ENVIRONMENT,
            )
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
