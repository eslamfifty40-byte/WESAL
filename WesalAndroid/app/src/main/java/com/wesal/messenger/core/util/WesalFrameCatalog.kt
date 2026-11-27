package com.wesal.messenger.core.util

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.wesal.messenger.R

/** Badge outline shapes (spec §6.1: shape + colour are both part of a seal type). */
enum class VerificationSealShape {
    STARBURST, CIRCLE, SHIELD, DIAMOND, HEXAGON, OCTAGON
}

enum class VerificationSealType(
    val key: String,
    val labelAr: String,
    val labelEn: String,
    val primaryHex: Long,
    val secondaryHex: Long,
    val glowHex: Long,
    val shape: VerificationSealShape = VerificationSealShape.STARBURST
) {
    BLUE("BLUE", "الشارة الزرقاء", "Blue Seal", 0xFF1D9BF0L, 0xFF0A66C2L, 0xFF1D9BF0L),
    BLACK("BLACK", "الشارة السمراء", "Black Seal", 0xFF111827L, 0xFF000000L, 0xFF00D5E8L, VerificationSealShape.HEXAGON),
    GREEN("GREEN", "الشارة الخضراء", "Green Seal", 0xFF00C853L, 0xFF007E33L, 0xFF00E676L, VerificationSealShape.CIRCLE),
    GOLD("GOLD", "الشارة الذهبية", "Gold Seal", 0xFFFFD54FL, 0xFFB8860BL, 0xFFFFD740L, VerificationSealShape.OCTAGON),
    CYAN("CYAN", "الشارة السماوية", "Cyan Seal", 0xFF00D5E8L, 0xFF00838FL, 0xFF00E5FFL, VerificationSealShape.DIAMOND),
    PURPLE("PURPLE", "الشارة البنفسجية", "Purple Seal", 0xFF9C27B0L, 0xFF4A148CL, 0xFFE040FBL, VerificationSealShape.HEXAGON),
    RED("RED", "الشارة الحمراء", "Red Seal", 0xFFFF1744L, 0xFFB71C1CL, 0xFFFF5252L, VerificationSealShape.SHIELD),
    SILVER("SILVER", "الشارة الفضية", "Silver Seal", 0xFFE2E8F0L, 0xFF64748BL, 0xFFCBD5E1L, VerificationSealShape.CIRCLE),
    ROSE("ROSE", "الشارة الوردية", "Rose Seal", 0xFFEC4899L, 0xFF9D174DL, 0xFFFF80ABL, VerificationSealShape.DIAMOND),
    ORANGE("ORANGE", "الشارة البرتقالية", "Orange Seal", 0xFFFF9100L, 0xFFE65100L, 0xFFFFAB40L, VerificationSealShape.OCTAGON),
    INDIGO("INDIGO", "الشارة النيلية", "Indigo Seal", 0xFF6366F1L, 0xFF3730A3L, 0xFF818CF8L),
    EMERALD("EMERALD", "الشارة الزمردية", "Emerald Seal", 0xFF10B981L, 0xFF065F46L, 0xFF34D399L, VerificationSealShape.CIRCLE),
    TURQUOISE("TURQUOISE", "الشارة الفيروزية", "Turquoise Seal", 0xFF06B6D4L, 0xFF0E7490L, 0xFF22D3EEL),
    CRIMSON("CRIMSON", "الشارة القرمزية", "Crimson Seal", 0xFFDC2626L, 0xFF7F1D1DL, 0xFFF87171L, VerificationSealShape.SHIELD),
    ROYAL_BLUE("ROYAL_BLUE", "الأزرق الملكي", "Royal Blue Seal", 0xFF2563EBL, 0xFF1E3A8AL, 0xFF60A5FAL, VerificationSealShape.OCTAGON),
    LIME("LIME", "الشارة الليمونية", "Lime Seal", 0xFF84CC16L, 0xFF3F6212L, 0xFFA3E635L),
    AMBER("AMBER", "الشارة العنبرية", "Amber Seal", 0xFFF59E0BL, 0xFF92400EL, 0xFFFBBF24L),
    MAGENTA("MAGENTA", "الشارة الأرجوانية", "Magenta Seal", 0xFFD946EFL, 0xFF86198FL, 0xFFF0ABACL),
    TEAL("TEAL", "الأزرق المخضر", "Teal Seal", 0xFF14B8A6L, 0xFF115E59L, 0xFF2DD4BFL),
    CORAL("CORAL", "الشارة المرجانية", "Coral Seal", 0xFFFF6B6BL, 0xFFC92A2AL, 0xFFFF8787L),
    VIOLET("VIOLET", "البنفسجي الداكن", "Violet Seal", 0xFF8B5CF6L, 0xFF4C1D95L, 0xFFA78BFAL),
    PLATINUM("PLATINUM", "الشارة البلاتينية", "Platinum Seal", 0xFFF8FAFCL, 0xFF94A3B8L, 0xFFE2E8F0L, VerificationSealShape.OCTAGON),
    BRONZE("BRONZE", "الشارة البرونزية", "Bronze Seal", 0xFFCD7F32L, 0xFF7C4A15L, 0xFFE6A15CL, VerificationSealShape.OCTAGON),
    MINT("MINT", "الشارة النعناعية", "Mint Seal", 0xFF00E5A3L, 0xFF008F64L, 0xFF69F0AEL),
    SAPPHIRE("SAPPHIRE", "الشارة الياقوتية الأزرق", "Sapphire Seal", 0xFF0F52BAL, 0xFF082D69L, 0xFF3B82F6L, VerificationSealShape.DIAMOND),
    RUBY("RUBY", "الشارة الياقوتية الأحمر", "Ruby Seal", 0xFFE0115FL, 0xFF880E4FL, 0xFFFF4081L, VerificationSealShape.SHIELD),
    PEARL("PEARL", "الشارة اللؤلؤية", "Pearl Seal", 0xFFF1F5F9L, 0xFF475569L, 0xFF00D9E8L, VerificationSealShape.CIRCLE),
    OBSIDIAN("OBSIDIAN", "الأوبسيديان الملكي", "Obsidian Seal", 0xFF1E293BL, 0xFF020617L, 0xFFFFD54FL, VerificationSealShape.HEXAGON),
    NEON_YELLOW("NEON_YELLOW", "الأصفر الفسفوري", "Neon Yellow Seal", 0xFFFACC15L, 0xFFA16207L, 0xFFFEF08AL),
    COSMIC("COSMIC", "الشارة الكونية", "Cosmic Seal", 0xFF7C3AEDL, 0xFF0EA5E9L, 0xFF38BDF8L, VerificationSealShape.DIAMOND);

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
        AppearanceColorOption("نيلي", "Indigo Glow", 0xFF6366F1L),
        AppearanceColorOption("قرمزي ساطع", "Crimson Bright", 0xFFDC2626L),
        AppearanceColorOption("أزرق كوبالت", "Cobalt Blue", 0xFF2563EBL),
        AppearanceColorOption("أوركيد", "Orchid", 0xFFC026D3L),
        AppearanceColorOption("تيال غامق", "Deep Teal", 0xFF0D9488L),
        AppearanceColorOption("ليموني مشع", "Neon Lime", 0xFF65A30DL),
        AppearanceColorOption("مرجاني", "Coral", 0xFFF97316L),
        AppearanceColorOption("يشم فاخر", "Royal Jade", 0xFF059669L),
        AppearanceColorOption("نبيتي", "Garnet", 0xFFBE123CL),
        AppearanceColorOption("أكوا", "Aqua", 0xFF0891B2L),
        AppearanceColorOption("جمشت", "Amethyst", 0xFF7C3AEDL),
        AppearanceColorOption("برونزي", "Bronze", 0xFFB45309L),
        AppearanceColorOption("بلاتيني", "Platinum", 0xFF64748BL)
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
        AppearanceColorOption("برونزي", "Bronze", 0xFFD97706L),
        AppearanceColorOption("أبيض ثلجي", "Ice Cyan", 0xFF67E8F9L),
        AppearanceColorOption("وردي فاتح", "Soft Rose", 0xFFFDA4AFL),
        AppearanceColorOption("ماجنتا", "Magenta", 0xFFE879F9L),
        AppearanceColorOption("نعناعي ناعم", "Soft Mint", 0xFF6EE7B7L),
        AppearanceColorOption("أصفر شمسي", "Solar Yellow", 0xFFFACC15L),
        AppearanceColorOption("برتقالي فاتح", "Soft Orange", 0xFFFB923CL),
        AppearanceColorOption("أزرق نيلي", "Periwinkle", 0xFF818CF8L),
        AppearanceColorOption("سماوي فاتح", "Aqua Glow", 0xFF22D3EEL),
        AppearanceColorOption("كريمي ذهبي", "Cream Gold", 0xFFFDE68AL),
        AppearanceColorOption("أخضر ناعم", "Mint Leaf", 0xFF34D399L),
        AppearanceColorOption("ياقوتي فاتح", "Light Ruby", 0xFFFB7185L),
        AppearanceColorOption("رمادي فضي", "Silver", 0xFF94A3B8L)
    )

    val backgroundPresets: List<AppearanceColorOption> = listOf(
        AppearanceColorOption("افتراضي وصال", "Default", 0L),
        AppearanceColorOption("أسود عميق", "Obsidian Black", 0xFF05090BL),
        AppearanceColorOption("كحلي ليلي", "Midnight Navy", 0xFF070D19L),
        AppearanceColorOption("رمادي فحمي", "Charcoal", 0xFF111827L),
        AppearanceColorOption("زمردي ليلي", "Dark Emerald", 0xFF04140FL),
        AppearanceColorOption("أرجواني ليلي", "Dark Plum", 0xFF12081CL),
        AppearanceColorOption("عنابي ليلي", "Dark Bordeaux", 0xFF14060AL),
        AppearanceColorOption("محيط ليلي", "Deep Ocean", 0xFF041014L),
        AppearanceColorOption("عقيق ليلي", "Dark Onyx", 0xFF0E0A05L),
        AppearanceColorOption("نيلي ليلي", "Indigo Night", 0xFF090B1FL),
        AppearanceColorOption("رمادي مخملي", "Velvet Gray", 0xFF0A0A0EL),
        AppearanceColorOption("غابة داكنة", "Forest Black", 0xFF030F09L),
        AppearanceColorOption("أبيض نقي", "Pure White", 0xFFFFFFFFL),
        AppearanceColorOption("ثلجي ناعم", "Ice Mist", 0xFFF3F6F8L),
        AppearanceColorOption("كريمي دافئ", "Warm Cream", 0xFFFAF7F2L),
        AppearanceColorOption("أزرق ثلجي", "Frost Blue", 0xFFF0F7FFL),
        AppearanceColorOption("نعناعي فاتح", "Mint Tint", 0xFFF0FDF4L),
        AppearanceColorOption("رمادي أنيق", "Slate Mist", 0xFFF1F5F9L),
        AppearanceColorOption("وردي خفيف", "Blush Tint", 0xFFFFF1F2L),
        AppearanceColorOption("لافندر خفيف", "Lavender Mist", 0xFFF5F3FFL),
        AppearanceColorOption("فيروزي خفيف", "Cyan Mist", 0xFFECFEFFL),
        AppearanceColorOption("عنبري خفيف", "Amber Tint", 0xFFFFFBEBL),
        AppearanceColorOption("لؤلؤي فاتح", "Pearl Light", 0xFFF8FAFCL),
        AppearanceColorOption("سماوي باهت", "Sky Pale", 0xFFE0F2FEL)
    )

    val surfacePresets: List<AppearanceColorOption> = listOf(
        AppearanceColorOption("افتراضي وصال", "Default", 0L),
        AppearanceColorOption("سطح ليلي", "Dark Surface", 0xFF0B1115L),
        AppearanceColorOption("أردوازي داكن", "Dark Slate", 0xFF131C22L),
        AppearanceColorOption("كحلي بارز", "Navy Card", 0xFF0F172AL),
        AppearanceColorOption("غابة داكنة", "Forest Card", 0xFF082018L),
        AppearanceColorOption("فحمي مخملي", "Velvet Gray", 0xFF18181BL),
        AppearanceColorOption("عنابي داكن", "Bordeaux Card", 0xFF210B12L),
        AppearanceColorOption("بنفسجي داكن", "Plum Card", 0xFF1A102EL),
        AppearanceColorOption("محيطي داكن", "Ocean Card", 0xFF0A1D24L),
        AppearanceColorOption("ذهبي داكن", "Onyx Card", 0xFF1C160BL),
        AppearanceColorOption("نيلي داكن", "Indigo Card", 0xFF131632L),
        AppearanceColorOption("جرانيت داكن", "Granite Card", 0xFF1C2026L),
        AppearanceColorOption("أبيض بطاقات", "White Card", 0xFFFFFFFFL),
        AppearanceColorOption("لؤلؤي فاتح", "Pearl Card", 0xFFF8FAFCL),
        AppearanceColorOption("سماوي فاتح", "Sky Card", 0xFFEFF6FFL),
        AppearanceColorOption("زمردي فاتح", "Mint Card", 0xFFECFDF5L),
        AppearanceColorOption("رملي ناعم", "Sand Card", 0xFFFEFCE8L),
        AppearanceColorOption("بنفسجي فاتح", "Lavender Card", 0xFFF5F3FFL),
        AppearanceColorOption("وردي فاتح", "Rose Card", 0xFFFFE4E6L),
        AppearanceColorOption("أكوا فاتح", "Aqua Card", 0xFFCFFAFEL),
        AppearanceColorOption("ثلجي رمادي", "Cool Card", 0xFFE2E8F0L),
        AppearanceColorOption("كريمي فاتح", "Ivory Card", 0xFFFEF3C7L),
        AppearanceColorOption("أخضر باهت", "Sage Card", 0xFFDCFCE7L),
        AppearanceColorOption("أزرق فاتح", "Ice Card", 0xFFE0ECFFL)
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
        AppearanceColorOption("ياقوتي", "Crimson", 0xFFE11D48L),
        AppearanceColorOption("ليموني", "Lime", 0xFF84CC16L),
        AppearanceColorOption("نيلي", "Indigo", 0xFF6366F1L),
        AppearanceColorOption("أكوا", "Aqua", 0xFF06B6D4L),
        AppearanceColorOption("تيال", "Teal", 0xFF14B8A6L),
        AppearanceColorOption("أوركيد", "Orchid", 0xFFD946EFL),
        AppearanceColorOption("برونزي", "Bronze", 0xFFD97706L),
        AppearanceColorOption("كوبالت", "Cobalt", 0xFF2563EBL),
        AppearanceColorOption("فضي", "Silver", 0xFFCBD5E1L),
        AppearanceColorOption("يشم", "Jade", 0xFF10B981L),
        AppearanceColorOption("نبيتي", "Garnet", 0xFFBE123CL)
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
    ADMIN,
    OFFICIAL,
    ANIMATED
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
    val defaultAnimationStyle: String = "PULSE_GLOW",
    /**
     * Luxury tier for the «إطارات متحركة» set (owner requirement, round 5):
     * 1 = بسيط (simple), 2 = متوسط (medium), 3 = فاخر (luxury), 4 = ملكي (royal).
     * 0 = untiered (all pre-existing customer/admin frames). Tier 4 frames also
     * surface in the ADMIN picker.
     */
    val tier: Int = 0
)

