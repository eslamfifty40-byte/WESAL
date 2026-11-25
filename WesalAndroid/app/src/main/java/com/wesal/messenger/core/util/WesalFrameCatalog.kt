package com.wesal.messenger.core.util

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.wesal.messenger.R

enum class VerificationSealType(
    val key: String,
    val labelAr: String,
    val labelEn: String,
    val primaryHex: Long,
    val secondaryHex: Long,
    val glowHex: Long
) {
    BLUE("BLUE", "الشارة الزرقاء", "Blue Seal", 0xFF1D9BF0L, 0xFF0A66C2L, 0xFF1D9BF0L),
    BLACK("BLACK", "الشارة السمراء", "Black Seal", 0xFF111827L, 0xFF000000L, 0xFF00D5E8L),
    GREEN("GREEN", "الشارة الخضراء", "Green Seal", 0xFF00C853L, 0xFF007E33L, 0xFF00E676L),
    GOLD("GOLD", "الشارة الذهبية", "Gold Seal", 0xFFFFD54FL, 0xFFB8860BL, 0xFFFFD740L),
    CYAN("CYAN", "الشارة السماوية", "Cyan Seal", 0xFF00D5E8L, 0xFF00838FL, 0xFF00E5FFL),
    PURPLE("PURPLE", "الشارة البنفسجية", "Purple Seal", 0xFF9C27B0L, 0xFF4A148CL, 0xFFE040FBL),
    RED("RED", "الشارة الحمراء", "Red Seal", 0xFFFF1744L, 0xFFB71C1CL, 0xFFFF5252L),
    SILVER("SILVER", "الشارة الفضية", "Silver Seal", 0xFFE2E8F0L, 0xFF64748BL, 0xFFCBD5E1L),
    ROSE("ROSE", "الشارة الوردية", "Rose Seal", 0xFFEC4899L, 0xFF9D174DL, 0xFFFF80ABL),
    ORANGE("ORANGE", "الشارة البرتقالية", "Orange Seal", 0xFFFF9100L, 0xFFE65100L, 0xFFFFAB40L),
    INDIGO("INDIGO", "الشارة النيلية", "Indigo Seal", 0xFF6366F1L, 0xFF3730A3L, 0xFF818CF8L),
    EMERALD("EMERALD", "الشارة الزمردية", "Emerald Seal", 0xFF10B981L, 0xFF065F46L, 0xFF34D399L),
    TURQUOISE("TURQUOISE", "الشارة الفيروزية", "Turquoise Seal", 0xFF06B6D4L, 0xFF0E7490L, 0xFF22D3EEL),
    CRIMSON("CRIMSON", "الشارة القرمزية", "Crimson Seal", 0xFFDC2626L, 0xFF7F1D1DL, 0xFFF87171L),
    ROYAL_BLUE("ROYAL_BLUE", "الأزرق الملكي", "Royal Blue Seal", 0xFF2563EBL, 0xFF1E3A8AL, 0xFF60A5FAL),
    LIME("LIME", "الشارة الليمونية", "Lime Seal", 0xFF84CC16L, 0xFF3F6212L, 0xFFA3E635L),
    AMBER("AMBER", "الشارة العنبرية", "Amber Seal", 0xFFF59E0BL, 0xFF92400EL, 0xFFFBBF24L),
    MAGENTA("MAGENTA", "الشارة الأرجوانية", "Magenta Seal", 0xFFD946EFL, 0xFF86198FL, 0xFFF0ABACL),
    TEAL("TEAL", "الأزرق المخضر", "Teal Seal", 0xFF14B8A6L, 0xFF115E59L, 0xFF2DD4BFL),
    CORAL("CORAL", "الشارة المرجانية", "Coral Seal", 0xFFFF6B6BL, 0xFFC92A2AL, 0xFFFF8787L),
    VIOLET("VIOLET", "البنفسجي الداكن", "Violet Seal", 0xFF8B5CF6L, 0xFF4C1D95L, 0xFFA78BFAL),
    PLATINUM("PLATINUM", "الشارة البلاتينية", "Platinum Seal", 0xFFF8FAFCL, 0xFF94A3B8L, 0xFFE2E8F0L),
    BRONZE("BRONZE", "الشارة البرونزية", "Bronze Seal", 0xFFCD7F32L, 0xFF7C4A15L, 0xFFE6A15CL),
    MINT("MINT", "الشارة النعناعية", "Mint Seal", 0xFF00E5A3L, 0xFF008F64L, 0xFF69F0AEL),
    SAPPHIRE("SAPPHIRE", "الشارة الياقوتية الأزرق", "Sapphire Seal", 0xFF0F52BAL, 0xFF082D69L, 0xFF3B82F6L),
    RUBY("RUBY", "الشارة الياقوتية الأحمر", "Ruby Seal", 0xFFE0115FL, 0xFF880E4FL, 0xFFFF4081L),
    PEARL("PEARL", "الشارة اللؤلؤية", "Pearl Seal", 0xFFF1F5F9L, 0xFF475569L, 0xFF00D9E8L),
    OBSIDIAN("OBSIDIAN", "الأوبسيديان الملكي", "Obsidian Seal", 0xFF1E293BL, 0xFF020617L, 0xFFFFD54FL),
    NEON_YELLOW("NEON_YELLOW", "الأصفر الفسفوري", "Neon Yellow Seal", 0xFFFACC15L, 0xFFA16207L, 0xFFFEF08AL),
    COSMIC("COSMIC", "الشارة الكونية", "Cosmic Seal", 0xFF7C3AEDL, 0xFF0EA5E9L, 0xFF38BDF8L);

    companion object {
        fun fromKey(raw: String?): VerificationSealType =
            entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: BLUE
    }
}

