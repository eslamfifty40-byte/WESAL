package com.wesal.messenger.authentication

import com.wesal.messenger.domain.model.CountryCode

object CountryCatalog {
    val defaultEgypt = CountryCode("مصر", "Egypt", "EG", "+20", "🇪🇬")

    val countries = listOf(
        defaultEgypt,
        CountryCode("السعودية", "Saudi Arabia", "SA", "+966", "🇸🇦"),
        CountryCode("الإمارات", "United Arab Emirates", "AE", "+971", "🇦🇪"),
        CountryCode("الكويت", "Kuwait", "KW", "+965", "🇰🇼"),
        CountryCode("قطر", "Qatar", "QA", "+974", "🇶🇦"),
        CountryCode("البحرين", "Bahrain", "BH", "+973", "🇧🇭"),
        CountryCode("عُمان", "Oman", "OM", "+968", "🇴🇲"),
        CountryCode("الأردن", "Jordan", "JO", "+962", "🇯🇴"),
        CountryCode("فلسطين", "Palestine", "PS", "+970", "🇵🇸"),
        CountryCode("لبنان", "Lebanon", "LB", "+961", "🇱🇧"),
        CountryCode("العراق", "Iraq", "IQ", "+964", "🇮🇶"),
        CountryCode("سوريا", "Syria", "SY", "+963", "🇸🇾"),
        CountryCode("اليمن", "Yemen", "YE", "+967", "🇾🇪"),
        CountryCode("السودان", "Sudan", "SD", "+249", "🇸🇩"),
        CountryCode("ليبيا", "Libya", "LY", "+218", "🇱🇾"),
        CountryCode("تونس", "Tunisia", "TN", "+216", "🇹🇳"),
        CountryCode("الجزائر", "Algeria", "DZ", "+213", "🇩🇿"),
        CountryCode("المغرب", "Morocco", "MA", "+212", "🇲🇦"),
        CountryCode("موريتانيا", "Mauritania", "MR", "+222", "🇲🇷"),
        CountryCode("تركيا", "Turkey", "TR", "+90", "🇹🇷"),
        CountryCode("المملكة المتحدة", "United Kingdom", "GB", "+44", "🇬🇧"),
        CountryCode("الولايات المتحدة", "United States", "US", "+1", "🇺🇸"),
        CountryCode("ألمانيا", "Germany", "DE", "+49", "🇩🇪"),
        CountryCode("فرنسا", "France", "FR", "+33", "🇫🇷")
    )
}
