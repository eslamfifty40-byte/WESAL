package com.wesal.messenger.core.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

object LocationVerificationHelper {

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isDeviceLocationEnabled(context: Context): Boolean {
        return runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            lm.isLocationEnabled
        }.getOrDefault(false)
    }

    fun computeGraceRemainingHours(graceStartedAt: Long, now: Long = System.currentTimeMillis()): Int {
        if (graceStartedAt <= 0L) return 48
        val elapsed = (now - graceStartedAt).coerceAtLeast(0L)
        val remainingMs = (Constants.LOCATION_GRACE_PERIOD_MS - elapsed).coerceAtLeast(0L)
        return ((remainingMs + 3_599_999L) / 3_600_000L).toInt()
    }

    fun isGraceExpired(
        locationGranted: Boolean,
        graceStartedAt: Long,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (locationGranted) return false
        if (graceStartedAt <= 0L) return false
        return (now - graceStartedAt) > Constants.LOCATION_GRACE_PERIOD_MS
    }
}