enum class VerificationDurationOption(
    val key: String,
    val labelAr: String,
    val labelEn: String
) {
    HOUR_1("1H", "ساعة واحدة", "1 Hour"),
    CUSTOM_HOURS("CUSTOM_H", "ساعات مخصصة", "Custom Hours"),
    DAY_1("1D", "يوم واحد", "1 Day"),
    CUSTOM_DAYS("CUSTOM_D", "عدة أيام", "Multiple Days"),
    MONTH_1("1M", "شهر تقويمي", "1 Calendar Month"),
    CUSTOM_MONTHS("CUSTOM_M", "عدة شهور تقويمية", "Multiple Months"),
    PERMANENT("PERMANENT", "دائم", "Permanent")
}

enum class NeonColorPreset(
    val key: String,
    val labelAr: String,
    val labelEn: String,
    val glowColor: Color,
    val textColorDarkBg: Color,
    val textColorLightBg: Color
) {
    NONE("", "بدون نيون", "None", Color.Transparent, Color.White, Color(0xFF0A1116)),
    RED("RED", "أحمر نيون", "Neon Red", Color(0xFFFF1744), Color(0xFFFF6E87), Color(0xFFB71C1C)),
    GREEN("GREEN", "أخضر نيون", "Neon Green", Color(0xFF00E676), Color(0xFF5CFFAE), Color(0xFF006E38)),
    BLACK("BLACK", "أسود نيون", "Neon Obsidian", Color(0xFF00D5E8), Color(0xFFFFFFFF), Color(0xFF05090B)),
    BLUE("BLUE", "أزرق نيون", "Neon Blue", Color(0xFF00D9E8), Color(0xFF5BEBFF), Color(0xFF006080)),
    GOLD("GOLD", "ذهبي نيون", "Neon Gold", Color(0xFFFFD54F), Color(0xFFFFE082), Color(0xFF8C6200)),
    PURPLE("PURPLE", "بنفسجي نيون", "Neon Purple", Color(0xFFD500F9), Color(0xFFEA80FC), Color(0xFF6A1B9A)),
    PINK("PINK", "وردي نيون", "Neon Pink", Color(0xFFFF4081), Color(0xFFFF80AB), Color(0xFFAD1457)),
    ORANGE("ORANGE", "برتقالي نيون", "Neon Orange", Color(0xFFFF6D00), Color(0xFFFF9E40), Color(0xFFBF360C)),
    CYAN("CYAN", "سماوي نيون", "Neon Cyan", Color(0xFF00E5FF), Color(0xFF84FFFF), Color(0xFF006064)),
    EMERALD("EMERALD", "زمردي نيون", "Neon Emerald", Color(0xFF10B981), Color(0xFF6EE7B7), Color(0xFF047857)),
    LIME("LIME", "ليموني نيون", "Neon Lime", Color(0xFFAEEA00), Color(0xFFCCFF90), Color(0xFF558B2F)),
    INDIGO("INDIGO", "نيلي نيون", "Neon Indigo", Color(0xFF536DFE), Color(0xFF8C9EFF), Color(0xFF283593)),
    CRIMSON("CRIMSON", "قرمزي نيون", "Neon Crimson", Color(0xFFE11D48), Color(0xFFFB7185), Color(0xFF9F1239)),
    SILVER("SILVER", "فضي نيون", "Neon Silver", Color(0xFFE2E8F0), Color(0xFFF8FAFC), Color(0xFF334155)),
    AMBER("AMBER", "عنبر نيون", "Neon Amber", Color(0xFFFFAB00), Color(0xFFFFD740), Color(0xFF8D4E00)),
    TEAL("TEAL", "فيروزي غامق", "Neon Teal", Color(0xFF1DE9B6), Color(0xFF64FFDA), Color(0xFF00695C)),
    CORAL("CORAL", "مرجاني نيون", "Neon Coral", Color(0xFFFF5252), Color(0xFFFF8A80), Color(0xFFC62828)),
    VIOLET("VIOLET", "ليلكي نيون", "Neon Violet", Color(0xFF7C4DFF), Color(0xFFB388FF), Color(0xFF4527A0)),
    MAGENTA("MAGENTA", "ماجنتا نيون", "Neon Magenta", Color(0xFFFF00AA), Color(0xFFFF77CC), Color(0xFF880E4F)),
    SKY("SKY", "أزرق فاتح نيون", "Neon Sky", Color(0xFF38BDF8), Color(0xFF7DD3FC), Color(0xFF0369A1)),
    MINT("MINT", "نعناع نيون", "Neon Mint", Color(0xFF69F0AE), Color(0xFFB9F6CA), Color(0xFF1B5E20)),
    RUBY("RUBY", "ياقوتي نيون", "Neon Ruby", Color(0xFFF43F5E), Color(0xFFFDA4AF), Color(0xFF881337)),
    YELLOW("YELLOW", "أصفر نيون", "Neon Yellow", Color(0xFFFFEA00), Color(0xFFFFFF8D), Color(0xFF7A6300)),
    ROYAL("ROYAL", "ملكي نيون", "Neon Royal", Color(0xFF2979FF), Color(0xFF82B1FF), Color(0xFF0D47A1)),
    BRONZE("BRONZE", "برونزي نيون", "Neon Bronze", Color(0xFFD97706), Color(0xFFFCD34D), Color(0xFF78350F)),
    ICE("ICE", "ثلجي نيون", "Neon Ice", Color(0xFF67E8F9), Color(0xFFCFFAFE), Color(0xFF0E7490)),
    NEON_WHITE("NEON_WHITE", "أبيض مضيء", "Neon White", Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFF1E293B)),
    PEACH("PEACH", "خوخي نيون", "Neon Peach", Color(0xFFFF8A65), Color(0xFFFFAB91), Color(0xFFBF360C)),
    AQUA("AQUA", "أكوا نيون", "Neon Aqua", Color(0xFF00B8D4), Color(0xFF18FFFF), Color(0xFF006064)),
    ROSE_GOLD("ROSE_GOLD", "ذهبي وردي", "Neon Rose Gold", Color(0xFFF472B6), Color(0xFFFBCFE8), Color(0xFF9D174D));

    companion object {
        fun fromKey(raw: String?): NeonColorPreset =
            entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: NONE
    }
}

