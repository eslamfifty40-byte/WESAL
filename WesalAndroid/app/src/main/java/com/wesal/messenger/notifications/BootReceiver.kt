package com.wesal.messenger.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth

/**
 * Restarts [WesalListenerService] after a reboot when a session persists.
 *
 * Firebase Auth persists the signed-in user across reboots (and the app keeps its
 * own saved-accounts list), so a reboot must not silently end out-of-app message
 * and call notifications until the user happens to open the app again. If nobody
 * is signed in, nothing starts. BOOT_COMPLETED is one of the broadcasts exempt
 * from the background foreground-service start restrictions, and the service's
 * remoteMessaging type is not among the types Android 15 bars from boot starts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val signedIn = runCatching { FirebaseAuth.getInstance().currentUser != null }
            .getOrDefault(false)
        if (signedIn) {
            WesalListenerService.start(context)
        }
    }
}
