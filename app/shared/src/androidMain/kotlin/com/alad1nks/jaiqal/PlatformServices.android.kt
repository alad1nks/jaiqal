package com.alad1nks.jaiqal

import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.alad1nks.jaiqal.core.preferences.AppLanguage
import androidx.core.net.toUri

actual fun applyAppLanguage(language: AppLanguage) {
    val tags = when (language) {
        AppLanguage.SYSTEM -> ""
        AppLanguage.KAZAKH -> "kk"
        AppLanguage.RUSSIAN -> "ru"
        AppLanguage.ENGLISH -> "en"
    }
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
}

actual fun openExternalUrl(url: String) {
    val context = AndroidAppContext.value ?: return
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal object AndroidAppContext {
    var value: android.content.Context? = null
}