data class AppearanceColorOption(
    val labelAr: String,
    val labelEn: String,
    val hex: Long
)

object WesalAppearancePresets {
    val accentPrimaryPresets: List<AppearanceColorOption> = listOf(
        AppearanceColorOption("زمردي وصال", "WESAL Emerald", 0xFF00D66BL),
        AppearanceColorOption("أخضر ملكي", "Royal Green", 0xFF10B981L),
        AppearanceColorOption("فيروزي", "Turquoise", 0xFF00D5E8L),
        AppearanceColorOption("أزرق نيون", "Electric Blue", 0xFF1D9BF0L),
        AppearanceColorOption("ذهبي فاخر", "Luxury Gold", 0xFFFFD54FL),
        AppearanceColorOption("بنفسجي ملكي", "Royal Purple", 0xFF8B5CF6L),
        AppearanceColorOption("أحمر ياقوتي", "Ruby Red", 0xFFE11D48L),
        AppearanceColorOption("برتقالي دافئ", "Warm Amber", 0xFFF59E0BL),
        AppearanceColorOption("وردي نيون", "Neon Rose", 0xFFEC4899L),
        AppearanceColorOption("سماوي عميق", "Deep Cyan", 0xFF06B6D4L),
        AppearanceColorOption("نعناعي", "Fresh Mint", 0xFF00E5A3L),
        AppearanceColorOption("نيلي", "Indigo Glow", 0xFF6366F1L)
    )

