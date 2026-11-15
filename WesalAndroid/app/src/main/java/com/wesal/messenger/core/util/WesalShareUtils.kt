package com.wesal.messenger.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder

object WesalShareUtils {

    private const val INVITE_WEB_BASE_URL = "https://wesal.app/join"
    private const val CHANNEL_WEB_BASE_URL = "https://wesal.app/channel"

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    fun buildSingleInviteLink(myWesalId: String?): String {
        val cleanId = myWesalId?.trim()?.removePrefix("@").orEmpty()
        return if (cleanId.isNotBlank()) {
            "$INVITE_WEB_BASE_URL?id=${urlEncode(cleanId)}"
        } else {
            INVITE_WEB_BASE_URL
        }
    }

    /**
     * Mandatory Single-Link Invite Policy:
     * Produces ONE single URL (`https://wesal.app/join?id=...`).
     * Never emits a second link, intermediate web page link, or GitHub URL in the share text.
     * On devices with WESAL installed, Android App Links open WESAL directly on the inviter's card.
     * On devices without WESAL installed, `/join` responds with an HTTP 302 direct download of WESAL.apk.
     */
    fun buildInviteMessage(
        myWesalId: String?,
        targetContactName: String? = null,
        isEnglish: Boolean = false
    ): String {
        val singleInviteLink = buildSingleInviteLink(myWesalId)
        val greeting = if (!targetContactName.isNullOrBlank()) {
            if (isEnglish) "Hi ${targetContactName.trim()},\n" else "مرحباً ${targetContactName.trim()} 👋\n"
        } else {
            ""
        }
        return if (isEnglish) {
            "${greeting}Join me on WESAL Messenger via my invite link:\n$singleInviteLink"
        } else {
            "${greeting}انضم إليّ في تطبيق WESAL للمراسلة الفورية عبر رابط الدعوة المباشر:\n$singleInviteLink"
        }
    }

    fun buildChannelInviteLink(inviteCode: String): String {
        val cleanCode = inviteCode.trim()
        return "$CHANNEL_WEB_BASE_URL?code=${Uri.encode(cleanCode)}"
    }

    fun buildChannelInviteMessage(
        channelTitle: String,
        inviteCode: String,
        isEnglish: Boolean = false
    ): String {
        val link = buildChannelInviteLink(inviteCode)
        return if (isEnglish) {
            "Join the WESAL channel \"$channelTitle\":\n$link"
        } else {
            "انضم إلى قناة «$channelTitle» على WESAL:\n$link"
        }
    }

    fun shareViaWhatsApp(context: Context, message: String, targetPhoneE164: String? = null) {
        val whatsappIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage("com.whatsapp")
            putExtra(Intent.EXTRA_TEXT, message)
        }
        val businessWhatsappIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage("com.whatsapp.w4b")
            putExtra(Intent.EXTRA_TEXT, message)
        }

        val launchedDirect = runCatching {
            context.startActivity(whatsappIntent)
            true
        }.getOrElse {
            runCatching {
                context.startActivity(businessWhatsappIntent)
                true
            }.getOrDefault(false)
        }

        if (!launchedDirect) {
            val digitsPhone = targetPhoneE164?.filter { it.isDigit() }.orEmpty()
            val waUrl = if (digitsPhone.length >= 9) {
                "https://wa.me/$digitsPhone?text=${Uri.encode(message)}"
            } else {
                "https://wa.me/?text=${Uri.encode(message)}"
            }
            val browserFallback = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))
            runCatching {
                context.startActivity(browserFallback)
            }.onFailure {
                shareGeneral(context, message, "WESAL Invite")
            }
        }
    }

    fun shareGeneral(context: Context, message: String, chooserTitle: String = "WESAL") {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        runCatching {
            context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
        }.onFailure {
            Toast.makeText(context, "Unable to open share sheet", Toast.LENGTH_SHORT).show()
        }
    }
}
