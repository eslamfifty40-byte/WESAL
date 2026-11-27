package com.wesal.messenger

import com.wesal.messenger.core.util.AssistantLogic
import com.wesal.messenger.core.util.AssistantMediaKind
import com.wesal.messenger.core.util.AssistantMessage
import com.wesal.messenger.core.util.AssistantPayloadMedia
import com.wesal.messenger.core.util.AssistantRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-8 tests for the pure «مساعد وِصال» shaping rules: the greeting
 * window, the suggestion chips, the backend payload window (last 10,
 * attachment only on the newest message, placeholders for older media)
 * and the local-history cap. The backend owns answering; these cover
 * only what the client sends and shows.
 */
class AssistantLogicTest {

    private fun msg(
        id: String,
        role: AssistantRole,
        text: String,
        mediaKind: AssistantMediaKind = AssistantMediaKind.NONE,
        isErrorNotice: Boolean = false
    ) = AssistantMessage(
        id = id,
        role = role,
        text = text,
        mediaKind = mediaKind,
        mediaPath = if (mediaKind == AssistantMediaKind.NONE) "" else "/tmp/$id.media",
        mediaMimeType = when (mediaKind) {
            AssistantMediaKind.IMAGE -> "image/jpeg"
            AssistantMediaKind.VOICE -> "audio/mp4"
            AssistantMediaKind.NONE -> ""
        },
        isErrorNotice = isErrorNotice
    )

    @Test
    fun greetingMorningWindow() {
        assertEquals("صباح الخير يا اسلام", AssistantLogic.greetingFor(5, "اسلام"))
        assertEquals("صباح الخير يا اسلام", AssistantLogic.greetingFor(9, "اسلام"))
        assertEquals("صباح الخير يا اسلام", AssistantLogic.greetingFor(11, "اسلام"))
    }

    @Test
    fun greetingEveningOutsideMorningWindow() {
        assertEquals("مساء الخير يا اسلام", AssistantLogic.greetingFor(12, "اسلام"))
        assertEquals("مساء الخير يا اسلام", AssistantLogic.greetingFor(23, "اسلام"))
        assertEquals("مساء الخير يا اسلام", AssistantLogic.greetingFor(4, "اسلام"))
        assertEquals("مساء الخير يا اسلام", AssistantLogic.greetingFor(0, "اسلام"))
    }

    @Test
    fun greetingWithoutNameOmitsYa() {
        assertEquals("صباح الخير", AssistantLogic.greetingFor(8, ""))
        assertEquals("مساء الخير", AssistantLogic.greetingFor(20, "  "))
    }

    @Test
    fun firstNameTakesFirstToken() {
        assertEquals("اسلام", AssistantLogic.firstNameOf("اسلام احمد شحاتة"))
        assertEquals("Ahmed", AssistantLogic.firstNameOf("  Ahmed Samy "))
        assertEquals("", AssistantLogic.firstNameOf(""))
        assertEquals("", AssistantLogic.firstNameOf(null))
    }

    @Test
    fun suggestionsAreEightEgyptianChips() {
        assertEquals(8, AssistantLogic.SUGGESTIONS.size)
        assertTrue(AssistantLogic.SUGGESTIONS.contains("ساعدني أكتب رسالة حلوة"))
        assertTrue(AssistantLogic.SUGGESTIONS.contains("اشرح لي موضوع صعب ببساطة"))
        assertTrue(AssistantLogic.SUGGESTIONS.contains("اديني أفكار لهدية لحد بحبه"))
        assertTrue(AssistantLogic.SUGGESTIONS.contains("ترجم لي كلام للإنجليزي"))
    }

    @Test
    fun payloadKeepsOnlyLastTenMessages() {
        val history = (1..12).map { i ->
            msg("m$i", if (i % 2 == 1) AssistantRole.USER else AssistantRole.ASSISTANT, "t$i")
        }
        val payload = AssistantLogic.buildPayload(history)
        assertEquals(10, payload.size)
        assertEquals("t3", payload.first().text)
        assertEquals("t12", payload.last().text)
    }

