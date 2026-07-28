package com.alad1nks.jaiqal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.alad1nks.jaiqal.di.createAndroidAppConfiguration
import com.alad1nks.jaiqal.di.createUnavailableAppConfiguration

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val configuration = createAndroidAppConfiguration(
            context = applicationContext,
            backendBaseUrl = BuildConfig.API_BASE_URL,
            environmentName = BuildConfig.APP_ENVIRONMENT,
        )

        setContent {
            App(configuration)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App(createUnavailableAppConfiguration("http://10.0.2.2:8080", "local"))
}
