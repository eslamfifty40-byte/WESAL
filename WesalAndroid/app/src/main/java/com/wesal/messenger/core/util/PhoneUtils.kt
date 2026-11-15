package com.wesal.messenger.core.util

import android.content.Context
import android.telephony.TelephonyManager
import com.wesal.messenger.authentication.CountryCatalog
import java.security.MessageDigest
import java.util.Locale

object PhoneUtils {
    /**
     * Resolves the user's country dial code from SIM/Network ISO or Locale, falling back to +20.
     */
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
     * Normalizes local/international phone numbers to E.164 format.
     */
    fun normalizeToE164(rawInput: String, defaultDialCode: String = Constants.DEFAULT_COUNTRY_DIAL): String? {
        val cleaned = rawInput.trim().replace(Regex("[\\s\\-().]"), "")
        if (cleaned.isEmpty()) return null

        val e164 = when {
            cleaned.startsWith("+") -> "+" + cleaned.drop(1).filter { it.isDigit() }
            cleaned.startsWith("00") -> "+" + cleaned.drop(2).filter { it.isDigit() }
            cleaned.startsWith("0") -> defaultDialCode + cleaned.drop(1).filter { it.isDigit() }
            cleaned.startsWith("20") && cleaned.length >= 12 -> "+" + cleaned.filter { it.isDigit() }
            else -> defaultDialCode + cleaned.filter { it.isDigit() }
        }

        val digitsCount = e164.drop(1).length
        return if (e164.startsWith("+") && digitsCount in 9..15) e164 else null
    }

    fun sha256Hash(e164Phone: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(e164Phone.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
