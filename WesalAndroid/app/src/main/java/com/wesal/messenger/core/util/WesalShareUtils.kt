package com.wesal.messenger.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object WesalShareUtils {

    fun buildSingleInviteLink(myWesalId: String? = null): String {
        val clean = myWesalId?.trim()?.removePrefix("@").orEmpty()
        return if (clean.isNotBlank()) {
            "${Constants.WEB_BASE_URL}/join?id=${Uri.encode(clean)}"
        } else {
            Constants.APK_DOWNLOAD_URL.trim()
        }
    }

    /**
     * Approved invite template (Sec 37):
     * «مرحبًا [اسم المستلم] 👋
     * انضم إليّ في تطبيق WESAL للمراسلة الفورية.
     * معرفي: [معرف المرسل]
     * [اسم المرسل]
     * https://tinyurl.com/wesal-Messenger»
     */
    fun buildInviteMessage(
        myWesalId: String?,
        targetContactName: String? = null,
        isEnglish: Boolean = false,
        senderName: String? = null
    ): String {
        val cleanId = myWesalId?.trim()?.removePrefix("@").orEmpty()
        val cleanSenderName = senderName?.trim().orEmpty()
        val directApkUrl = Constants.APK_DOWNLOAD_URL.trim().ifBlank { "https://tinyurl.com/wesal-Messenger" }
        val cleanTarget = targetContactName?.trim().orEmpty()

        return buildString {
            if (cleanTarget.isNotBlank()) {
                append(
                    if (isEnglish) "Hello $cleanTarget 👋\n"
                    else "مرحبًا $cleanTarget 👋\n"
                )
            } else {
                append(if (isEnglish) "Hello 👋\n" else "مرحبًا 👋\n")
            }
            append(
                if (isEnglish) "Join me on WESAL Messenger for instant messaging."
                else "انضم إليّ في تطبيق WESAL للمراسلة الفورية."
            )
            if (cleanId.isNotBlank()) {
                append(if (isEnglish) "\nMy ID: $cleanId" else "\nمعرفي: $cleanId")
            }
            if (cleanSenderName.isNotBlank()) {
                append("\n$cleanSenderName")
            }
            append("\n$directApkUrl")
            if (cleanId.isNotBlank()) {
                // Attribution link (round 3): the bare tinyurl above is identical for every
                // user, so it can never credit the inviter. The parameterized /join?id= link
                // carries the sender's publicId — opened with WESAL installed it is stored as
                // the pending invite and stamped onto the new account at registration, which
                // is what powers the inviter's «سجّلوا من خلالي» list.
                append("\n${buildSingleInviteLink(cleanId)}")
            }
        }
    }

    fun buildChannelInviteLink(inviteCode: String): String {
        val cleanCode = inviteCode.trim()
        return if (cleanCode.isNotBlank()) {
            "${Constants.WEB_BASE_URL}/channel?code=${Uri.encode(cleanCode)}"
        } else {
            Constants.APK_DOWNLOAD_URL.trim()
        }
    }

    fun buildChannelInviteMessage(
        channelTitle: String,
        inviteCode: String,
        isEnglish: Boolean = false
    ): String {
        val cleanCode = inviteCode.trim()
        val directApkUrl = Constants.APK_DOWNLOAD_URL.trim()
        return buildString {
            append(
                if (isEnglish) "Join the WESAL channel \"$channelTitle\"\nChannel code: $cleanCode"
                else "انضم إلى قناة «$channelTitle» على WESAL\nرمز القناة: $cleanCode"
            )
            if (directApkUrl.isNotBlank()) {
                append("\n$directApkUrl")
            }
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
