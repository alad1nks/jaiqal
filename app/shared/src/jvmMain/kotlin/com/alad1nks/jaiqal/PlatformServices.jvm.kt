package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.core.preferences.AppLanguage
import java.awt.Desktop
import java.net.URI
import java.util.Locale

actual fun applyAppLanguage(language: AppLanguage) {
    val locale = when (language) {
        AppLanguage.SYSTEM -> Locale.getDefault()
        AppLanguage.KAZAKH -> Locale.forLanguageTag("kk")
        AppLanguage.RUSSIAN -> Locale.forLanguageTag("ru")
        AppLanguage.ENGLISH -> Locale.forLanguageTag("en")
    }
    Locale.setDefault(locale)
}

actual fun openExternalUrl(url: String) {
    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
}