    val secondaryAccentPresets: List<AppearanceColorOption> = listOf(
        AppearanceColorOption("تركواز وصال", "WESAL Turquoise", 0xFF00D5E8L),
        AppearanceColorOption("أزرق سماوي", "Sky Cyan", 0xFF38BDF8L),
        AppearanceColorOption("أخضر مضيء", "Neon Green", 0xFF00E676L),
        AppearanceColorOption("ذهبي", "Gold", 0xFFFFCA28L),
        AppearanceColorOption("بنفسجي فاتح", "Violet", 0xFFA78BFAL),
        AppearanceColorOption("مرجاني", "Coral", 0xFFFF6B6BL),
        AppearanceColorOption("تيال", "Teal", 0xFF14B8A6L),
        AppearanceColorOption("ليموني", "Lime", 0xFF84CC16L),
        AppearanceColorOption("وردي", "Pink", 0xFFF472B6L),
        AppearanceColorOption("بلاتيني", "Platinum", 0xFFE2E8F0L),
        AppearanceColorOption("أزرق ملكي", "Royal Blue", 0xFF3B82F6L),
        AppearanceColorOption("برونزي", "Bronze", 0xFFD97706L)
    )

    val backgroundPresets: List<AppearanceColorOption> = listOf(
        AppearanceColorOption("افتراضي وصال", "Default", 0L),
        AppearanceColorOption("أسود عميق", "Obsidian Black", 0xFF05090BL),
        AppearanceColorOption("كحلي ليلي", "Midnight Navy", 0xFF070D19L),
        AppearanceColorOption("رمادي فحمي", "Charcoal", 0xFF111827L),
        AppearanceColorOption("زمردي ليلي", "Dark Emerald", 0xFF04140FL),
        AppearanceColorOption("أرجواني ليلي", "Dark Plum", 0xFF12081CL),
        AppearanceColorOption("أبيض نقي", "Pure White", 0xFFFFFFFFL),
        AppearanceColorOption("ثلجي ناعم", "Ice Mist", 0xFFF3F6F8L),
        AppearanceColorOption("كريمي دافئ", "Warm Cream", 0xFFFAF7F2L),
        AppearanceColorOption("أزرق ثلجي", "Frost Blue", 0xFFF0F7FFL),
        AppearanceColorOption("نعناعي فاتح", "Mint Tint", 0xFFF0FDF4L),
        AppearanceColorOption("رمادي أنيق", "Slate Mist", 0xFFF1F5F9L)
    )

    val surfacePresets: List<AppearanceColorOption> = listOf(
        AppearanceColorOption("افتراضي وصال", "Default", 0L),
        AppearanceColorOption("سطح ليلي", "Dark Surface", 0xFF0B1115L),
        AppearanceColorOption("أردوازي داكن", "Dark Slate", 0xFF131C22L),
        AppearanceColorOption("كحلي بارز", "Navy Card", 0xFF0F172AL),
        AppearanceColorOption("غابة داكنة", "Forest Card", 0xFF082018L),
        AppearanceColorOption("فحمي مخملي", "Velvet Gray", 0xFF18181BL),
        AppearanceColorOption("أبيض بطاقات", "White Card", 0xFFFFFFFFL),
        AppearanceColorOption("لؤلؤي فاتح", "Pearl Card", 0xFFF8FAFCL),
        AppearanceColorOption("سماوي فاتح", "Sky Card", 0xFFEFF6FFL),
        AppearanceColorOption("زمردي فاتح", "Mint Card", 0xFFECFDF5L),
        AppearanceColorOption("رملي ناعم", "Sand Card", 0xFFFEFCE8L),
        AppearanceColorOption("بنفسجي فاتح", "Lavender Card", 0xFFF5F3FFL)
    )

    val textPrimaryPresets: List<AppearanceColorOption> = listOf(
        AppearanceColorOption("تباين تلقائي", "Auto Contrast", 0L),
        AppearanceColorOption("أبيض ناصع", "Pure White", 0xFFFFFFFFL),
        AppearanceColorOption("أبيض ثلجي", "Snow White", 0xFFF8FAFCL),
        AppearanceColorOption("ذهبي فاتح", "Soft Gold", 0xFFFEF08AL),
        AppearanceColorOption("سماوي ناعم", "Soft Cyan", 0xFFE0F2FEL),
        AppearanceColorOption("نعناعي فاتح", "Mint Text", 0xFFD1FAE5L),
        AppearanceColorOption("أسود نقي", "Pure Black", 0xFF05090BL),
        AppearanceColorOption("أردوازي داكن", "Deep Slate", 0xFF0F172AL),
        AppearanceColorOption("رمادي فحمي", "Charcoal", 0xFF1E293BL),
        AppearanceColorOption("أزرق ليلي", "Dark Navy", 0xFF172554L),
        AppearanceColorOption("أخضر داكن", "Forest Ink", 0xFF064E3BL)
    )

