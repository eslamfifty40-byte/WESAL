package com.wesal.messenger.chat

import kotlinx.coroutines.flow.MutableStateFlow

/** Visible diagnostics: the app blocks screenshots, so real errors are shown as an in-chat banner. */
object WesalDiag {
    val error = MutableStateFlow<String?>(null)
    val status = MutableStateFlow("")
    val probe = MutableStateFlow("")
    val clearResult = MutableStateFlow("clr=?")
    val ring = MutableStateFlow("")
    fun report(msg: String) { error.value = "[v${com.wesal.messenger.BuildConfig.BUILD_STAMP}] $msg" }
    /** Timestamped ring/call trace, last few lines, shown in-app (screenshots are blocked). */
    fun ringLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val lines = ring.value.split("\n").filter { it.isNotBlank() }.takeLast(5)
        ring.value = (lines + "$ts $msg").joinToString("\n")
    }
}
