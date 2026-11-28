package com.wesal.messenger.core.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateTimeUtils {
    fun formatMessageTime(timestampMs: Long, locale: Locale = Locale.getDefault()): String {
        if (timestampMs <= 0L) return ""
        return SimpleDateFormat("hh:mm a", locale).format(Date(timestampMs))
    }

    fun formatChatListTime(timestampMs: Long, isArabic: Boolean = true): String {
        if (timestampMs <= 0L) return ""
        val now = Calendar.getInstance()
        val msgCal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val locale = if (isArabic) Locale("ar") else Locale.ENGLISH

        return when {
            now.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR) -> {
                SimpleDateFormat("hh:mm a", locale).format(Date(timestampMs))
            }
            now.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) - msgCal.get(Calendar.DAY_OF_YEAR) == 1 -> {
                if (isArabic) "أمس" else "Yesterday"
            }
            else -> SimpleDateFormat("dd/MM/yyyy", locale).format(Date(timestampMs))
        }
    }

    fun formatDurationSeconds(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format(Locale.ENGLISH, "%02d:%02d", mins, secs)
    }

    fun formatDurationMillis(millis: Long): String = formatDurationSeconds((millis / 1000L).coerceAtLeast(0L))

    /**
     * R17 (G-01, owner: «لما يجي يرد يبان عدد الدقايق»): the in-call status
     * line keeps the state word next to the running time once the other
     * side has answered — «متصل • 01:23» — instead of replacing the state
     * with a bare timer. Pure, so the call screen and the unit test share
     * exactly this composition.
     */
    fun formatCallStatusWithDuration(stateText: String, seconds: Long): String =
        "$stateText • ${formatDurationSeconds(seconds.coerceAtLeast(0L))}"
}
