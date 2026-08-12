package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.core.preferences.AppLanguage
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication

actual fun applyAppLanguage(language: AppLanguage) {
    val defaults = NSUserDefaults.standardUserDefaults
    when (language) {
        AppLanguage.SYSTEM -> defaults.removeObjectForKey("AppleLanguages")
        AppLanguage.KAZAKH -> defaults.setObject(listOf("kk"), "AppleLanguages")
        AppLanguage.RUSSIAN -> defaults.setObject(listOf("ru"), "AppleLanguages")
        AppLanguage.ENGLISH -> defaults.setObject(listOf("en"), "AppleLanguages")
    }
    defaults.synchronize()
}

actual fun openExternalUrl(url: String) {
    NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it) }
}
