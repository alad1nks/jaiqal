package com.alad1nks.jaiqal

import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
    override val localBackendBaseUrl: String = "http://127.0.0.1:8080"
}

actual fun getPlatform(): Platform = IOSPlatform()
