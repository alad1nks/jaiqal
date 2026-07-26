package com.alad1nks.jaiqal.core.network
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
actual fun platformHttpClient()=HttpClient(OkHttp)