    val iconTintPresets: List<AppearanceColorOption> = listOf(
        AppearanceColorOption("تلقائي", "Auto", 0L),
        AppearanceColorOption("تركواز وصال", "Turquoise", 0xFF00D5E8L),
        AppearanceColorOption("زمردي", "Emerald", 0xFF00D66BL),
        AppearanceColorOption("أزرق توثيق", "Verified Blue", 0xFF1D9BF0L),
        AppearanceColorOption("ذهبي ملكي", "Royal Gold", 0xFFFFD54FL),
        AppearanceColorOption("أبيض", "White", 0xFFFFFFFFL),
        AppearanceColorOption("أسود", "Black", 0xFF0A1116L),
        AppearanceColorOption("بنفسجي", "Violet", 0xFF8B5CF6L),
        AppearanceColorOption("وردي", "Rose", 0xFFEC4899L),
        AppearanceColorOption("برتقالي", "Amber", 0xFFF59E0BL),
        AppearanceColorOption("مرجاني", "Coral", 0xFFFF5252L),
        AppearanceColorOption("سماوي", "Sky Blue", 0xFF38BDF8L),
        AppearanceColorOption("زمردي نيون", "Neon Mint", 0xFF00E5A3L),
        AppearanceColorOption("ياقوتي", "Crimson", 0xFFE11D48L)
    )
}

data class WesalBuiltinIconSpec(
    val id: String,
    val titleAr: String,
    val titleEn: String,
    @DrawableRes val resId: Int,
    val accentColorHex: Long
)

enum class FrameCategory {
    DEFAULT_ALL_CUSTOMERS,
    CUSTOMER,
    ADMIN
}

data class WesalFrameSpec(
    val id: String,
    val titleAr: String,
    val titleEn: String,
    val category: FrameCategory,
    @DrawableRes val resId: Int,
    val overlayOnTop: Boolean,
    val accentColorHex: Long = 0xFF00D9E8L,
    val apertureCenterYFraction: Float = 0.50f,
    val apertureDiameterFraction: Float = 0.62f,
    val defaultAnimated: Boolean = false,
    val defaultAnimationStyle: String = "PULSE_GLOW"
)

object WesalFrameCatalog {
    const val DEFAULT_ALL_CUSTOMERS_FRAME_ID = "all_customers"

    val defaultCustomerFrame = WesalFrameSpec(
        id = DEFAULT_ALL_CUSTOMERS_FRAME_ID,
        titleAr = "إطار عملاء WESAL الافتراضي",
        titleEn = "Default WESAL Frame",
        category = FrameCategory.DEFAULT_ALL_CUSTOMERS,
        resId = R.drawable.wesal_frame_all_customers,
        overlayOnTop = true,
        accentColorHex = 0xFF00D9E8L,
        apertureCenterYFraction = 0.50f,
        apertureDiameterFraction = 0.60f
    )

