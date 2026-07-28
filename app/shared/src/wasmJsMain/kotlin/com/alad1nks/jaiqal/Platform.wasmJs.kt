package com.alad1nks.jaiqal

class WasmPlatform: Platform {
    override val name: String = "Web with Kotlin/Wasm"
    override val localBackendBaseUrl: String = "http://localhost:8080"
}

actual fun getPlatform(): Platform = WasmPlatform()
