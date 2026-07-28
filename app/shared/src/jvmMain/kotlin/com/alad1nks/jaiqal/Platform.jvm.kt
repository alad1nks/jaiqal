package com.alad1nks.jaiqal

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
    override val localBackendBaseUrl: String = "http://127.0.0.1:8080"
}

actual fun getPlatform(): Platform = JVMPlatform()