    val customerFrames: List<WesalFrameSpec> = listOf(
        defaultCustomerFrame,
        WesalFrameSpec("cust_01", "إطار عملاء #1", "Customer Frame #1", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_01, true, 0xFFFFD54FL),
        WesalFrameSpec("cust_02", "إطار عملاء #2", "Customer Frame #2", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_02, true, 0xFFB388FFL),
        WesalFrameSpec("cust_03", "إطار عملاء #3", "Customer Frame #3", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_03, true, 0xFF00E5FFL),
        WesalFrameSpec("cust_04", "إطار عملاء #4", "Customer Frame #4", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_04, true, 0xFFFFCA28L),
        WesalFrameSpec("cust_05", "إطار عملاء #5", "Customer Frame #5", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_05, true, 0xFFFF4081L),
        WesalFrameSpec("cust_06", "إطار عملاء #6", "Customer Frame #6", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_06, true, 0xFF69F0AEL),
        WesalFrameSpec("cust_07", "إطار عملاء #7", "Customer Frame #7", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_07, true, 0xFF40C4FFL),
        WesalFrameSpec("cust_08", "إطار عملاء #8", "Customer Frame #8", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_08, true, 0xFFFFD740L),
        WesalFrameSpec("cust_09", "إطار عملاء #9", "Customer Frame #9", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_09, true, 0xFF00E676L),
        WesalFrameSpec("cust_10", "إطار عملاء #10", "Customer Frame #10", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_10, true, 0xFFE040FBL),
        WesalFrameSpec("cust_11", "إطار عملاء #11", "Customer Frame #11", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_11, true, 0xFFFFD54FL),
        WesalFrameSpec("cust_12", "إطار عملاء #12", "Customer Frame #12", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_12, true, 0xFF00E676L),
        WesalFrameSpec("cust_13", "إطار عملاء #13", "Customer Frame #13", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_13, true, 0xFFFF5252L),
        WesalFrameSpec("cust_14", "إطار عملاء #14", "Customer Frame #14", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_14, true, 0xFF00D9E8L),
        WesalFrameSpec("cust_15", "إطار عملاء #15", "Customer Frame #15", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_15, true, 0xFFFFD740L),
        WesalFrameSpec("cust_16", "إطار عملاء #16", "Customer Frame #16", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_16, true, 0xFF7C4DFFL),
        WesalFrameSpec("cust_17", "إطار عملاء #17", "Customer Frame #17", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_17, true, 0xFF00E5FFL),
        WesalFrameSpec("cust_18", "إطار عملاء #18", "Customer Frame #18", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_18, true, 0xFFFFAB40L),
        WesalFrameSpec("cust_19", "إطار عملاء #19", "Customer Frame #19", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_19, true, 0xFF64FFDAL),
        WesalFrameSpec("cust_20", "إطار عملاء #20", "Customer Frame #20", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_20, true, 0xFFFF4081L),
        WesalFrameSpec("cust_21", "إطار عملاء #21", "Customer Frame #21", FrameCategory.CUSTOMER, R.drawable.wesal_frame_cust_21, true, 0xFFFFD54FL)
    )