object WesalFrameCatalog {
    const val DEFAULT_ALL_CUSTOMERS_FRAME_ID = "all_customers"

    // Default frame for EVERY customer: a BASIC customer ring (cust_01 artwork), deliberately
    // distinct from the royal lion ADMIN crest. The royal 1536x1536 artwork is reserved for
    // the ADMIN frame (WesalFrameLibrary.ADMIN_FRAME_ID = "wesal_admin_golden"), which
    // toWesalUser forces onto the admin accounts (numeric IDs 10001/10002) only.
    // Zero-write migration: existing user docs already carry frameId = "all_customers";
    // re-pointing this spec's artwork changes what every one of them renders at once,
    // without touching any stored data.
    val defaultCustomerFrame = WesalFrameSpec(
        id = DEFAULT_ALL_CUSTOMERS_FRAME_ID,
        titleAr = "إطار العملاء الأساسي الافتراضي",
        titleEn = "Basic Default Customer Frame (All Customers)",
        category = FrameCategory.DEFAULT_ALL_CUSTOMERS,
        resId = R.drawable.wesal_frame_cust_01,
        overlayOnTop = true,
        accentColorHex = 0xFFFFD54FL
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

    /**
     * «إطارات متحركة» (round 5): the owner's VIP-style frames, extracted from his two
     * collages (hidden_audit/FRAMES_NEW_INVENTORY.md) plus the best of the extraction's
     * skipped pool. Aperture centre-Y / diameter and glow colour are MEASURED per frame,
     * never guessed: anim_01..anim_28 carry the extraction inventory's values;
     * anim_29..anim_49 were measured from the extractions' alpha channels with the same
     * 720-ray radial inner-edge method (validated against the inventory's frames to
     * within ±0.017 Ø). Frames carrying baked-in text/rank banners (TOP1, NO.1, …) were
     * excluded from the expansion picks. Assets ship as WebP (quality 90, alpha kept) —
     * 49 frames ≈ 4.7MB instead of ≈ 15.9MB of PNG; PNG masters stay in hidden_audit.
     * TIERS (owner requirement): the list is ordered by [WesalFrameSpec.tier] —
     * 1 بسيط (Simple) → 2 متوسط (Medium) → 3 فاخر (Luxury) → 4 ملكي (Royal) — and every
     * title carries its tier name. Tier-4 (crowns/lions/dragons, the most ornate) ALSO
     * surfaces in the ADMIN picker (see WesalFrameLibrary.pickerItems). All are flagged
     * animated (PULSE_GLOW, ice/crystal ones SHIMMER); the renderer adds the living
     * treatment (pulsing glow disc, shine sweep, sparkles) and the admin can flip any
     * of them to static from the admin panel via the frame_library override doc.
     * Aperture centre-X is not modelled: measured centres sit at x≈0.49–0.50, and the
     * few higher readings (anim_19/anim_23) were visually verified as ornament-pull
     * artefacts — their openings are centred.
     */
    val animatedFrames: List<WesalFrameSpec> = listOf(
        // ——— Tier 1: بسيط (Simple) ———
        WesalFrameSpec("anim_03", "بسيط · إكليل الغار الذهبي", "Simple · Golden Laurel Wreath", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_03, true, 0xFFF8EAA4L, apertureCenterYFraction = 0.495f, apertureDiameterFraction = 0.434f, defaultAnimated = true, tier = 1),
        WesalFrameSpec("anim_16", "بسيط · حلقة ذهبية", "Simple · Gold Ring", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_16, true, 0xFFFDD89BL, apertureCenterYFraction = 0.495f, apertureDiameterFraction = 0.453f, defaultAnimated = true, tier = 1),
        WesalFrameSpec("anim_25", "بسيط · حلقة فضية زرقاء", "Simple · Silver Blue Ring", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_25, true, 0xFFEEF6FEL, apertureCenterYFraction = 0.445f, apertureDiameterFraction = 0.523f, defaultAnimated = true, tier = 1),
        WesalFrameSpec("anim_28", "بسيط · إكليل أخضر شاحب", "Simple · Pale Green Wreath", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_28, true, 0xFFEFFEE6L, apertureCenterYFraction = 0.445f, apertureDiameterFraction = 0.467f, defaultAnimated = true, tier = 1),
        WesalFrameSpec("anim_30", "بسيط · إكليل الغار والنجمة", "Simple · Laurel & Star", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_30, true, 0xFFFBD27CL, apertureCenterYFraction = 0.456f, apertureDiameterFraction = 0.559f, defaultAnimated = true, tier = 1),
        WesalFrameSpec("anim_35", "بسيط · إكليل غار ذهبي", "Simple · Golden Laurel", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_35, true, 0xFFFBD58BL, apertureCenterYFraction = 0.455f, apertureDiameterFraction = 0.520f, defaultAnimated = true, tier = 1),
        WesalFrameSpec("anim_40", "بسيط · إطار الاحتفال الوردي", "Simple · Pink Celebration", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_40, true, 0xFFF7C8C8L, apertureCenterYFraction = 0.488f, apertureDiameterFraction = 0.500f, defaultAnimated = true, tier = 1),
        // ——— Tier 2: متوسط (Medium) ———
        WesalFrameSpec("anim_04", "متوسط · حلقة ذهبية ملكية", "Medium · Ornate Gold Ring", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_04, true, 0xFFFDDCB0L, apertureCenterYFraction = 0.497f, apertureDiameterFraction = 0.429f, defaultAnimated = true, tier = 2),
        WesalFrameSpec("anim_06", "متوسط · إكليل الغار الأحمر", "Medium · Red Gem Laurel", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_06, true, 0xFFFEE6A2L, apertureCenterYFraction = 0.533f, apertureDiameterFraction = 0.416f, defaultAnimated = true, tier = 2),
        WesalFrameSpec("anim_09", "متوسط · وردي فضي مزخرف", "Medium · Pink Silver Ornate", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_09, true, 0xFFFDE0F2L, apertureCenterYFraction = 0.495f, apertureDiameterFraction = 0.542f, defaultAnimated = true, tier = 2),
        WesalFrameSpec("anim_18", "متوسط · أصفر مخضر مزخرف", "Medium · Yellow Green Ornate", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_18, true, 0xFFF1F58DL, apertureCenterYFraction = 0.445f, apertureDiameterFraction = 0.505f, defaultAnimated = true, tier = 2),
        WesalFrameSpec("anim_20", "متوسط · بنفسجي وردي مزخرف", "Medium · Purple Pink Ornate", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_20, true, 0xFFF8B0FEL, apertureCenterYFraction = 0.495f, apertureDiameterFraction = 0.566f, defaultAnimated = true, tier = 2),
        WesalFrameSpec("anim_21", "متوسط · حلقة جليدية زرقاء", "Medium · Ice Blue Ring", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_21, true, 0xFFBBEBFEL, apertureCenterYFraction = 0.445f, apertureDiameterFraction = 0.472f, defaultAnimated = true, defaultAnimationStyle = "SHIMMER", tier = 2),
        WesalFrameSpec("anim_22", "متوسط · ذهبي وأخضر مزخرف", "Medium · Gold Green Ornate", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_22, true, 0xFFFDE49EL, apertureCenterYFraction = 0.495f, apertureDiameterFraction = 0.486f, defaultAnimated = true, tier = 2),
        WesalFrameSpec("anim_23", "متوسط · ذهبي وردي ناعم", "Medium · Rose Gold Pink", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_23, true, 0xFFF6CCBFL, apertureCenterYFraction = 0.495f, apertureDiameterFraction = 0.523f, defaultAnimated = true, tier = 2),
        WesalFrameSpec("anim_31", "متوسط · إكليل وردي بنفسجي", "Medium · Pink Purple Laurel", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_31, true, 0xFFF9DEBBL, apertureCenterYFraction = 0.422f, apertureDiameterFraction = 0.551f, defaultAnimated = true, tier = 2),
        WesalFrameSpec("anim_33", "متوسط · حلقة لافندر فضية", "Medium · Lavender Silver Ring", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_33, true, 0xFFD8D6FBL, apertureCenterYFraction = 0.489f, apertureDiameterFraction = 0.531f, defaultAnimated = true, defaultAnimationStyle = "SHIMMER", tier = 2),
        WesalFrameSpec("anim_38", "متوسط · حلقة وردية مجنحة", "Medium · Pink Winged Ring", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_38, true, 0xFFFCD0D7L, apertureCenterYFraction = 0.491f, apertureDiameterFraction = 0.500f, defaultAnimated = true, tier = 2),
        WesalFrameSpec("anim_48", "متوسط · حلقة الكريستال البنفسجية", "Medium · Violet Crystal Ring", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_48, true, 0xFFE0DBFBL, apertureCenterYFraction = 0.467f, apertureDiameterFraction = 0.484f, defaultAnimated = true, defaultAnimationStyle = "SHIMMER", tier = 2),
        // ——— Tier 3: فاخر (Luxury) ———
        WesalFrameSpec("anim_01", "فاخر · أجنحة ذهبية وهلال", "Luxury · Golden Wings & Crescent", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_01, true, 0xFFFCEEC2L, apertureCenterYFraction = 0.447f, apertureDiameterFraction = 0.503f, defaultAnimated = true, tier = 3),
        WesalFrameSpec("anim_05", "فاخر · أجنحة البرق الزرقاء", "Luxury · Lightning Wings", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_05, true, 0xFFE0EAD3L, apertureCenterYFraction = 0.497f, apertureDiameterFraction = 0.419f, defaultAnimated = true, tier = 3),
        WesalFrameSpec("anim_07", "فاخر · أجنحة جليدية", "Luxury · Ice Wings", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_07, true, 0xFFB5EAFFL, apertureCenterYFraction = 0.537f, apertureDiameterFraction = 0.390f, defaultAnimated = true, defaultAnimationStyle = "SHIMMER", tier = 3),
        WesalFrameSpec("anim_10", "فاخر · أجنحة ذهبية حمراء", "Luxury · Gold Red Wings", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_10, true, 0xFFFEDBA1L, apertureCenterYFraction = 0.495f, apertureDiameterFraction = 0.523f, defaultAnimated = true, tier = 3),
        WesalFrameSpec("anim_13", "فاخر · أجنحة فضية", "Luxury · Silver Wings", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_13, true, 0xFFF9F6E7L, apertureCenterYFraction = 0.495f, apertureDiameterFraction = 0.566f, defaultAnimated = true, tier = 3),
        WesalFrameSpec("anim_14", "فاخر · أبيض وذهبي مزخرف", "Luxury · White Gold Ornate", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_14, true, 0xFFFFF1CCL, apertureCenterYFraction = 0.545f, apertureDiameterFraction = 0.561f, defaultAnimated = true, tier = 3),
        WesalFrameSpec("anim_17", "فاخر · ذهبي أصفر مزخرف", "Luxury · Yellow Gold Ornate", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_17, true, 0xFFFCF5BBL, apertureCenterYFraction = 0.495f, apertureDiameterFraction = 0.528f, defaultAnimated = true, tier = 3),
        WesalFrameSpec("anim_19", "فاخر · ذهبي مزخرف بأجنحة", "Luxury · Winged Gold Ornate", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_19, true, 0xFFFEE9C1L, apertureCenterYFraction = 0.495f, apertureDiameterFraction = 0.523f, defaultAnimated = true, tier = 3),
        WesalFrameSpec("anim_24", "فاخر · أجنحة كريستال جليدية", "Luxury · Ice Crystal Wings", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_24, true, 0xFFA0EEFEL, apertureCenterYFraction = 0.445f, apertureDiameterFraction = 0.585f, defaultAnimated = true, defaultAnimationStyle = "SHIMMER", tier = 3),
        WesalFrameSpec("anim_26", "فاخر · ذهبي مزخرف كلاسيك", "Luxury · Classic Gold Ornate", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_26, true, 0xFFFDEAAFL, apertureCenterYFraction = 0.495f, apertureDiameterFraction = 0.547f, defaultAnimated = true, tier = 3),
        WesalFrameSpec("anim_29", "فاخر · إكليل بأجنحة بنفسجية", "Luxury · Purple Wing Laurel", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_29, true, 0xFFF9E0BFL, apertureCenterYFraction = 0.414f, apertureDiameterFraction = 0.570f, defaultAnimated = true, tier = 3),
        WesalFrameSpec("anim_34", "فاخر · بنفسجي ذهبي مزخرف", "Luxury · Purple Gold Ornate", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_34, true, 0xFFF8B28FL, apertureCenterYFraction = 0.498f, apertureDiameterFraction = 0.520f, defaultAnimated = true, tier = 3),
        WesalFrameSpec("anim_36", "فاخر · إكليل وردي متوج", "Luxury · Crowned Rose Laurel", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_36, true, 0xFFFBD1B2L, apertureCenterYFraction = 0.448f, apertureDiameterFraction = 0.508f, defaultAnimated = true, tier = 3),
        WesalFrameSpec("anim_37", "فاخر · أجنحة فضية جليدية", "Luxury · Silver Ice Wings", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_37, true, 0xFFF0EEEDL, apertureCenterYFraction = 0.444f, apertureDiameterFraction = 0.500f, defaultAnimated = true, defaultAnimationStyle = "SHIMMER", tier = 3),
        WesalFrameSpec("anim_41", "فاخر · عنقاء خضراء ذهبية", "Luxury · Green Gold Phoenix", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_41, true, 0xFFF0E195L, apertureCenterYFraction = 0.462f, apertureDiameterFraction = 0.496f, defaultAnimated = true, tier = 3),
        WesalFrameSpec("anim_42", "فاخر · أجنحة ذهبية متلألئة", "Luxury · Sparkling Gold Wings", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_42, true, 0xFFFAD395L, apertureCenterYFraction = 0.414f, apertureDiameterFraction = 0.488f, defaultAnimated = true, defaultAnimationStyle = "SHIMMER", tier = 3),
        WesalFrameSpec("anim_46", "فاخر · إكليل الأجنحة الذهبي", "Luxury · Golden Wing Wreath", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_46, true, 0xFFFAE5B7L, apertureCenterYFraction = 0.493f, apertureDiameterFraction = 0.461f, defaultAnimated = true, tier = 3),
        WesalFrameSpec("anim_47", "فاخر · إكليل الياقوت البنفسجي", "Luxury · Violet Gem Wreath", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_47, true, 0xFFFAD296L, apertureCenterYFraction = 0.496f, apertureDiameterFraction = 0.445f, defaultAnimated = true, tier = 3),
        WesalFrameSpec("anim_49", "فاخر · هلال القلعة الذهبي", "Luxury · Golden Castle Crescent", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_49, true, 0xFFFAD772L, apertureCenterYFraction = 0.489f, apertureDiameterFraction = 0.430f, defaultAnimated = true, tier = 3),
        // ——— Tier 4: ملكي (Royal) ———
        WesalFrameSpec("anim_02", "ملكي · تنينان ذهبيان", "Royal · Twin Gold Dragons", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_02, true, 0xFFFBE3A3L, apertureCenterYFraction = 0.536f, apertureDiameterFraction = 0.449f, defaultAnimated = true, tier = 4),
        WesalFrameSpec("anim_08", "ملكي · أجنحة جليدية متوجة", "Royal · Crowned Ice Wings", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_08, true, 0xFFBBECFEL, apertureCenterYFraction = 0.537f, apertureDiameterFraction = 0.382f, defaultAnimated = true, defaultAnimationStyle = "SHIMMER", tier = 4),
        WesalFrameSpec("anim_11", "ملكي · ذهبي أحمر مزخرف", "Royal · Gold Red Ornate", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_11, true, 0xFFFDDDC1L, apertureCenterYFraction = 0.495f, apertureDiameterFraction = 0.598f, defaultAnimated = true, tier = 4),
        WesalFrameSpec("anim_12", "ملكي · تاج ذهبي", "Royal · Gold Crown Ring", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_12, true, 0xFFFEE6ABL, apertureCenterYFraction = 0.445f, apertureDiameterFraction = 0.491f, defaultAnimated = true, tier = 4),
        WesalFrameSpec("anim_15", "ملكي · ذهبي ملكي فاخر", "Royal · Royal Gold Ornate", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_15, true, 0xFFFEECC6L, apertureCenterYFraction = 0.445f, apertureDiameterFraction = 0.598f, defaultAnimated = true, tier = 4),
        WesalFrameSpec("anim_27", "ملكي · تيجان بنفسجية ذهبية", "Royal · Purple Crown Gold", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_27, true, 0xFFFDE1BAL, apertureCenterYFraction = 0.495f, apertureDiameterFraction = 0.579f, defaultAnimated = true, tier = 4),
        WesalFrameSpec("anim_32", "ملكي · تاج ذهبي أحمر", "Royal · Gold Red Crown", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_32, true, 0xFFFBBA5EL, apertureCenterYFraction = 0.521f, apertureDiameterFraction = 0.535f, defaultAnimated = true, tier = 4),
        WesalFrameSpec("anim_39", "ملكي · إكليل التاج الذهبي", "Royal · Golden Crown Laurel", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_39, true, 0xFFFCC077L, apertureCenterYFraction = 0.421f, apertureDiameterFraction = 0.500f, defaultAnimated = true, tier = 4),
        WesalFrameSpec("anim_43", "ملكي · قصر الزمرد الذهبي", "Royal · Emerald Gold Palace", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_43, true, 0xFFFAE0A2L, apertureCenterYFraction = 0.457f, apertureDiameterFraction = 0.488f, defaultAnimated = true, tier = 4),
        WesalFrameSpec("anim_44", "ملكي · تاج العنقاء الذهبي", "Royal · Phoenix Crown", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_44, true, 0xFFFCD381L, apertureCenterYFraction = 0.453f, apertureDiameterFraction = 0.480f, defaultAnimated = true, tier = 4),
        WesalFrameSpec("anim_45", "ملكي · أجنحة الأسود الذهبية", "Royal · Lion Wings", FrameCategory.ANIMATED, R.drawable.wesal_frame_new_45, true, 0xFFFBD382L, apertureCenterYFraction = 0.445f, apertureDiameterFraction = 0.460f, defaultAnimated = true, tier = 4)
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

    val allFrames: List<WesalFrameSpec> = customerFrames + adminFrames + animatedFrames

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
