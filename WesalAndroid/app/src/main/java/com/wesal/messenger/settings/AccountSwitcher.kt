package com.wesal.messenger.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

object AccountSwitcherState {
    var show by mutableStateOf(false)
}

private fun loadAccounts(ctx: Context): List<List<String>> {
    val raw = ctx.getSharedPreferences("wesal_accounts", Context.MODE_PRIVATE).getString("list", "") ?: ""
    return raw.split("\n").filter { it.isNotBlank() }.map { it.split("\t") }
}

private fun saveAccounts(ctx: Context, list: List<List<String>>) {
    ctx.getSharedPreferences("wesal_accounts", Context.MODE_PRIVATE).edit()
        .putString("list", list.joinToString("\n") { it.joinToString("\t") }).apply()
}

@Composable
fun AccountSwitcherDialog(onSignOut: () -> Unit) {
    if (AccountSwitcherState.show) {
        val ctx = LocalContext.current
        val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        val accounts = remember {
            val cur = loadAccounts(ctx).toMutableList()
            if (me != null) {
                cur.removeAll { it.firstOrNull() == me.uid }
                cur.add(0, listOf(me.uid, (me.displayName ?: "").replace("\t", " "), (me.email ?: me.phoneNumber ?: "")))
                saveAccounts(ctx, cur)
            }
            cur.toList()
        }
        AlertDialog(
            onDismissRequest = { AccountSwitcherState.show = false },
            title = { Text("\u062d\u0633\u0627\u0628\u0627\u062a\u064a") },
            text = {
                Column {
                    accounts.forEach { a ->
                        val isMe = me != null && a[0] == me.uid
                        val nm = a.getOrElse(1) { "" }
                        val sub = a.getOrElse(2) { "" }
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { if (!isMe) { AccountSwitcherState.show = false; onSignOut() } }
                                .padding(vertical = 10.dp)
                        ) {
                            Text((if (nm.isBlank()) sub else nm) + (if (isMe) "  \u2713" else ""))
                            Text(sub)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { AccountSwitcherState.show = false; onSignOut() }) {
                    Text("\u0625\u0636\u0627\u0641\u0629 / \u062a\u0628\u062f\u064a\u0644 \u062d\u0633\u0627\u0628")
                }
            },
            dismissButton = {
                TextButton(onClick = { AccountSwitcherState.show = false }) {
                    Text("\u0625\u0644\u063a\u0627\u0621")
                }
            }
        )
    }
}
