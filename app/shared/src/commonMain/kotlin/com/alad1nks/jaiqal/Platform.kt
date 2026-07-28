package com.alad1nks.jaiqal

interface Platform {
    val name: String
    val localBackendBaseUrl: String
}

expect fun getPlatform(): Platform
