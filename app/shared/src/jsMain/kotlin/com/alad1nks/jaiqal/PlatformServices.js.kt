package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.core.preferences.AppLanguage
import web.dom.document
import web.window.window

actual fun applyAppLanguage(language: AppLanguage) {
    document.documentElement.lang = when (language) {
        AppLanguage.SYSTEM -> ""
        AppLanguage.KAZAKH -> "kk"
        AppLanguage.RUSSIAN -> "ru"
        AppLanguage.ENGLISH -> "en"
    }
}

actual fun openExternalUrl(url: String) {
    window.open(url)
}
