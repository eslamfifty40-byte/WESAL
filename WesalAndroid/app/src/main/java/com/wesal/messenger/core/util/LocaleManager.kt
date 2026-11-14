package com.wesal.messenger.core.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LocaleManager {
    fun setAppLanguage(languageTag: String) {
        val locales = LocaleListCompat.forLanguageTags(languageTag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun getCurrentLanguageTag(): String {
        val current = AppCompatDelegate.getApplicationLocales()
        return if (!current.isEmpty) {
            current[0]?.language ?: "ar"
        } else {
            "ar"
        }
    }
}
