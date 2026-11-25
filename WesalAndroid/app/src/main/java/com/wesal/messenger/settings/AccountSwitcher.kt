package com.wesal.messenger.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings

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
        val palette = WesalColors.current
        val strings = LocalWesalStrings.current
        val me = FirebaseAuth.getInstance().currentUser
        val accounts = remember(AccountSwitcherState.show) {
            val cur = loadAccounts(ctx).toMutableList()
            if (me != null) {
                cur.removeAll { it.firstOrNull() == me.uid }
                cur.add(0, listOf(me.uid, (me.displayName ?: "").replace("\t", " "), (me.email ?: "")))
                saveAccounts(ctx, cur)
            }
            cur.toList()
        }
        AlertDialog(
            onDismissRequest = { AccountSwitcherState.show = false },
            containerColor = palette.surface,
            title = { Text(strings.switchAccount, color = palette.textPrimary, fontWeight = FontWeight.Bold) },
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
                            Text(
                                text = (if (nm.isBlank()) sub else nm) + (if (isMe) "  ✓" else ""),
                                color = if (isMe) palette.emerald else palette.textPrimary,
                                fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium
                            )
                            if (sub.isNotBlank()) {
                                Text(sub, color = palette.textSecondary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { AccountSwitcherState.show = false; onSignOut() }) {
                    Text(strings.addAccount, color = palette.emerald)
                }
            },
            dismissButton = {
                TextButton(onClick = { AccountSwitcherState.show = false }) {
                    Text(strings.cancel, color = palette.textSecondary)
                }
            }
        )
    }
}
