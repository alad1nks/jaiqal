package com.alad1nks.jaiqal.core.network
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
actual fun platformHttpClient()=HttpClient(Darwin)
