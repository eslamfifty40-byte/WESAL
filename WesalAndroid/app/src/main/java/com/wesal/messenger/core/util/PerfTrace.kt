package com.wesal.messenger.core.util

import android.os.SystemClock
import android.util.Log
import com.wesal.messenger.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * §13.2 measurement: tiny DEBUG-only timing marks written to Logcat under the
 * "WesalPerf" tag (cold start, first frame, first snapshots, avatar decode…).
 * Every entry point is a no-op in release builds — [BuildConfig.DEBUG] gates
 * both the mark store and the logging, so release pays only a boolean check.
 */
object PerfTrace {

    private const val TAG = "WesalPerf"

    private val marks = ConcurrentHashMap<String, Long>()

    /** Records the current uptime under [tag] (overwrites any previous mark). */
    fun mark(tag: String) {
        if (!BuildConfig.DEBUG) return
        marks[tag] = SystemClock.uptimeMillis()
    }

    /** Logs the elapsed time between the [fromTag] mark and now as [tag]. */
    fun logElapsed(tag: String, fromTag: String) {
        if (!BuildConfig.DEBUG) return
        val from = marks[fromTag]
        if (from == null) {
            Log.d(TAG, "$tag (start mark '$fromTag' missing)")
        } else {
            Log.d(TAG, "$tag: ${SystemClock.uptimeMillis() - from}ms since $fromTag")
        }
    }
}
