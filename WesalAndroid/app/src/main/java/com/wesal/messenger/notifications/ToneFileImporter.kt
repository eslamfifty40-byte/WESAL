package com.wesal.messenger.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * «إضافة من ملفاتي» — imports an audio file picked through the system file
 * picker (SAF `OpenDocument`, audio/*) as a message/call tone choice (§2.5).
 *
 * Two things happen for every pick:
 *  1. a persistable read grant is taken on the picked content URI, and that URI
 *     is the value persisted as the tone choice. Notification-channel sounds are
 *     rendered by the system, which can open content URIs under the app's
 *     persisted grant — an app-private file path would not be readable there.
 *  2. the audio bytes are also copied into app-private storage
 *     (`filesDir/tones/message_tone.<ext>` / `call_tone.<ext>`, overwritten on
 *     each new pick) so the imported tone's bytes are preserved inside the app
 *     even if the original file is moved or deleted later.
 *
 * Both steps are best-effort: a failed copy never blocks the choice itself.
 * Returns the value to persist (the picked URI as a string).
 */
object ToneFileImporter {

    fun importTone(context: Context, source: Uri, isCall: Boolean): String {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                source,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        runCatching { copyIntoTonesDir(context, source, isCall) }
        return source.toString()
    }

    private fun copyIntoTonesDir(context: Context, source: Uri, isCall: Boolean) {
        val resolver = context.contentResolver
        val dir = File(context.filesDir, TONES_DIR).apply { mkdirs() }
        val target = File(dir, (if (isCall) CALL_TONE_FILE else MESSAGE_TONE_FILE) + "." + extensionFor(context, source))
        val input = resolver.openInputStream(source) ?: return
        input.use { stream ->
            target.outputStream().use { out -> stream.copyTo(out) }
        }
    }

    /** Best-effort file extension: display name first, then the resolver MIME type. */
    private fun extensionFor(context: Context, source: Uri): String {
        val fromName = runCatching {
            context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.filter { it.isLetterOrDigit() }
            ?.take(5)
            .orEmpty()
        if (fromName.isNotBlank()) return fromName
        return when (context.contentResolver.getType(source)?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/aac" -> "aac"
            "audio/flac" -> "flac"
            "audio/3gpp" -> "3gp"
            "audio/amr" -> "amr"
            else -> "audio"
        }
    }

    private const val TONES_DIR = "tones"
    private const val MESSAGE_TONE_FILE = "message_tone"
    private const val CALL_TONE_FILE = "call_tone"
}
