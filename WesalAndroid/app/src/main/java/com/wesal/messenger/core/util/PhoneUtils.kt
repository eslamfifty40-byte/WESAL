package com.wesal.messenger.core.util

import android.content.Context
import android.telephony.TelephonyManager
import com.wesal.messenger.authentication.CountryCatalog
import com.wesal.messenger.domain.model.CountryCode
import java.security.MessageDigest
import java.util.Locale

data class PhoneValidationResult(
    val isValid: Boolean,
    val e164: String? = null,
    val errorAr: String = "",
    val errorEn: String = ""
)

object PhoneUtils {

    // National significant number length ranges (digits AFTER country dial code, leading 0 stripped where local trunk 0 applies)
    private val countryLengthBounds: Map<String, IntRange> = mapOf(
        "EG" to 10..10,   // +20 + 10 digits (010/011/012/015 -> 11 digits with trunk 0)
        "SA" to 9..9,     // +966 5xxxxxxxx
        "AE" to 9..9,     // +971 5xxxxxxxx
        "KW" to 8..8,
        "QA" to 8..8,
        "BH" to 8..8,
        "OM" to 8..8,
        "JO" to 9..9,
        "PS" to 9..9,
        "LB" to 7..8,
        "IQ" to 10..10,
        "SY" to 9..9,
        "YE" to 9..9,
        "SD" to 9..9,
        "LY" to 9..9,
        "TN" to 8..8,
        "DZ" to 9..9,
        "MA" to 9..9,
        "TR" to 10..10,
        "US" to 10..10,
        "CA" to 10..10,
        "GB" to 9..10,
        "DE" to 9..11,
        "FR" to 9..9,
        "IN" to 10..10,
        "PK" to 10..10,
        "ID" to 9..12
    )

    // Countries where domestic trunk leading "0" is normally stripped when converting to E.164
    private val stripTrunkZeroCountries = setOf(
        "EG", "SA", "AE", "JO", "PS", "IQ", "SY", "YE", "SD", "LY", "DZ", "MA",
        "LB", "TR", "GB", "DE", "FR", "IT", "IN", "PK", "ID", "MY", "AU", "NZ",
        "JP", "KR", "ZA", "NG", "KE", "GH", "BR", "UA", "RU"
    )

    fun resolveDeviceDialCode(context: Context): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val iso = tm?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country
        return CountryCatalog.countries.firstOrNull {
            it.iso.equals(iso, ignoreCase = true)
        }?.dialCode ?: Constants.DEFAULT_COUNTRY_DIAL
    }

    /**
     * Validates a separated country + local phone input per country rules (Sec 9).
     * Egypt: requires 11 local digits starting with 010/011/012/015 (or 10 digits without leading 0).
     * Other countries: applies per-country length and E.164 rules without forcing 11 digits.
     */
    fun validateForCountry(country: CountryCode, rawLocalInput: String): PhoneValidationResult {
        val cleaned = rawLocalInput.trim().replace(Regex("[\\s\\-().]"), "")
        if (cleaned.isEmpty()) {
            return PhoneValidationResult(
                isValid = false,
                errorAr = "يرجى إدخال رقم الهاتف",
                errorEn = "Phone number is required"
            )
        }

        // If user pasted full E.164 with matching dial code, strip dial code cleanly
        var localDigits = when {
            cleaned.startsWith(country.dialCode) -> cleaned.removePrefix(country.dialCode)
            cleaned.startsWith("00" + country.dialCode.drop(1)) -> cleaned.removePrefix("00" + country.dialCode.drop(1))
            else -> cleaned
        }.filter { it.isDigit() }

        if (localDigits.isEmpty()) {
            return PhoneValidationResult(
                isValid = false,
                errorAr = "رقم الهاتف غير صالح",
                errorEn = "Invalid phone number"
            )
        }

        if (country.iso == "EG") {
            // Normalize Egypt trunk 0 or direct 10 digits
            val withZero = when {
                localDigits.startsWith("0") -> localDigits
                localDigits.length == 10 && localDigits.startsWith("1") -> "0$localDigits"
                else -> localDigits
            }
            val validPrefix = withZero.startsWith("010") ||
                withZero.startsWith("011") ||
                withZero.startsWith("012") ||
                withZero.startsWith("015")
            if (withZero.length != 11 || !validPrefix) {
                return PhoneValidationResult(
                    isValid = false,
                    errorAr = "رقم مصر يجب أن يكون 11 رقماً ويبدأ بـ 010 أو 011 أو 012 أو 015",
                    errorEn = "Egyptian phone numbers must be 11 digits starting with 010, 011, 012, or 015"
                )
            }
            val e164 = "+20${withZero.drop(1)}"
            return PhoneValidationResult(isValid = true, e164 = e164)
        }

        // International non-Egypt rules
        if (country.iso in stripTrunkZeroCountries && localDigits.startsWith("0")) {
            localDigits = localDigits.drop(1)
        }

        val bounds = countryLengthBounds[country.iso] ?: 6..13
        if (localDigits.length !in bounds) {
            return PhoneValidationResult(
                isValid = false,
                errorAr = "طول الرقم لا يطابق تنسيق ${country.nameAr} (${bounds.first}–${bounds.last} أرقام بدون رمز الدولة)",
                errorEn = "Number length does not match ${country.nameEn} (${bounds.first}–${bounds.last} digits)"
            )
        }

        val e164 = "${country.dialCode}$localDigits"
        val totalDigits = e164.drop(1).length
        if (totalDigits !in 7..15) {
            return PhoneValidationResult(
                isValid = false,
                errorAr = "رقم الهاتف الدولي غير صالح",
                errorEn = "Invalid international phone number"
            )
        }

        return PhoneValidationResult(isValid = true, e164 = e164)
    }

    /**
     * Normalizes local/international phone numbers to E.164 format.
     */
    fun normalizeToE164(rawInput: String, defaultDialCode: String = Constants.DEFAULT_COUNTRY_DIAL): String? {
        val cleaned = rawInput.trim().replace(Regex("[\\s\\-().]"), "")
        if (cleaned.isEmpty()) return null

        val e164 = when {
            cleaned.startsWith("+") -> "+" + cleaned.drop(1).filter { it.isDigit() }
            cleaned.startsWith("00") -> "+" + cleaned.drop(2).filter { it.isDigit() }
            cleaned.startsWith("0") -> defaultDialCode + cleaned.drop(1).filter { it.isDigit() }
            cleaned.startsWith("20") && cleaned.length == 12 -> "+" + cleaned.filter { it.isDigit() }
            else -> defaultDialCode + cleaned.filter { it.isDigit() }
        }

        val (country, local) = CountryCatalog.detectFromE164(e164)
        val validated = validateForCountry(country, local)
        if (validated.isValid) return validated.e164

        val digitsCount = e164.drop(1).length
        return if (e164.startsWith("+") && digitsCount in 7..15) e164 else null
    }

    fun sha256Hash(e164Phone: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(e164Phone.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
