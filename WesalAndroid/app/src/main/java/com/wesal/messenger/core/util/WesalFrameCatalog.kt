package com.wesal.messenger.core.util

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.wesal.messenger.R

enum class VerificationSealType(val key: String, val labelAr: String, val labelEn: String) {
    BLUE("BLUE", "الشارة الزرقاء", "Blue Seal"),
    BLACK("BLACK", "الشارة السمراء", "Black Seal"),
    GREEN("GREEN", "الشارة الخضراء", "Green Seal"),
    GOLD("GOLD", "الشارة الذهبية", "Gold Seal"),
    CYAN("CYAN", "الشارة السماوية", "Cyan Seal"),
    PURPLE("PURPLE", "الشارة البنفسجية", "Purple Seal"),
    RED("RED", "الشارة الحمراء", "Red Seal"),
    SILVER("SILVER", "الشارة الفضية", "Silver Seal"),
    ROSE("ROSE", "الشارة الوردية", "Rose Seal"),
    ORANGE("ORANGE", "الشارة البرتقالية", "Orange Seal");

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
    BLUE("BLUE", "أزرق نيون", "Neon Blue", Color(0xFF00D9E8), Color(0xFF5BEBFF), Color(0xFF006080));

    companion object {
        fun fromKey(raw: String?): NeonColorPreset =
            entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: NONE
    }
}

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
    val accentColorHex: Long = 0xFF00D9E8L
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
        accentColorHex = 0xFF00D9E8L
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
        WesalFrameSpec("admin_01", "إطار إدارة #1", "Admin Frame #1", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_01, true, 0xFFFFD54FL),
        WesalFrameSpec("admin_02", "إطار إدارة #2", "Admin Frame #2", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_02, true, 0xFF00D9E8L),
        WesalFrameSpec("admin_03", "إطار إدارة #3", "Admin Frame #3", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_03, true, 0xFFFF5252L),
        WesalFrameSpec("admin_04", "إطار إدارة #4", "Admin Frame #4", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_04, true, 0xFF00E676L),
        WesalFrameSpec("admin_05", "إطار إدارة #5", "Admin Frame #5", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_05, true, 0xFF40C4FFL),
        WesalFrameSpec("admin_06", "إطار إدارة #6", "Admin Frame #6", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_06, true, 0xFFE040FBL),
        WesalFrameSpec("admin_07", "إطار إدارة #7", "Admin Frame #7", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_07, true, 0xFFFFD740L),
        WesalFrameSpec("admin_08", "إطار إدارة #8", "Admin Frame #8", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_08, true, 0xFF69F0AEL),
        WesalFrameSpec("admin_09", "إطار إدارة #9", "Admin Frame #9", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_09, true, 0xFFFF4081L),
        WesalFrameSpec("admin_10", "إطار ملكي #10", "Admin Royal #10", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_10, true, 0xFFFFD54FL),
        WesalFrameSpec("admin_11", "إطار ملكي #11", "Admin Royal #11", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_11, true, 0xFF00D9E8L),
        WesalFrameSpec("admin_12", "إطار إدارة #12", "Admin Frame #12", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_12, true, 0xFFFFAB40L),
        WesalFrameSpec("admin_13", "إطار ملكي #13", "Admin Royal #13", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_13, true, 0xFFFF5252L),
        WesalFrameSpec("admin_14", "إطار إدارة #14", "Admin Frame #14", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_14, true, 0xFF00E676L),
        WesalFrameSpec("admin_15", "إطار ملكي #15", "Admin Royal #15", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_15, true, 0xFFB388FFL),
        WesalFrameSpec("admin_16", "إطار ملكي #16", "Admin Royal #16", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_16, true, 0xFF00E5FFL),
        WesalFrameSpec("admin_17", "إطار ملكي #17", "Admin Royal #17", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_17, true, 0xFFFFD740L),
        WesalFrameSpec("admin_18", "إطار ملكي #18", "Admin Royal #18", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_18, true, 0xFFFF6E40L),
        WesalFrameSpec("admin_19", "إطار ملكي #19", "Admin Royal #19", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_19, true, 0xFF69F0AEL),
        WesalFrameSpec("admin_20", "إطار ملكي #20", "Admin Royal #20", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_20, true, 0xFFFFD54FL),
        WesalFrameSpec("admin_21", "إطار ملكي #21", "Admin Royal #21", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_21, true, 0xFFD4AF37L),
        WesalFrameSpec("admin_22", "إطار ملكي #22", "Admin Royal #22", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_22, true, 0xFFFF5252L),
        WesalFrameSpec("admin_23", "إطار إدارة #23", "Admin Frame #23", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_23, true, 0xFF00D9E8L),
        WesalFrameSpec("admin_24", "إطار ملكي #24", "Admin Royal #24", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_24, true, 0xFFFFD740L),
        WesalFrameSpec("admin_25", "إطار ملكي #25", "Admin Royal #25", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_25, true, 0xFFB388FFL),
        WesalFrameSpec("admin_26", "إطار ملكي #26", "Admin Royal #26", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_26, true, 0xFF00E676L),
        WesalFrameSpec("admin_27", "إطار ملكي #27", "Admin Royal #27", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_27, true, 0xFFFFAB40L),
        WesalFrameSpec("admin_28", "إطار ملكي #28", "Admin Royal #28", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_28, true, 0xFF40C4FFL),
        WesalFrameSpec("admin_29", "إطار ملكي #29", "Admin Royal #29", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_29, true, 0xFFFF4081L),
        WesalFrameSpec("admin_30", "إطار إدارة #30", "Admin Frame #30", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_30, true, 0xFF00D9E8L),
        WesalFrameSpec("admin_31", "إطار ملكي #31", "Admin Royal #31", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_31, true, 0xFFFFD54FL),
        WesalFrameSpec("admin_32", "إطار ملكي #32", "Admin Royal #32", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_32, true, 0xFFFF5252L),
        WesalFrameSpec("admin_33", "إطار إدارة #33", "Admin Frame #33", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_33, true, 0xFF00E676L),
        WesalFrameSpec("admin_34", "إطار إدارة #34", "Admin Frame #34", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_34, true, 0xFFB388FFL),
        WesalFrameSpec("admin_35", "إطار إدارة #35", "Admin Frame #35", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_35, true, 0xFF00E5FFL),
        WesalFrameSpec("admin_36", "إطار إدارة #36", "Admin Frame #36", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_36, true, 0xFFFFD740L),
        WesalFrameSpec("admin_37", "إطار إدارة #37", "Admin Frame #37", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_37, true, 0xFFFF6E40L),
        WesalFrameSpec("admin_38", "إطار إدارة #38", "Admin Frame #38", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_38, true, 0xFF00D9E8L),
        WesalFrameSpec("admin_39", "إطار الإدارة الملكي #39", "Admin Royal Crest #39", FrameCategory.ADMIN, R.drawable.wesal_frame_admin_39, true, 0xFFFF1744L)
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
