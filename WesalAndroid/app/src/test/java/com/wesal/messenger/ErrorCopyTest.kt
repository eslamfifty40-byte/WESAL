package com.wesal.messenger

import com.google.firebase.firestore.FirebaseFirestoreException
import com.wesal.messenger.core.util.ErrorCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Round 13 (Addendum L/L5): raw SDK exception text must NEVER reach a
 * screen; our own authored Arabic guard messages must survive.
 */
class ErrorCopyTest {

    @Test
    fun quotaException_mapsToQuotaCopy_neverRawText() {
        val raw = FirebaseFirestoreException(
            "RESOURCE_EXHAUSTED: Quota limit exceeded. project_number: 255196025471",
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED
        )
        assertEquals(ErrorCopy.QUOTA_AR, ErrorCopy.friendly(raw, "fallback"))
    }

    @Test
    fun unavailableAndDeadline_mapToConnectionCopy() {
        val un = FirebaseFirestoreException(
            "UNAVAILABLE: io.grpc.StatusRuntimeException",
            FirebaseFirestoreException.Code.UNAVAILABLE
        )
        assertEquals(ErrorCopy.CONNECTION_AR, ErrorCopy.friendly(un, "fallback"))
        val dl = FirebaseFirestoreException(
            "DEADLINE_EXCEEDED",
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
        )
        assertEquals(ErrorCopy.CONNECTION_AR, ErrorCopy.friendly(dl, "fallback"))
    }

    @Test
    fun permissionDenied_mapsToNotAllowed() {
        val pd = FirebaseFirestoreException(
            "PERMISSION_DENIED: Missing or insufficient permissions.",
            FirebaseFirestoreException.Code.PERMISSION_DENIED
        )
        assertEquals(ErrorCopy.NOT_ALLOWED_AR, ErrorCopy.friendly(pd, "fallback"))
    }

    @Test
    fun ioException_mapsToConnectionCopy() {
        assertEquals(
            ErrorCopy.CONNECTION_AR,
            ErrorCopy.friendly(IOException("Unable to resolve host"), "fallback")
        )
    }

    @Test
    fun authoredArabicGuardMessage_passesThrough() {
        val ours = IllegalStateException("الحساب غير موجود")
        assertEquals("الحساب غير موجود", ErrorCopy.friendly(ours, "fallback"))
    }

    @Test
    fun englishSdkMessage_collapsesToFallback() {
        val sdk = IllegalStateException("The email address is badly formatted.")
        assertEquals("fallback", ErrorCopy.friendly(sdk, "fallback"))
        assertEquals("fallback", ErrorCopy.friendly(RuntimeException(), "fallback"))
        assertEquals("fallback", ErrorCopy.friendly(null, "fallback"))
    }

    @Test
    fun arabicWithTechnicalMarkers_isNotAuthored() {
        assertFalse(ErrorCopy.isAuthored("خطأ RESOURCE_EXHAUSTED في Firestore"))
        assertTrue(ErrorCopy.isAuthored("تعذر حفظ البيانات — حاول مرة أخرى"))
        assertFalse(ErrorCopy.isAuthored("plain english text"))
    }
}
