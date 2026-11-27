package com.wesal.messenger.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Round 10 fix — false-offline root cause.
     *
     * The old predicate required [NetworkCapabilities.NET_CAPABILITY_VALIDATED].
     * VALIDATED is only set after Android's captive-portal probe succeeds against
     * Google endpoints; on many Egyptian carrier/Wi-Fi networks (and any network
     * where those probes are blocked, delayed or proxied) the probe never
     * succeeds even though the internet works perfectly — the status bar shows
     * live data while the app concluded "offline" and showed the
     * «غير متصل — يتم عرض المحادثات المحفوظة» banner, hard-blocking OTP sends,
     * logins, profile saves and story uploads behind the same flag.
     *
     * New rule (matches reality): a network with a real transport
     * (Wi-Fi / cellular / ethernet / VPN) that advertises INTERNET capability
     * counts as ONLINE. VALIDATED is deliberately NOT required. A genuinely
     * unvalidated network still routes traffic in the overwhelming majority of
     * real cases; when it truly cannot, the individual operation fails with its
     * own error instead of the whole app pre-emptively declaring itself offline.
     */
    private fun isUsable(capabilities: NetworkCapabilities?): Boolean {
        if (capabilities == null) return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun isCurrentlyConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        return isUsable(connectivityManager.getNetworkCapabilities(network))
    }

    val isOnline: Flow<Boolean> = callbackFlow {
        trySend(isCurrentlyConnected())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Recompute with the shared predicate instead of blindly sending
                // true (the old code did), so the flow and the one-shot check
                // can never disagree.
                trySend(isCurrentlyConnected())
            }

            override fun onLost(network: Network) {
                trySend(isCurrentlyConnected())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // Same predicate as isCurrentlyConnected(): INTERNET + transport.
                // VALIDATED is ignored on purpose (see isUsable). If this network
                // is not usable but another one is, the recompute catches it.
                trySend(isUsable(capabilities) || isCurrentlyConnected())
            }

            override fun onUnavailable() {
                trySend(isCurrentlyConnected())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
