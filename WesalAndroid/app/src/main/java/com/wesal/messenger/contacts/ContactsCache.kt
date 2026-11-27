package com.wesal.messenger.contacts

import android.content.Context
import com.wesal.messenger.domain.model.WesalUser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Round 11 (addendum 3): the persisted last-sync result for the contacts
 * screen.
 *
 * PROVEN cause of the "contacts screen opens empty" complaint: the screen's
 * ViewModel is destination-scoped, so every entry created a fresh empty
 * `ContactsUiState`, and NOTHING was persisted between visits — the lists
 * only ever appeared after a full phone-book sync completed (the slowest
 * operation on the screen: provider read + hashing ~900 contacts + ~30
 * chunked lookups), and a state reset racing the sync could wipe even
 * that. The screen now renders this cache immediately on entry (the sync
 * status line honestly shows the cached timestamp) and the fresh sync /
 * live listeners update it in place.
 *
 * The cache holds exactly what the rows render: matched contacts keep the
 * display subset of their [WesalUser] (name/handle/ids/avatar/frame/
 * verification/neon), unmatched contacts keep the full [DeviceContact].
 * It is per-account (`contacts_cache_<uid>.json` in filesDir) so an
 * account switch never leaks the previous account's list.
 */
data class ContactsCacheSnapshot(
    val matched: List<MatchedWesalContact>,
    val unmatched: List<DeviceContact>,
    val savedAtMs: Long
)

/** Pure JSON codec for [ContactsCacheSnapshot] — JVM-testable. */
object ContactsCacheCodec {

    fun encode(snapshot: ContactsCacheSnapshot): String {
        val root = JSONObject()
        root.put("savedAtMs", snapshot.savedAtMs)
        val matchedArr = JSONArray()
        snapshot.matched.forEach { m ->
            val u = m.user
            val o = JSONObject()
            o.put("deviceName", m.deviceName)
            o.put("uid", u.uid)
            o.put("displayName", u.displayName)
            o.put("publicId", u.publicId)
            o.put("numericId", u.numericId)
            o.put("photoUrl", u.photoUrl)
            o.put("frameId", u.frameId)
            o.put("frameUrl", u.frameUrl)
            o.put("frameExpiresAt", u.frameExpiresAt)
            o.put("isVerified", u.isVerified)
            o.put("verificationType", u.verificationType)
            o.put("verificationGlowColor", u.verificationGlowColor)
            o.put("verificationGlowEnabled", u.verificationGlowEnabled)
            o.put("verificationGlowIntensity", u.verificationGlowIntensity.toDouble())
            o.put("verificationExpiresAt", u.verificationExpiresAt)
            o.put("photoRemovedBg", u.photoRemovedBg)
            o.put("photoUserFramed", u.photoUserFramed)
            o.put("neonEnabled", u.neonEnabled)
            o.put("neonColor", u.neonColor)
            o.put("neonGlowIntensity", u.neonGlowIntensity.toDouble())
            o.put("nameplateColor", u.nameplateColor)
            matchedArr.put(o)
        }
        root.put("matched", matchedArr)
        val unmatchedArr = JSONArray()
        snapshot.unmatched.forEach { c ->
            val o = JSONObject()
            o.put("contactId", c.contactId)
            o.put("displayName", c.displayName)
            o.put("rawPhone", c.rawPhone)
            o.put("e164Phone", c.e164Phone ?: JSONObject.NULL)
            o.put("phoneHash", c.phoneHash ?: JSONObject.NULL)
            val hashes = JSONArray()
            c.candidateHashes.forEach { hashes.put(it) }
            o.put("candidateHashes", hashes)
            unmatchedArr.put(o)
        }
        root.put("unmatched", unmatchedArr)
        return root.toString()
    }

    /** Null on any corruption — a bad cache must degrade to "no cache", never crash. */
    fun decode(json: String): ContactsCacheSnapshot? = runCatching {
        val root = JSONObject(json)
        val matched = mutableListOf<MatchedWesalContact>()
        val matchedArr = root.optJSONArray("matched") ?: JSONArray()
        for (i in 0 until matchedArr.length()) {
            val o = matchedArr.getJSONObject(i)
            val user = WesalUser(
                uid = o.optString("uid"),
                displayName = o.optString("displayName"),
                publicId = o.optString("publicId"),
                numericId = o.optString("numericId"),
                photoUrl = o.optString("photoUrl"),
                frameId = o.optString("frameId"),
                frameUrl = o.optString("frameUrl"),
                frameExpiresAt = o.optLong("frameExpiresAt"),
                isVerified = o.optBoolean("isVerified"),
                verificationType = o.optString("verificationType", "BLUE"),
                verificationGlowColor = o.optString("verificationGlowColor"),
                verificationGlowEnabled = o.optBoolean("verificationGlowEnabled", true),
                verificationGlowIntensity = o.optDouble("verificationGlowIntensity", 0.7).toFloat(),
                verificationExpiresAt = o.optLong("verificationExpiresAt"),
                photoRemovedBg = o.optBoolean("photoRemovedBg"),
                photoUserFramed = o.optBoolean("photoUserFramed"),
                neonEnabled = o.optBoolean("neonEnabled"),
                neonColor = o.optString("neonColor"),
                neonGlowIntensity = o.optDouble("neonGlowIntensity", 0.7).toFloat(),
                nameplateColor = o.optString("nameplateColor", "CRIMSON")
            )
            if (user.uid.isNotBlank()) {
                matched += MatchedWesalContact(deviceName = o.optString("deviceName"), user = user)
            }
        }
        val unmatched = mutableListOf<DeviceContact>()
        val unmatchedArr = root.optJSONArray("unmatched") ?: JSONArray()
        for (i in 0 until unmatchedArr.length()) {
            val o = unmatchedArr.getJSONObject(i)
            val hashes = mutableListOf<String>()
            val hashArr = o.optJSONArray("candidateHashes")
            if (hashArr != null) {
                for (j in 0 until hashArr.length()) hashes += hashArr.optString(j)
            }
            unmatched += DeviceContact(
                contactId = o.optString("contactId"),
                displayName = o.optString("displayName"),
                rawPhone = o.optString("rawPhone"),
                e164Phone = o.optString("e164Phone").takeIf { o.has("e164Phone") && !o.isNull("e164Phone") && it.isNotBlank() },
                phoneHash = o.optString("phoneHash").takeIf { o.has("phoneHash") && !o.isNull("phoneHash") && it.isNotBlank() },
                candidateHashes = hashes
            )
        }
        ContactsCacheSnapshot(
            matched = matched,
            unmatched = unmatched,
            savedAtMs = root.optLong("savedAtMs")
        )
    }.getOrNull()
}

/** File-backed store for [ContactsCacheSnapshot], one file per account uid. */
class ContactsCacheStore(context: Context) {

    private val dir: File = context.filesDir

    private fun fileFor(uid: String): File =
        File(dir, "contacts_cache_${uid.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    fun load(uid: String): ContactsCacheSnapshot? {
        if (uid.isBlank()) return null
        return runCatching {
            val f = fileFor(uid)
            if (!f.exists()) null else ContactsCacheCodec.decode(f.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    fun save(uid: String, snapshot: ContactsCacheSnapshot) {
        if (uid.isBlank()) return
        runCatching {
            fileFor(uid).writeText(ContactsCacheCodec.encode(snapshot), Charsets.UTF_8)
        }
    }
}
