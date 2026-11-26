package com.wesal.messenger.contacts

import android.content.ContentProviderOperation
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
    val phoneHash: String?,
    val candidateHashes: List<String> = emptyList()
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

        runCatching {
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

                    val candidates = PhoneUtils.normalizePhoneCandidates(rawPhone, defaultCountryDialCode)
                    val e164 = PhoneUtils.normalizeToE164(rawPhone, defaultCountryDialCode) ?: candidates.firstOrNull()
                    val dedupKey = e164 ?: PhoneUtils.normalizeAsciiDigits(rawPhone).filter { it.isDigit() }
                    if (dedupKey.isBlank() || resultMap.containsKey(dedupKey)) continue

                    val hashes = candidates.map { PhoneUtils.sha256Hash(it) }
                    val hash = e164?.let { PhoneUtils.sha256Hash(it) } ?: hashes.firstOrNull()
                    resultMap[dedupKey] = DeviceContact(
                        contactId = contactId,
                        displayName = name.ifBlank { rawPhone },
                        rawPhone = rawPhone,
                        e164Phone = e164,
                        phoneHash = hash,
                        candidateHashes = if (hashes.isNotEmpty()) hashes else listOfNotNull(hash)
                    )
                }
            }
        }

        resultMap.values.sortedBy { it.displayName.lowercase() }
    }

    suspend fun upsertDeviceContact(
        context: Context,
        displayName: String,
        phone: String,
        existingContactId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = displayName.trim()
            val cleanPhone = phone.trim()
            require(cleanName.isNotBlank()) { "الاسم مطلوب" }

            val resolver = context.contentResolver
            if (!existingContactId.isNullOrBlank()) {
                val ops = arrayListOf(
                    ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection(
                            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                            arrayOf(existingContactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        )
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, cleanName)
                        .build()
                )
                if (cleanPhone.isNotBlank()) {
                    ops.add(
                        ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection(
                                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                                arrayOf(existingContactId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            )
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, cleanPhone)
                            .build()
                    )
                }
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            } else {
                require(cleanPhone.isNotBlank()) { "رقم الهاتف مطلوب" }
                val ops = ArrayList<ContentProviderOperation>()
                val rawContactInsertIndex = 0
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, cleanName)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, cleanPhone)
                        .withValue(
                            ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                        )
                        .build()
                )
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            }
            Unit
        }
    }

    suspend fun findContactIdByPhone(context: Context, rawPhone: String): String? = withContext(Dispatchers.IO) {
        if (rawPhone.isBlank()) return@withContext null
        val targetDigits = PhoneUtils.normalizeAsciiDigits(rawPhone).filter { it.isDigit() }
        if (targetDigits.isBlank()) return@withContext null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val phoneCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val digits = PhoneUtils.normalizeAsciiDigits(cursor.getString(phoneCol).orEmpty())
                        .filter { it.isDigit() }
                    if (digits == targetDigits || (targetDigits.length >= 7 && digits.endsWith(targetDigits.takeLast(8)))) {
                        return@withContext cursor.getString(idCol)
                    }
                }
            }
        }
        null
    }

    /**
     * Deletes the contact and every other phone-book entry that has the same number. The list shows one row
     * per number, so deleting a single contact id used to leave a duplicate (Google, SIM, WhatsApp copy)
     * that immediately took its place and looked like the delete did nothing.
     */
    suspend fun deleteDeviceContact(
        context: Context,
        contactId: String,
        rawPhone: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(contactId.isNotBlank()) { "جهة الاتصال غير موجودة في دفتر الهاتف" }
            val ids = linkedSetOf(contactId)
            if (!rawPhone.isNullOrBlank()) {
                val dial = PhoneUtils.resolveDeviceDialCode(context)
                val wanted = PhoneUtils.normalizePhoneCandidates(rawPhone, dial).toSet()
                if (wanted.isNotEmpty()) {
                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        ),
                        null, null, null
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0).orEmpty()
                            val num = c.getString(1).orEmpty()
                            if (id.isBlank() || num.isBlank() || id in ids) continue
                            if (PhoneUtils.normalizePhoneCandidates(num, dial).any { it in wanted }) ids.add(id)
                        }
                    }
                }
            }
            var deleted = 0
            ids.forEach { id ->
                deleted += context.contentResolver.delete(
                    ContactsContract.RawContacts.CONTENT_URI,
                    "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                    arrayOf(id)
                )
            }
            if (deleted <= 0) {
                error("تعذر حذف جهة الاتصال من الجهاز")
            }
            Unit
        }
    }
}
