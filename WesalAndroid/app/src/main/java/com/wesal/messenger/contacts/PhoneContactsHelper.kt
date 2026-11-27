package com.wesal.messenger.contacts

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
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

        // Round 12 (addendum 3d): the device-owner ("Me") profile lives in
        // the separate Profile database and is NOT returned by the Phone
        // query above on many devices — a number saved only there (very
        // common for the owner's own second line / a family member entered
        // as the profile contact) was invisible to discovery. Merge its
        // phone rows best-effort; dedup by number (same key rule) makes
        // overlap with the main query harmless.
        runCatching {
            val profileDataUri = android.net.Uri.withAppendedPath(
                ContactsContract.Profile.CONTENT_URI,
                "data"
            )
            context.contentResolver.query(
                profileDataUri,
                arrayOf(
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.Data.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.Data.MIMETYPE
                ),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                val nameCol = cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
                val phoneCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (idCol >= 0 && phoneCol >= 0) {
                    while (cursor.moveToNext()) {
                        val rawPhone = cursor.getString(phoneCol).orEmpty().trim()
                        if (rawPhone.isBlank()) continue
                        val candidates = PhoneUtils.normalizePhoneCandidates(rawPhone, defaultCountryDialCode)
                        val e164 = PhoneUtils.normalizeToE164(rawPhone, defaultCountryDialCode)
                            ?: candidates.firstOrNull()
                        val dedupKey = e164
                            ?: PhoneUtils.normalizeAsciiDigits(rawPhone).filter { it.isDigit() }
                        if (dedupKey.isBlank() || resultMap.containsKey(dedupKey)) continue
                        val hashes = candidates.map { PhoneUtils.sha256Hash(it) }
                        val hash = e164?.let { PhoneUtils.sha256Hash(it) } ?: hashes.firstOrNull()
                        resultMap[dedupKey] = DeviceContact(
                            contactId = if (idCol >= 0) cursor.getString(idCol).orEmpty() else "",
                            displayName = (if (nameCol >= 0) {
                                cursor.getString(nameCol).orEmpty().trim()
                            } else "").ifBlank { rawPhone },
                            rawPhone = rawPhone,
                            e164Phone = e164,
                            phoneHash = hash,
                            candidateHashes = if (hashes.isNotEmpty()) hashes else listOfNotNull(hash)
                        )
                    }
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
            val cleanPhone = PhoneUtils.normalizeAsciiDigits(phone).trim()
            require(cleanName.isNotBlank()) { "الاسم مطلوب" }
            // Round 6 (item 9): check the WRITE permission HERE, at insert
            // time. The add dialog's only gate was a permission-state
            // snapshot taken when the top-bar button was tapped; when the
            // grant was not actually in effect the provider threw
            // SecurityException into a fire-and-forget Result whose error
            // landed in a transient list header while the dialog had already
            // closed — a silent no-save that looked exactly like success.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("لازم تسمح بصلاحية تعديل جهات الاتصال عشان نقدر نحفظ الرقم على الجهاز")
            }

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
                fun insertIntoAccount(accountType: String?, accountName: String?): Boolean {
                    val ops = ArrayList<ContentProviderOperation>()
                    val rawContactInsertIndex = 0
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
                            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
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
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, cleanName)
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
                    val results = runCatching { resolver.applyBatch(ContactsContract.AUTHORITY, ops) }.getOrNull()
                        ?: return false
                    val rawContactId = results.getOrNull(0)?.uri?.lastPathSegment?.toLongOrNull()
                        ?: return false
                    return resolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
                        arrayOf(rawContactId.toString()),
                        null
                    )?.use { cursor ->
                        var found = false
                        val wantedDigits = PhoneUtils.normalizeAsciiDigits(cleanPhone).filter { it.isDigit() }
                        while (cursor.moveToNext()) {
                            val storedDigits = PhoneUtils.normalizeAsciiDigits(cursor.getString(0).orEmpty())
                                .filter { it.isDigit() }
                            if (storedDigits == wantedDigits || storedDigits.endsWith(wantedDigits.takeLast(9))) {
                                found = true
                                break
                            }
                        }
                        found
                    } ?: false
                }

                var landed = insertIntoAccount(null, null)
                if (!landed) {
                    val writableAccount = runCatching {
                        resolver.query(
                            ContactsContract.RawContacts.CONTENT_URI,
                            arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
                            "${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NOT NULL AND ${ContactsContract.RawContacts.ACCOUNT_NAME} IS NOT NULL",
                            null,
                            null
                        )?.use { c ->
                            if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
                        }
                    }.getOrNull()
                    if (writableAccount != null) {
                        landed = insertIntoAccount(writableAccount.first, writableAccount.second)
                    }
                }
                if (!landed) {
                    error("تعذر حفظ جهة الاتصال على الجهاز")
                }
            }
            Unit
        }
    }

    /**
     * Outcome of a phone-book lookup by number. The outcomes are deliberately distinct:
     * the old implementation returned a bare null for ALL of them, so a permission denial
     * or a blank number was reported to the user as "this number is not saved in the
     * phone book" even when the contact was right there (round 3).
     */
    sealed interface PhoneContactLookup {
        data class Found(val contactId: String) : PhoneContactLookup
        data object NotFound : PhoneContactLookup
        data object PermissionDenied : PhoneContactLookup
        data object NoPhone : PhoneContactLookup
    }

    /**
     * Finds a phone-book contact by number using the SAME normalization as the rest of
     * the app: both sides are expanded with [PhoneUtils.normalizePhoneCandidates] and
     * intersected — the rule loadDeviceContacts / hash matching / the delete duplicate
     * sweep already use. The old version compared raw digit strings (exact match or a
     * last-8-digits suffix), which missed saved entries whose stored form differs from
     * the WESAL doc's number format. The digit rule survives only as a fallback.
     */
    suspend fun findContactIdByPhone(context: Context, rawPhone: String): PhoneContactLookup =
        withContext(Dispatchers.IO) {
            if (rawPhone.isBlank()) return@withContext PhoneContactLookup.NoPhone
            val dial = PhoneUtils.resolveDeviceDialCode(context)
            val wanted = PhoneUtils.normalizePhoneCandidates(rawPhone, dial).toSet()
            val targetDigits = PhoneUtils.normalizeAsciiDigits(rawPhone).filter { it.isDigit() }
            if (wanted.isEmpty() && targetDigits.isBlank()) {
                return@withContext PhoneContactLookup.NoPhone
            }
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            try {
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
                        val stored = cursor.getString(phoneCol).orEmpty()
                        if (stored.isBlank()) continue
                        val candidateHit = wanted.isNotEmpty() &&
                            PhoneUtils.normalizePhoneCandidates(stored, dial).any { it in wanted }
                        val digits = PhoneUtils.normalizeAsciiDigits(stored).filter { it.isDigit() }
                        val digitHit = digits == targetDigits ||
                            (targetDigits.length >= 7 && digits.endsWith(targetDigits.takeLast(8)))
                        if (candidateHit || digitHit) {
                            return@withContext PhoneContactLookup.Found(cursor.getString(idCol).orEmpty())
                        }
                    }
                }
                PhoneContactLookup.NotFound
            } catch (e: SecurityException) {
                // Never disguise a permission denial as "not saved in the phone book".
                PhoneContactLookup.PermissionDenied
            }
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
