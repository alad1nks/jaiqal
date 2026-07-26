package com.alad1nks.jaiqal.core.network
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
actual fun platformHttpClient()=HttpClient(CIO)