    val adminFrames: List<WesalFrameSpec> = listOf(
        WesalFrameSpec("admin_39", "إطار الإدارة الملكي #39 (مروحة دوّارة)", "Admin Royal Crest #39", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_39, true, 0xFFFF1744L, apertureCenterYFraction = 0.395f, apertureDiameterFraction = 0.56f, defaultAnimated = true, defaultAnimationStyle = "ROTATE_AURA"),
        WesalFrameSpec("admin_01", "إطار إدارة #1", "Admin Frame #1", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_01, true, 0xFFFFD54FL, apertureCenterYFraction = 0.415f, apertureDiameterFraction = 0.54f),
        WesalFrameSpec("admin_02", "إطار إدارة #2 (مروحة دوّارة)", "Admin Frame #2", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_02, true, 0xFF00D9E8L, apertureCenterYFraction = 0.415f, apertureDiameterFraction = 0.55f, defaultAnimated = true, defaultAnimationStyle = "ROTATE_AURA"),
        WesalFrameSpec("admin_03", "إطار إدارة #3", "Admin Frame #3", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_03, true, 0xFFFF5252L, apertureCenterYFraction = 0.395f, apertureDiameterFraction = 0.55f),
        WesalFrameSpec("admin_04", "إطار إدارة #4", "Admin Frame #4", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_04, true, 0xFF00E676L, apertureCenterYFraction = 0.395f, apertureDiameterFraction = 0.54f),
        WesalFrameSpec("admin_05", "إطار إدارة #5", "Admin Frame #5", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_05, true, 0xFF40C4FFL, apertureCenterYFraction = 0.41f, apertureDiameterFraction = 0.56f),
        WesalFrameSpec("admin_06", "إطار إدارة #6", "Admin Frame #6", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_06, true, 0xFFE040FBL, apertureCenterYFraction = 0.42f, apertureDiameterFraction = 0.55f),
        WesalFrameSpec("admin_07", "إطار إدارة #7", "Admin Frame #7", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_07, true, 0xFFFFD740L, apertureCenterYFraction = 0.415f, apertureDiameterFraction = 0.56f),
        WesalFrameSpec("admin_08", "إطار إدارة #8", "Admin Frame #8", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_08, true, 0xFF69F0AEL, apertureCenterYFraction = 0.415f, apertureDiameterFraction = 0.54f),
        WesalFrameSpec("admin_09", "إطار إدارة #9", "Admin Frame #9", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_09, true, 0xFFFF4081L, apertureCenterYFraction = 0.42f, apertureDiameterFraction = 0.53f),
        WesalFrameSpec("admin_10", "إطار ملكي #10", "Admin Royal #10", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_10, true, 0xFFFFD54FL, apertureCenterYFraction = 0.51f, apertureDiameterFraction = 0.58f),
        WesalFrameSpec("admin_11", "إطار ملكي #11", "Admin Royal #11", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_11, true, 0xFF00D9E8L, apertureCenterYFraction = 0.475f, apertureDiameterFraction = 0.52f),
        WesalFrameSpec("admin_12", "إطار إدارة #12", "Admin Frame #12", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_12, true, 0xFFFFAB40L, apertureCenterYFraction = 0.41f, apertureDiameterFraction = 0.56f),
        WesalFrameSpec("admin_13", "إطار ملكي #13", "Admin Royal #13", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_13, true, 0xFFFF5252L, apertureCenterYFraction = 0.51f, apertureDiameterFraction = 0.56f),
        WesalFrameSpec("admin_14", "إطار إدارة #14", "Admin Frame #14", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_14, true, 0xFF00E676L, apertureCenterYFraction = 0.415f, apertureDiameterFraction = 0.54f),
        WesalFrameSpec("admin_15", "إطار ملكي #15", "Admin Royal #15", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_15, true, 0xFFB388FFL),
        WesalFrameSpec("admin_16", "إطار ملكي #16", "Admin Royal #16", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_16, true, 0xFF00E5FFL),
        WesalFrameSpec("admin_17", "إطار ملكي #17", "Admin Royal #17", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_17, true, 0xFFFFD740L, apertureCenterYFraction = 0.50f, apertureDiameterFraction = 0.56f),
        WesalFrameSpec("admin_18", "إطار ملكي #18", "Admin Royal #18", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_18, true, 0xFFFF6E40L, apertureCenterYFraction = 0.48f, apertureDiameterFraction = 0.58f),
        WesalFrameSpec("admin_19", "إطار ملكي #19", "Admin Royal #19", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_19, true, 0xFF69F0AEL),
        WesalFrameSpec("admin_20", "إطار ملكي #20", "Admin Royal #20", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_20, true, 0xFFFFD54FL, apertureCenterYFraction = 0.47f, apertureDiameterFraction = 0.62f),
        WesalFrameSpec("admin_21", "إطار ملكي #21", "Admin Royal #21", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_21, true, 0xFFD4AF37L, apertureCenterYFraction = 0.52f, apertureDiameterFraction = 0.58f),
        WesalFrameSpec("admin_22", "إطار ملكي #22", "Admin Royal #22", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_22, true, 0xFFFF5252L, apertureCenterYFraction = 0.46f, apertureDiameterFraction = 0.55f),
        WesalFrameSpec("admin_23", "إطار إدارة #23", "Admin Frame #23", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_23, true, 0xFF00D9E8L, apertureCenterYFraction = 0.40f, apertureDiameterFraction = 0.54f),
        WesalFrameSpec("admin_24", "إطار ملكي #24", "Admin Royal #24", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_24, true, 0xFFFFD740L, apertureCenterYFraction = 0.49f, apertureDiameterFraction = 0.57f),
        WesalFrameSpec("admin_25", "إطار ملكي #25", "Admin Royal #25", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_25, true, 0xFFB388FFL, apertureCenterYFraction = 0.51f, apertureDiameterFraction = 0.56f),
        WesalFrameSpec("admin_26", "إطار ملكي #26", "Admin Royal #26", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_26, true, 0xFF00E676L, apertureCenterYFraction = 0.49f, apertureDiameterFraction = 0.62f),
        WesalFrameSpec("admin_27", "إطار ملكي #27", "Admin Royal #27", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_27, true, 0xFFFFAB40L, apertureCenterYFraction = 0.46f, apertureDiameterFraction = 0.52f),
        WesalFrameSpec("admin_28", "إطار ملكي #28", "Admin Royal #28", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_28, true, 0xFF40C4FFL, apertureCenterYFraction = 0.48f, apertureDiameterFraction = 0.56f),
        WesalFrameSpec("admin_29", "إطار ملكي #29", "Admin Royal #29", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_29, true, 0xFFFF4081L),
        WesalFrameSpec("admin_30", "إطار إدارة #30", "Admin Frame #30", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_30, true, 0xFF00D9E8L, apertureCenterYFraction = 0.41f, apertureDiameterFraction = 0.55f),
        WesalFrameSpec("admin_31", "إطار ملكي #31", "Admin Royal #31", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_31, true, 0xFFFFD54FL, apertureCenterYFraction = 0.50f, apertureDiameterFraction = 0.55f),
        WesalFrameSpec("admin_32", "إطار ملكي #32", "Admin Royal #32", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_32, true, 0xFFFF5252L),
        WesalFrameSpec("admin_33", "إطار إدارة #33", "Admin Frame #33", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_33, true, 0xFF00E676L, apertureCenterYFraction = 0.415f, apertureDiameterFraction = 0.56f),
        WesalFrameSpec("admin_34", "إطار إدارة #34", "Admin Frame #34", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_34, true, 0xFFB388FFL, apertureCenterYFraction = 0.415f, apertureDiameterFraction = 0.54f),
        WesalFrameSpec("admin_35", "إطار إدارة #35", "Admin Frame #35", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_35, true, 0xFF00E5FFL, apertureCenterYFraction = 0.405f, apertureDiameterFraction = 0.56f),
        WesalFrameSpec("admin_36", "إطار إدارة #36", "Admin Frame #36", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_36, true, 0xFFFFD740L, apertureCenterYFraction = 0.41f, apertureDiameterFraction = 0.56f),
        WesalFrameSpec("admin_37", "إطار إدارة #37", "Admin Frame #37", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_37, true, 0xFFFF6E40L, apertureCenterYFraction = 0.41f, apertureDiameterFraction = 0.55f),
        WesalFrameSpec("admin_38", "إطار إدارة #38", "Admin Frame #38", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_38, true, 0xFF00D9E8L, apertureCenterYFraction = 0.40f, apertureDiameterFraction = 0.56f)
    )

