package com.wesal.messenger.contacts

import android.content.Context
import android.provider.ContactsContract
import com.wesal.messenger.core.util.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceContact(
    val contactId: String,
    val displayName: String,
    val rawPhone: String,
    val e164Phone: String?,
    val phoneHash: String?
)

object PhoneContactsHelper {

    suspend fun loadDeviceContacts(
        context: Context,
        defaultCountryDialCode: String = PhoneUtils.resolveDeviceDialCode(context)
    ): List<DeviceContact> = withContext(Dispatchers.IO) {
        val resultMap = linkedMapOf<String, DeviceContact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val contactId = cursor.getString(idCol).orEmpty()
                val name = cursor.getString(nameCol).orEmpty().trim()
                val rawPhone = cursor.getString(phoneCol).orEmpty().trim()
                if (rawPhone.isBlank()) continue

                val e164 = PhoneUtils.normalizeToE164(rawPhone, defaultCountryDialCode)
                val dedupKey = e164 ?: rawPhone.filter { it.isDigit() }
                if (dedupKey.isBlank() || resultMap.containsKey(dedupKey)) continue

                val hash = e164?.let { PhoneUtils.sha256Hash(it) }
                resultMap[dedupKey] = DeviceContact(
                    contactId = contactId,
                    displayName = name.ifBlank { rawPhone },
                    rawPhone = rawPhone,
                    e164Phone = e164,
                    phoneHash = hash
                )
            }
        }

        resultMap.values.sortedBy { it.displayName.lowercase() }
    }
}
