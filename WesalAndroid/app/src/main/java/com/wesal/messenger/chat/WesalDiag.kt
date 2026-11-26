package com.wesal.messenger.chat

import kotlinx.coroutines.flow.MutableStateFlow

/** Visible diagnostics: the app blocks screenshots, so real errors are shown as an in-chat banner. */
object WesalDiag {
    val error = MutableStateFlow<String?>(null)
    val status = MutableStateFlow("")
    fun report(msg: String) { error.value = msg }
}