    val builtinIcons: List<WesalBuiltinIconSpec> = listOf(
        WesalBuiltinIconSpec("builtin_icon_crown", "التاج الملكي", "Royal Crown", R.drawable.wesal_ic_badge_crown, 0xFFFFD54FL),
        WesalBuiltinIconSpec("builtin_icon_diamond", "الألماسة الفيروزية", "Turquoise Diamond", R.drawable.wesal_ic_badge_diamond, 0xFF00E5FFL),
        WesalBuiltinIconSpec("builtin_icon_fire", "شعلة القوة", "Flame Crest", R.drawable.wesal_ic_badge_fire, 0xFFFF3D00L),
        WesalBuiltinIconSpec("builtin_icon_star", "النجمة الذهبية", "Golden Star", R.drawable.wesal_ic_badge_star, 0xFFFFD740L),
        WesalBuiltinIconSpec("builtin_icon_lightning", "البرق الخاطف", "Lightning Bolt", R.drawable.wesal_ic_badge_lightning, 0xFFFFEA00L),
        WesalBuiltinIconSpec("builtin_icon_heart", "القلب الملكي", "Royal Heart", R.drawable.wesal_ic_badge_heart, 0xFFFF1744L),
        WesalBuiltinIconSpec("builtin_icon_shield", "درع وصال", "WESAL Shield", R.drawable.wesal_ic_badge_shield, 0xFF00D66BL),
        WesalBuiltinIconSpec("builtin_icon_gem", "الجوهرة الأرجوانية", "Violet Gem", R.drawable.wesal_ic_badge_gem, 0xFFD500F9L),
        WesalBuiltinIconSpec("builtin_icon_wing", "الجناح الذهبي", "Golden Wing", R.drawable.wesal_ic_badge_wing, 0xFFFFD54FL),
        WesalBuiltinIconSpec("builtin_icon_crescent", "الهلال والنجمة", "Crescent Star", R.drawable.wesal_ic_badge_crescent, 0xFF00E5FFL),
        WesalBuiltinIconSpec("builtin_icon_phoenix", "شعار العنقاء", "Phoenix Crest", R.drawable.wesal_ic_badge_phoenix, 0xFFFF5252L),
        WesalBuiltinIconSpec("builtin_icon_vip", "وسام VIP", "VIP Crest", R.drawable.wesal_ic_badge_vip, 0xFFFFD54FL)
    )

    val allFrames: List<WesalFrameSpec> = customerFrames + adminFrames

    val builtinGlow: Map<String, Long> = allFrames.associate { it.id to it.accentColorHex }

    fun findActiveFrame(frameId: String?, frameExpiresAt: Long): WesalFrameSpec? {
        if (frameId.isNullOrBlank()) return null
        if (frameExpiresAt > 0L && System.currentTimeMillis() > frameExpiresAt) return null
        return allFrames.firstOrNull { it.id == frameId }
    }

    fun accentColorForFrame(frameId: String?): Color {
        val spec = allFrames.firstOrNull { it.id == frameId }
        return Color(spec?.accentColorHex ?: 0xFF00D9E8L)
    }
}
