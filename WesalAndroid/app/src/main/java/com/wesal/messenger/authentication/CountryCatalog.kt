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
        CountryCode("الصومال", "Somalia", "SO", "+252", "🇸🇴"),
        CountryCode("جيبوتي", "Djibouti", "DJ", "+253", "🇩🇯"),
        CountryCode("جزر القمر", "Comoros", "KM", "+269", "🇰🇲"),
        CountryCode("تركيا", "Turkey", "TR", "+90", "🇹🇷"),
        CountryCode("المملكة المتحدة", "United Kingdom", "GB", "+44", "🇬🇧"),
        CountryCode("الولايات المتحدة", "United States", "US", "+1", "🇺🇸"),
        CountryCode("كندا", "Canada", "CA", "+1", "🇨🇦"),
        CountryCode("ألمانيا", "Germany", "DE", "+49", "🇩🇪"),
        CountryCode("فرنسا", "France", "FR", "+33", "🇫🇷"),
        CountryCode("إيطاليا", "Italy", "IT", "+39", "🇮🇹"),
        CountryCode("إسبانيا", "Spain", "ES", "+34", "🇪🇸"),
        CountryCode("هولندا", "Netherlands", "NL", "+31", "🇳🇱"),
        CountryCode("بلجيكا", "Belgium", "BE", "+32", "🇧🇪"),
        CountryCode("سويسرا", "Switzerland", "CH", "+41", "🇨🇭"),
        CountryCode("النمسا", "Austria", "AT", "+43", "🇦🇹"),
        CountryCode("السويد", "Sweden", "SE", "+46", "🇸🇪"),
        CountryCode("النرويج", "Norway", "NO", "+47", "🇳🇴"),
        CountryCode("الدنمارك", "Denmark", "DK", "+45", "🇩🇰"),
        CountryCode("فنلندا", "Finland", "FI", "+358", "🇫🇮"),
        CountryCode("بولندا", "Poland", "PL", "+48", "🇵🇱"),
        CountryCode("اليونان", "Greece", "GR", "+30", "🇬🇷"),
        CountryCode("البرتغال", "Portugal", "PT", "+351", "🇵🇹"),
        CountryCode("أيرلندا", "Ireland", "IE", "+353", "🇮🇪"),
        CountryCode("روسيا", "Russia", "RU", "+7", "🇷🇺"),
        CountryCode("أوكرانيا", "Ukraine", "UA", "+380", "🇺🇦"),
        CountryCode("الهند", "India", "IN", "+91", "🇮🇳"),
        CountryCode("باكستان", "Pakistan", "PK", "+92", "🇵🇰"),
        CountryCode("بنغلاديش", "Bangladesh", "BD", "+880", "🇧🇩"),
        CountryCode("إندونيسيا", "Indonesia", "ID", "+62", "🇮🇩"),
        CountryCode("ماليزيا", "Malaysia", "MY", "+60", "🇲🇾"),
        CountryCode("سنغافورة", "Singapore", "SG", "+65", "🇸🇬"),
        CountryCode("الفلبين", "Philippines", "PH", "+63", "🇵🇭"),
        CountryCode("الصين", "China", "CN", "+86", "🇨🇳"),
        CountryCode("اليابان", "Japan", "JP", "+81", "🇯🇵"),
        CountryCode("كوريا الجنوبية", "South Korea", "KR", "+82", "🇰🇷"),
        CountryCode("تايلاند", "Thailand", "TH", "+66", "🇹🇭"),
        CountryCode("فيتنام", "Vietnam", "VN", "+84", "🇻🇳"),
        CountryCode("أفغانستان", "Afghanistan", "AF", "+93", "🇦🇫"),
        CountryCode("إيران", "Iran", "IR", "+98", "🇮🇷"),
        CountryCode("أذربيجان", "Azerbaijan", "AZ", "+994", "🇦🇿"),
        CountryCode("أوزبكستان", "Uzbekistan", "UZ", "+998", "🇺🇿"),
        CountryCode("كازاخستان", "Kazakhstan", "KZ", "+7", "🇰🇿"),
        CountryCode("نيجيريا", "Nigeria", "NG", "+234", "🇳🇬"),
        CountryCode("كينيا", "Kenya", "KE", "+254", "🇰🇪"),
        CountryCode("إثيوبيا", "Ethiopia", "ET", "+251", "🇪🇹"),
        CountryCode("جنوب أفريقيا", "South Africa", "ZA", "+27", "🇿🇦"),
        CountryCode("غانا", "Ghana", "GH", "+233", "🇬🇭"),
        CountryCode("السنغال", "Senegal", "SN", "+221", "🇸🇳"),
        CountryCode("تنزانيا", "Tanzania", "TZ", "+255", "🇹🇿"),
        CountryCode("أوغندا", "Uganda", "UG", "+256", "🇺🇬"),
        CountryCode("البرازيل", "Brazil", "BR", "+55", "🇧🇷"),
        CountryCode("الأرجنتين", "Argentina", "AR", "+54", "🇦🇷"),
        CountryCode("المكسيك", "Mexico", "MX", "+52", "🇲🇽"),
        CountryCode("كولومبيا", "Colombia", "CO", "+57", "🇨🇴"),
        CountryCode("تشيلي", "Chile", "CL", "+56", "🇨🇱"),
        CountryCode("بيرو", "Peru", "PE", "+51", "🇵🇪"),
        CountryCode("أستراليا", "Australia", "AU", "+61", "🇦🇺"),
        CountryCode("نيوزيلندا", "New Zealand", "NZ", "+64", "🇳🇿"),
        CountryCode("رومانيا", "Romania", "RO", "+40", "🇷🇴"),
        CountryCode("المجر", "Hungary", "HU", "+36", "🇭🇺"),
        CountryCode("التشيك", "Czechia", "CZ", "+420", "🇨🇿"),
        CountryCode("بلغاريا", "Bulgaria", "BG", "+359", "🇧🇬"),
        CountryCode("صربيا", "Serbia", "RS", "+381", "🇷🇸"),
        CountryCode("كرواتيا", "Croatia", "HR", "+385", "🇭🇷"),
        CountryCode("سريلانكا", "Sri Lanka", "LK", "+94", "🇱🇰"),
        CountryCode("نيبال", "Nepal", "NP", "+977", "🇳🇵"),
        CountryCode("تايوان", "Taiwan", "TW", "+886", "🇹🇼"),
        CountryCode("هونغ كونغ", "Hong Kong", "HK", "+852", "🇭🇰"),
        CountryCode("قبرص", "Cyprus", "CY", "+357", "🇨🇾"),
        CountryCode("مالطا", "Malta", "MT", "+356", "🇲🇹"),
        CountryCode("ألبانيا", "Albania", "AL", "+355", "🇦🇱"),
        CountryCode("البوسنة والهرسك", "Bosnia and Herzegovina", "BA", "+387", "🇧🇦"),
        CountryCode("جورجيا", "Georgia", "GE", "+995", "🇬🇪"),
        CountryCode("أرمينيا", "Armenia", "AM", "+374", "🇦🇲"),
        CountryCode("فنزويلا", "Venezuela", "VE", "+58", "🇻🇪"),
        CountryCode("الإكوادور", "Ecuador", "EC", "+593", "🇪🇨")
    )

    fun search(query: String): List<CountryCode> {
        val q = query.trim()
        if (q.isBlank()) return countries
        return countries.filter {
            it.nameAr.contains(q, ignoreCase = true) ||
                it.nameEn.contains(q, ignoreCase = true) ||
                it.dialCode.contains(q, ignoreCase = true) ||
                it.iso.equals(q, ignoreCase = true)
        }
    }

    fun findByDialCode(dialCode: String): CountryCode? {
        val clean = dialCode.trim().let { if (it.startsWith("+")) it else "+$it" }
        return countries.firstOrNull { it.dialCode == clean }
    }

    fun detectFromE164(e164: String?): Pair<CountryCode, String> {
        if (e164.isNullOrBlank()) return defaultEgypt to ""
        val clean = e164.trim()
        if (!clean.startsWith("+")) return defaultEgypt to clean
        val matched = countries
            .sortedByDescending { it.dialCode.length }
            .firstOrNull { clean.startsWith(it.dialCode) }
            ?: return defaultEgypt to clean
        val local = clean.removePrefix(matched.dialCode)
        val displayLocal = if (matched.iso == "EG" && !local.startsWith("0") && local.length == 10) {
            "0$local"
        } else {
            local
        }
        return matched to displayLocal
    }
}