    @Test
    fun payloadMapsRoles() {
        val payload = AssistantLogic.buildPayload(
            listOf(
                msg("1", AssistantRole.USER, "سؤال"),
                msg("2", AssistantRole.ASSISTANT, "جواب")
            )
        )
        assertEquals("user", payload[0].role)
        assertEquals("assistant", payload[1].role)
    }

    @Test
    fun payloadDropsErrorNotices() {
        val payload = AssistantLogic.buildPayload(
            listOf(
                msg("1", AssistantRole.USER, "سؤال"),
                msg("2", AssistantRole.ASSISTANT, AssistantLogic.ERROR_GENERIC, isErrorNotice = true),
                msg("3", AssistantRole.USER, "سؤال تاني")
            )
        )
        assertEquals(2, payload.size)
        assertEquals("سؤال تاني", payload.last().text)
    }

    @Test
    fun attachmentGoesOnlyOnNewestMediaMessage() {
        val media = AssistantPayloadMedia("image/jpeg", "QUJD")
        val payload = AssistantLogic.buildPayload(
            listOf(
                msg("1", AssistantRole.USER, "", AssistantMediaKind.IMAGE),
                msg("2", AssistantRole.ASSISTANT, "دي صورة قطة"),
                msg("3", AssistantRole.USER, "", AssistantMediaKind.IMAGE)
            ),
            attachmentForLast = media
        )
        assertNull(payload[0].media)
        assertNull(payload[1].media)
        assertEquals(media, payload[2].media)
    }

    @Test
    fun attachmentNotAttachedToTextMessage() {
        val media = AssistantPayloadMedia("image/jpeg", "QUJD")
        val payload = AssistantLogic.buildPayload(
            listOf(msg("1", AssistantRole.USER, "أهلا")),
            attachmentForLast = media
        )
        assertNull(payload[0].media)
    }

    @Test
    fun olderMediaMessagesBecomePlaceholders() {
        val payload = AssistantLogic.buildPayload(
            listOf(
                msg("1", AssistantRole.USER, "", AssistantMediaKind.IMAGE),
                msg("2", AssistantRole.ASSISTANT, "تمام"),
                msg("3", AssistantRole.USER, "", AssistantMediaKind.VOICE),
                msg("4", AssistantRole.ASSISTANT, "سمعتك"),
                msg("5", AssistantRole.USER, "شكراً")
            )
        )
        assertEquals(AssistantLogic.IMAGE_PLACEHOLDER, payload[0].text)
        assertEquals(AssistantLogic.VOICE_PLACEHOLDER, payload[2].text)
        assertEquals("شكراً", payload[4].text)
    }

    @Test
    fun newestMediaMessageUsesDefaultQuestion() {
        val imagePayload = AssistantLogic.buildPayload(
            listOf(msg("1", AssistantRole.USER, "", AssistantMediaKind.IMAGE))
        )
        assertEquals(AssistantLogic.IMAGE_DEFAULT_QUESTION, imagePayload[0].text)

        val voicePayload = AssistantLogic.buildPayload(
            listOf(msg("1", AssistantRole.USER, "", AssistantMediaKind.VOICE))
        )
        assertEquals(AssistantLogic.VOICE_DEFAULT_TEXT, voicePayload[0].text)
    }

    @Test
    fun captionedMediaKeepsCaptionEverywhere() {
        val payload = AssistantLogic.buildPayload(
            listOf(
                msg("1", AssistantRole.USER, "ايه ده؟", AssistantMediaKind.IMAGE),
                msg("2", AssistantRole.ASSISTANT, "ده هرم"),
                msg("3", AssistantRole.USER, "ودي؟", AssistantMediaKind.IMAGE)
            )
        )
        assertEquals("ايه ده؟", payload[0].text)
        assertEquals("ودي؟", payload[2].text)
    }

    @Test
    fun historyCapKeepsNewestHundred() {
        val history = (1..105).map { i -> msg("m$i", AssistantRole.USER, "t$i") }
        val capped = AssistantLogic.capHistory(history)
        assertEquals(100, capped.size)
        assertEquals("m6", capped.first().id)
        assertEquals("m105", capped.last().id)
    }
}
