package com.wesal.messenger.core.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

data class WesalLanguageOption(
    val tag: String,
    val nativeName: String,
    val englishName: String,
    val isRtl: Boolean = false
)

object LocaleManager {
    val worldLanguages: List<WesalLanguageOption> = listOf(
        WesalLanguageOption("ar", "العربية", "Arabic", isRtl = true),
        WesalLanguageOption("en", "English", "English"),
        WesalLanguageOption("fr", "Français", "French"),
        WesalLanguageOption("es", "Español", "Spanish"),
        WesalLanguageOption("de", "Deutsch", "German"),
        WesalLanguageOption("tr", "Türkçe", "Turkish"),
        WesalLanguageOption("id", "Bahasa Indonesia", "Indonesian"),
        WesalLanguageOption("ur", "اردو", "Urdu", isRtl = true),
        WesalLanguageOption("fa", "فارسی", "Persian", isRtl = true),
        WesalLanguageOption("hi", "हिन्दी", "Hindi"),
        WesalLanguageOption("bn", "বাংলা", "Bengali"),
        WesalLanguageOption("ru", "Русский", "Russian"),
        WesalLanguageOption("zh", "中文 (简体)", "Chinese (Simplified)"),
        WesalLanguageOption("zh-TW", "中文 (繁體)", "Chinese (Traditional)"),
        WesalLanguageOption("ja", "日本語", "Japanese"),
        WesalLanguageOption("ko", "한국어", "Korean"),
        WesalLanguageOption("it", "Italiano", "Italian"),
        WesalLanguageOption("pt", "Português", "Portuguese"),
        WesalLanguageOption("nl", "Nederlands", "Dutch"),
        WesalLanguageOption("pl", "Polski", "Polish"),
        WesalLanguageOption("sv", "Svenska", "Swedish"),
        WesalLanguageOption("da", "Dansk", "Danish"),
        WesalLanguageOption("no", "Norsk", "Norwegian"),
        WesalLanguageOption("fi", "Suomi", "Finnish"),
        WesalLanguageOption("el", "Ελληνικά", "Greek"),
        WesalLanguageOption("uk", "Українська", "Ukrainian"),
        WesalLanguageOption("ro", "Română", "Romanian"),
        WesalLanguageOption("cs", "Čeština", "Czech"),
        WesalLanguageOption("hu", "Magyar", "Hungarian"),
        WesalLanguageOption("ms", "Bahasa Melayu", "Malay"),
        WesalLanguageOption("th", "ไทย", "Thai"),
        WesalLanguageOption("vi", "Tiếng Việt", "Vietnamese"),
        WesalLanguageOption("fil", "Filipino", "Filipino"),
        WesalLanguageOption("sw", "Kiswahili", "Swahili"),
        WesalLanguageOption("am", "አማርኛ", "Amharic"),
        WesalLanguageOption("he", "עברית", "Hebrew", isRtl = true),
        WesalLanguageOption("ps", "پښتو", "Pashto", isRtl = true),
        WesalLanguageOption("ku", "کوردی", "Kurdish", isRtl = true),
        WesalLanguageOption("az", "Azərbaycan", "Azerbaijani"),
        WesalLanguageOption("uz", "Oʻzbek", "Uzbek"),
        WesalLanguageOption("kk", "Қазақша", "Kazakh"),
        WesalLanguageOption("so", "Soomaali", "Somali")
    )

    fun isRtlLanguage(tag: String): Boolean {
        val normalized = tag.lowercase().substringBefore('-')
        return normalized in setOf("ar", "ur", "fa", "he", "iw", "ps", "ku")
    }

    fun labelForTag(tag: String): String =
        worldLanguages.firstOrNull { it.tag.equals(tag, ignoreCase = true) }?.nativeName
            ?: worldLanguages.firstOrNull { it.tag.equals(tag.substringBefore('-'), ignoreCase = true) }?.nativeName
            ?: tag

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
