package com.wesal.messenger.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object WesalShareUtils {

    private const val INVITE_WEB_URL = "https://wesal.app/join"

    fun buildInviteMessage(myWesalId: String?, targetContactName: String? = null): String {
        val greeting = if (!targetContactName.isNullOrBlank()) {
            "مرحباً ${targetContactName.trim()} 👋\n"
        } else {
            ""
        }
        val cleanId = myWesalId?.trim()?.removePrefix("@").orEmpty()
        val idLine = if (cleanId.isNotBlank()) {
            "\nمعرف وصال الخاص بي: @$cleanId\nرابط الدعوة المباشر: $INVITE_WEB_URL?id=$cleanId"
        } else {
            "\nرابط التطبيق: $INVITE_WEB_URL"
        }
        return "${greeting}انضم إليّ في تطبيق «وصال» (WESAL) للمراسلة الفورية والمكالمات المشفرة.$idLine"
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
                shareGeneral(context, message, "دعوة إلى وصال")
            }
        }
    }

    fun shareGeneral(context: Context, message: String, chooserTitle: String = "مشاركة رابط دعوة وصال") {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        runCatching {
            context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
        }.onFailure {
            Toast.makeText(context, "تعذر فتح قائمة المشاركة", Toast.LENGTH_SHORT).show()
        }
    }
}
