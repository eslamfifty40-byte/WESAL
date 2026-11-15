package com.wesal.messenger.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object WesalShareUtils {

    fun buildSingleInviteLink(myWesalId: String? = null): String {
        return Constants.APK_DOWNLOAD_URL.trim()
    }

    /**
     * Single Direct-Download Invite Policy:
     * Emits ONLY the direct APK URL from Constants.APK_DOWNLOAD_URL (when configured)
     * paired with plain text user ID (`معرّفي @<wesalId>`), with NO wesal.app, /join, or GitHub URLs.
     */
    fun buildInviteMessage(
        myWesalId: String?,
        targetContactName: String? = null,
        isEnglish: Boolean = false
    ): String {
        val cleanId = myWesalId?.trim()?.removePrefix("@").orEmpty()
        val directApkUrl = Constants.APK_DOWNLOAD_URL.trim()

        return buildString {
            if (!targetContactName.isNullOrBlank()) {
                append(
                    if (isEnglish) "Hi ${targetContactName.trim()},\n"
                    else "مرحباً ${targetContactName.trim()} 👋\n"
                )
            }
            append(
                if (isEnglish) "Join me on WESAL Messenger"
                else "انضم إليّ في تطبيق WESAL للمراسلة الفورية"
            )
            if (cleanId.isNotBlank()) {
                append(if (isEnglish) "\nMy ID: @$cleanId" else "\nمعرّفي @$cleanId")
            }
            if (directApkUrl.isNotBlank()) {
                append("\n$directApkUrl")
            }
        }
    }

    fun buildChannelInviteLink(inviteCode: String): String {
        return Constants.APK_DOWNLOAD_URL.trim()
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
