package com.wesal.messenger.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.domain.model.SavedWesalAccount
import com.wesal.messenger.presentation.components.WesalAvatar
import org.json.JSONObject

/**
 * Encrypted store for saved-login credentials ("حفظ الدخول" / remember me, spec §3.1-3.2).
 *
 * Uses the same Android Keystore pattern as the hidden-chat password vault in
 * UserPreferencesDataStore (MasterKey AES256_GCM + EncryptedSharedPreferences
 * AES256_SIV/AES256_GCM), in its own file. Passwords are NEVER logged and never
 * written anywhere else. If the encrypted store cannot be created the vault is
 * disabled (no plaintext fallback for passwords — saving simply does nothing).
 *
 * Layout: one JSON entry per account keyed by uid ("cred_uid_<uid>" ->
 * {"email":..., "password":...}) plus an email index ("cred_email_<email>" -> uid)
 * so the login screen can find a credential from a saved account's email.
 */
object WesalCredentialVault {
    private const val PREFS_NAME = "wesal_saved_credentials"
    private const val UID_KEY_PREFIX = "cred_uid_"
    private const val EMAIL_KEY_PREFIX = "cred_email_"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences? {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            val created = runCatching {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrNull()
            cachedPrefs = created
            return created
        }
    }

    fun save(context: Context, uid: String, email: String, password: String) {
        if (uid.isBlank() || email.isBlank() || password.isEmpty()) return
        val store = prefs(context) ?: return
        runCatching {
            val cleanEmail = email.trim().lowercase()
            val payload = JSONObject()
                .put("email", cleanEmail)
                .put("password", password)
                .toString()
            store.edit()
                .putString(UID_KEY_PREFIX + uid, payload)
                .putString(EMAIL_KEY_PREFIX + cleanEmail, uid)
                .apply()
        }
    }

    fun passwordForEmail(context: Context, email: String): String? {
        if (email.isBlank()) return null
        val store = prefs(context) ?: return null
        return runCatching {
            val uid = store.getString(EMAIL_KEY_PREFIX + email.trim().lowercase(), null)
                ?: return null
            val raw = store.getString(UID_KEY_PREFIX + uid, null) ?: return null
            JSONObject(raw).optString("password").takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun hasCredential(context: Context, email: String): Boolean =
        !passwordForEmail(context, email).isNullOrEmpty()

    fun removeByEmail(context: Context, email: String) {
        if (email.isBlank()) return
        val store = prefs(context) ?: return
        runCatching {
            val cleanEmail = email.trim().lowercase()
            val uid = store.getString(EMAIL_KEY_PREFIX + cleanEmail, null)
            val editor = store.edit().remove(EMAIL_KEY_PREFIX + cleanEmail)
            if (!uid.isNullOrBlank()) editor.remove(UID_KEY_PREFIX + uid)
            editor.apply()
        }
    }

    fun removeByUid(context: Context, uid: String) {
        if (uid.isBlank()) return
        val store = prefs(context) ?: return
        runCatching {
            val raw = store.getString(UID_KEY_PREFIX + uid, null)
            val email = raw?.let { runCatching { JSONObject(it).optString("email") }.getOrNull() }
                .orEmpty()
            val editor = store.edit().remove(UID_KEY_PREFIX + uid)
            if (email.isNotBlank()) editor.remove(EMAIL_KEY_PREFIX + email.trim().lowercase())
            editor.apply()
        }
    }
}

/**
 * One-shot notice used when this device's session was revoked from another device
 * (spec §3.5): AuthRepositoryImpl stashes the message when it force-signs-out
 * locally, and the login screen consumes + displays it exactly once.
 */
object SessionRevokeNotice {
    private const val PREFS_NAME = "wesal_auth_events"
    private const val KEY_MESSAGE = "session_revoked_message"

    fun stash(context: Context, message: String) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MESSAGE, message)
                .apply()
        }
    }

    fun consume(context: Context): String? {
        return runCatching {
            val store = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val message = store.getString(KEY_MESSAGE, null)?.takeIf { it.isNotBlank() }
            if (message != null) store.edit().remove(KEY_MESSAGE).apply()
            message
        }.getOrNull()
    }
}

/**
 * Real account switcher (spec §3.2): lists the device's saved accounts and switches
 * in place. Tapping an email account that has a stored credential performs the fast
 * switch (handled by the caller's onSwitch); accounts without one fall back to the
 * caller's prefill behaviour. Replaces the old dead dialog that only signed out.
 */
@Composable
fun AccountSwitcherDialog(
    accounts: List<SavedWesalAccount>,
    currentUid: String?,
    isSwitching: Boolean,
    onDismiss: () -> Unit,
    onSwitch: (SavedWesalAccount) -> Unit,
    onAddAccount: () -> Unit,
    onRemove: (SavedWesalAccount) -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    AlertDialog(
        onDismissRequest = { if (!isSwitching) onDismiss() },
        containerColor = palette.surface,
        title = {
            Text(strings.switchAccount, color = palette.textPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isSwitching) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = palette.emerald)
                }
                if (accounts.isEmpty()) {
                    Text(
                        text = if (strings.isEnglish) {
                            "Add another Google or email account to switch quickly."
                        } else {
                            "أضف حساب Google أو بريد إلكتروني آخر للتنقل السريع."
                        },
                        color = palette.textSecondary
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(accounts, key = { it.uid }) { acc ->
                            val isActive = acc.uid == currentUid
                            Surface(
                                color = if (isActive) palette.emerald.copy(alpha = 0.12f) else palette.background,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isActive) palette.emerald.copy(alpha = 0.5f) else palette.border
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isActive && !isSwitching) { onSwitch(acc) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    WesalAvatar(
                                        imageUrl = acc.photoUrl,
                                        name = acc.displayName.ifBlank { acc.email.ifBlank { "WESAL" } },
                                        size = 40.dp
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = acc.displayName.ifBlank { acc.email.ifBlank { "WESAL" } },
                                                color = palette.textPrimary,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (isActive) {
                                                Surface(
                                                    color = palette.emerald.copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = if (strings.isEnglish) "Active ✓" else "نشط ✓",
                                                        color = palette.emerald,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        val meta = listOfNotNull(
                                            acc.numericId.takeIf { it.isNotBlank() }?.let { "ID: $it" },
                                            acc.publicId.takeIf { it.isNotBlank() }?.let { "@$it" },
                                            acc.email.takeIf { it.isNotBlank() }
                                        ).joinToString(" • ")
                                        if (meta.isNotBlank()) {
                                            Text(
                                                meta,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = palette.textSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (!isActive) {
                                        IconButton(
                                            onClick = { onRemove(acc) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = strings.delete,
                                                tint = palette.textMuted,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = onAddAccount,
                    enabled = !isSwitching,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.emerald,
                        contentColor = palette.onAccent
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.addAccount, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !isSwitching, onClick = onDismiss) {
                Text(strings.close, color = palette.turquoise)
            }
        }
    )
}
