package com.wesal.messenger.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.wesal.messenger.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class WesalUpdateInfo(
    val tag: String,
    val apkUrl: String,
    val notes: String
)

/**
 * Lightweight in-app updater fed by the GitHub prereleases published for WESAL
 * (tag scheme "v1.0.0-<commitSha>", matching the app's versionName "1.0.0-<commitSha>").
 * No Play Store involved: the app polls the repo's public releases once per cold
 * start, and when the newest release carries a different build sha it offers to
 * download the APK and hand it to the system package installer. All builds share
 * the checked-in debug keystore, so updates install in place.
 */
object AppUpdateChecker {

    private const val REPO = "eslamfifty40-byte/WESAL"
    private const val TAG_PREFIX = "v1.0.0-"

    /** Returns the newest published build when its sha differs from the installed one, else null. */
    suspend fun fetchLatestUpdate(context: Context): WesalUpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val installedSha = installedBuildSha(context)
            val conn = (URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "WESAL-Android")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val releases = JSONArray(body)
            // GitHub does not list releases newest-first by creation time, so pick the newest
            // by created_at ourselves (taking the first item offered stale builds as "updates").
            var newest: org.json.JSONObject? = null
            var newestAt = ""
            for (i in 0 until releases.length()) {
                val rel = releases.getJSONObject(i)
                if (!rel.optString("tag_name").startsWith(TAG_PREFIX)) continue
                if (rel.optBoolean("draft")) continue
                val at = rel.optString("created_at")
                if (newest == null || at > newestAt) { newest = rel; newestAt = at }
            }
            val rel = newest ?: return@runCatching null
            val tag = rel.optString("tag_name")
            if (tag.removePrefix(TAG_PREFIX) == installedSha) return@runCatching null
            val assets = rel.optJSONArray("assets") ?: return@runCatching null
            var apkUrl = ""
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                if (asset.optString("name").endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isNotBlank()) {
                return@runCatching WesalUpdateInfo(tag, apkUrl, rel.optString("body"))
            }
            null
        }.getOrNull()
    }

    private fun installedBuildSha(context: Context): String {
        val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        return versionName.substringAfter("1.0.0-", "")
    }

    /** Downloads the APK in the background and launches the system installer when done. */
    fun downloadAndInstall(context: Context, update: WesalUpdateInfo) {
        val appContext = context.applicationContext
        val dir = appContext.getExternalFilesDir("updates") ?: appContext.cacheDir
        val apkFile = File(dir, "wesal-${update.tag}.apk")

        val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(update.apkUrl)).apply {
            setTitle("WESAL ${update.tag}")
            setDestinationUri(Uri.fromFile(apkFile))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setMimeType("application/vnd.android.package-archive")
        }
        val downloadId = manager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                runCatching { appContext.unregisterReceiver(this) }
                val query = manager.query(DownloadManager.Query().setFilterById(downloadId))
                val success = query.use { cursor ->
                    cursor.moveToFirst() &&
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                }
                if (success && apkFile.exists()) {
                    installApk(appContext, apkFile)
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
