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

    /**
     * Digits-only form of an E.164 phone, exactly as wa.me expects it (no '+', no spaces).
     * Pure helper (no Android APIs) so the bulk-invite logic can be unit-tested.
     */
    fun inviteTargetDigits(targetPhoneE164: String?): String =
        targetPhoneE164?.filter { it.isDigit() }.orEmpty()

    /**
     * True when a saved number normalized to a plausible international number, i.e. a
     * wa.me chat can actually be opened for it. Contacts failing this are skipped (and
     * counted) by the bulk-invite flow instead of opening a dead chat.
     */
    fun isInvitablePhone(targetPhoneE164: String?): Boolean = inviteTargetDigits(targetPhoneE164).length >= 9

    /** The two WhatsApp apps, in display order for the chooser. */
    val WHATSAPP_PACKAGES: List<String> = listOf("com.whatsapp", "com.whatsapp.w4b")

    /** True when [pkg] is installed AND can handle a wa.me ACTION_VIEW link. */
    private fun whatsAppPackageCanOpen(context: Context, pkg: String, url: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(pkg, 0)
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
                .resolveActivity(context.packageManager) != null
        }.getOrDefault(false)

    /**
     * Round 11 (item 4 — owner's explicit ruling): opens a wa.me [url]
     * letting the user CHOOSE between WhatsApp and WhatsApp Business when
     * both are installed. His verification number lives in the Business
     * app, so silently forcing one package (the round-10 behaviour) could
     * send «ابدأ» from the WRONG number and the OTP window never opened.
     * Exactly one installed → opens it directly (no pointless chooser);
     * none → the plain generic intent (browser fallback). Returns true
     * when something was launched.
     */
    fun openWhatsAppLink(context: Context, url: String): Boolean {
        val installed = WHATSAPP_PACKAGES.filter { whatsAppPackageCanOpen(context, it, url) }
        return when (installed.size) {
            0 -> runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                true
            }.getOrDefault(false)

            1 -> runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(installed[0])
                )
                true
            }.getOrDefault(false)

            else -> runCatching {
                val primary = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(installed[0])
                val extras = installed.drop(1).map { pkg ->
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
                }
                val chooser = Intent.createChooser(primary, null).apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
                }
                context.startActivity(chooser)
                true
            }.getOrDefault(false)
        }
    }

    fun shareViaWhatsApp(context: Context, message: String, targetPhoneE164: String? = null) {
        // Targeted invite (owner-reported bug fix): a wa.me deep link is the ONLY way to
        // open a SPECIFIC person's chat with the message prefilled. The old order tried a
        // generic ACTION_SEND first — that intent carries no phone number, so WhatsApp /
        // WhatsApp Business simply opened its own share UI and the person's chat never
        // opened; the wa.me fallback was unreachable whenever WhatsApp was installed.
        // Round 11 (item 4): the open now goes through the two-app chooser —
        // the user picks WhatsApp or WhatsApp Business when both exist.
        val digitsPhone = inviteTargetDigits(targetPhoneE164)
        if (digitsPhone.length >= 9) {
            val waUrl = "https://wa.me/$digitsPhone?text=${Uri.encode(message)}"
            if (!openWhatsAppLink(context, waUrl)) {
                // WhatsApp not installed and nothing handled the link: generic share sheet.
                shareGeneral(context, message, "WESAL Invite")
            }
            return
        }

        // Non-targeted share: same chooser rule over ACTION_SEND — both
        // apps offered when both are installed, direct open when only one
        // is, and the old wa.me/browser fallback when neither is.
        fun sendIntent(pkg: String) = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(pkg)
            putExtra(Intent.EXTRA_TEXT, message)
        }
        val installedSend = WHATSAPP_PACKAGES.filter { pkg ->
            runCatching {
                context.packageManager.getPackageInfo(pkg, 0)
                sendIntent(pkg).resolveActivity(context.packageManager) != null
            }.getOrDefault(false)
        }
        val launchedDirect = when (installedSend.size) {
            0 -> false
            1 -> runCatching {
                context.startActivity(sendIntent(installedSend[0]))
                true
            }.getOrDefault(false)

            else -> runCatching {
                val primary = sendIntent(installedSend[0])
                val extras = installedSend.drop(1).map { sendIntent(it) }
                val chooser = Intent.createChooser(primary, null).apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
                }
                context.startActivity(chooser)
                true
            }.getOrDefault(false)
        }

        if (!launchedDirect) {
            val waUrl = "https://wa.me/?text=${Uri.encode(message)}"
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
