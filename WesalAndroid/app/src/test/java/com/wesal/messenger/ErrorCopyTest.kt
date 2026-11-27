package com.wesal.messenger

import com.wesal.messenger.core.util.ErrorCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Round 13 (Addendum L/L5): raw SDK exception text must NEVER reach a
 * screen; our own authored Arabic guard messages must survive.
 *
 * The Firestore code mapping is exercised through
 * [ErrorCopy.forCodeName] because local JVM unit tests cannot
 * initialize Firebase exception classes (ExceptionInInitializerError);
 * `ErrorCopy.friendly` delegates to it via `error.code.name`.
 */
class ErrorCopyTest {

    @Test
    fun quotaCode_mapsToQuotaCopy_neverRawText() {
        assertEquals(ErrorCopy.QUOTA_AR, ErrorCopy.forCodeName("RESOURCE_EXHAUSTED", "fallback"))
    }

    @Test
    fun unavailableAndDeadline_mapToConnectionCopy() {
        assertEquals(ErrorCopy.CONNECTION_AR, ErrorCopy.forCodeName("UNAVAILABLE", "fallback"))
        assertEquals(ErrorCopy.CONNECTION_AR, ErrorCopy.forCodeName("DEADLINE_EXCEEDED", "fallback"))
    }

    @Test
    fun permissionDenied_mapsToNotAllowed() {
        assertEquals(ErrorCopy.NOT_ALLOWED_AR, ErrorCopy.forCodeName("PERMISSION_DENIED", "fallback"))
    }

    @Test
    fun unknownCode_collapsesToFallback() {
        assertEquals("fallback", ErrorCopy.forCodeName("INTERNAL", "fallback"))
        assertEquals("fallback", ErrorCopy.forCodeName("NOT_FOUND", "fallback"))
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
    fun rawQuotaText_isNotAuthored() {
        // The exact string class from the owner's screenshots: even if
        // such text arrived wrapped in a plain exception, it must
        // never be treated as authored copy.
        assertFalse(
            ErrorCopy.isAuthored(
                "RESOURCE_EXHAUSTED: Quota limit exceeded. project_number: 255196025471"
            )
        )
        assertFalse(ErrorCopy.isAuthored("خطأ RESOURCE_EXHAUSTED في Firestore"))
        assertTrue(ErrorCopy.isAuthored("تعذر حفظ البيانات — حاول مرة أخرى"))
        assertFalse(ErrorCopy.isAuthored("plain english text"))
    }
}
