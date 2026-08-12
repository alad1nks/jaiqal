package com.alad1nks.jaiqal

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.alad1nks.jaiqal.di.createAndroidAppConfiguration
import com.alad1nks.jaiqal.di.createUnavailableAppConfiguration

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val configuration = createAndroidAppConfiguration(
            context = applicationContext,
            backendBaseUrl = BuildConfig.API_BASE_URL,
            environmentName = BuildConfig.APP_ENVIRONMENT,
            enableNetworkLogging = BuildConfig.DEBUG,
            appVersion = BuildConfig.VERSION_NAME,
            isDebug = BuildConfig.DEBUG,
            privacyPolicyUrl = BuildConfig.PRIVACY_POLICY_URL.takeIf(String::isNotBlank),
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
