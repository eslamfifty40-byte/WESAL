package com.wesal.messenger.security

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.crypto.tink.aead.AeadConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real End-to-End Encryption manager using:
 * - ECDH on curve secp256r1 (P-256) for asymmetric key agreement
 * - HKDF-SHA256 (RFC 5869) for deriving conversation & media keys
 * - AES-256-GCM with 96-bit random nonces and 128-bit auth tags for messages & attachments
 * - Android Keystore MasterKey + EncryptedSharedPreferences for private key persistence
 */
@Singleton
class E2EECryptoManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val secureRandom = SecureRandom()

    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        AeadConfig.register()
    }

    fun getOrCreateIdentityPublicKeyBase64(): String {
        val existingPub = encryptedPrefs.getString(KEY_PUBLIC, null)
        val existingPriv = encryptedPrefs.getString(KEY_PRIVATE, null)
        if (!existingPub.isNullOrBlank() && !existingPriv.isNullOrBlank()) {
            return existingPub
        }

        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val keyPair = generator.generateKeyPair()

        val pubBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val privBase64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)

        encryptedPrefs.edit()
            .putString(KEY_PUBLIC, pubBase64)
            .putString(KEY_PRIVATE, privBase64)
            .apply()

        return pubBase64
    }

    fun computeSafetyFingerprint(peerPublicKeyBase64: String): String {
        val myPub = Base64.decode(getOrCreateIdentityPublicKeyBase64(), Base64.NO_WRAP)
        val peerPub = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
        val ordered = if (myPub.contentHashCode() <= peerPub.contentHashCode()) {
            myPub + peerPub
        } else {
            peerPub + myPub
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(ordered)
        return digest.take(12).joinToString(" ") { "%02X".format(it) }
    }

    fun encryptDirectMessage(
        plaintext: String,
        peerPublicKeyBase64: String,
        chatId: String
    ): EncryptedPayload {
        val sharedAesKey = deriveSharedAesKey(peerPublicKeyBase64, chatId)
        return encryptWithAesGcm(plaintext.toByteArray(Charsets.UTF_8), sharedAesKey, chatId.toByteArray())
    }

    fun decryptDirectMessage(
        payload: EncryptedPayload,
        peerPublicKeyBase64: String,
        chatId: String
    ): String {
        val sharedAesKey = deriveSharedAesKey(peerPublicKeyBase64, chatId)
        val plainBytes = decryptWithAesGcm(payload, sharedAesKey, chatId.toByteArray())
        return String(plainBytes, Charsets.UTF_8)
    }

    /**
     * Generates a fresh 256-bit AES key for a group or media file,
     * and wraps (encrypts) that key individually for each participant's public key.
     */
    fun generateSymmetricKeyBytes(): ByteArray {
        val key = ByteArray(32)
        secureRandom.nextBytes(key)
        return key
    }

    fun encryptBytesWithKey(plainBytes: ByteArray, symmetricKeyBytes: ByteArray, aad: ByteArray = ByteArray(0)): EncryptedPayload {
        val key = SecretKeySpec(symmetricKeyBytes, "AES")
        return encryptWithAesGcm(plainBytes, key, aad)
    }

    fun decryptBytesWithKey(payload: EncryptedPayload, symmetricKeyBytes: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val key = SecretKeySpec(symmetricKeyBytes, "AES")
        return decryptWithAesGcm(payload, key, aad)
    }

    fun encryptStreamToBytes(input: InputStream, symmetricKeyBytes: ByteArray): Pair<ByteArray, String> {
        val buffer = ByteArrayOutputStream()
        input.copyTo(buffer)
        val payload = encryptBytesWithKey(buffer.toByteArray(), symmetricKeyBytes)
        val combined = payload.iv + payload.ciphertext
        return combined to Base64.encodeToString(symmetricKeyBytes, Base64.NO_WRAP)
    }

    fun decryptCombinedMediaBytes(combinedBytes: ByteArray, symmetricKeyBase64: String): ByteArray {
        require(combinedBytes.size > GCM_IV_LENGTH) { "Invalid encrypted media length" }
        val iv = combinedBytes.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combinedBytes.copyOfRange(GCM_IV_LENGTH, combinedBytes.size)
        val keyBytes = Base64.decode(symmetricKeyBase64, Base64.NO_WRAP)
        return decryptBytesWithKey(EncryptedPayload(iv, ciphertext), keyBytes)
    }

    fun wrapKeyForPeer(symmetricKeyBytes: ByteArray, peerPublicKeyBase64: String, contextId: String): String {
        val sharedKey = deriveSharedAesKey(peerPublicKeyBase64, contextId)
        val wrapped = encryptWithAesGcm(symmetricKeyBytes, sharedKey, contextId.toByteArray())
        val combined = wrapped.iv + wrapped.ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun unwrapKeyFromPeer(wrappedKeyBase64: String, peerPublicKeyBase64: String, contextId: String): ByteArray {
        val combined = Base64.decode(wrappedKeyBase64, Base64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH) { "Invalid wrapped key" }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val sharedKey = deriveSharedAesKey(peerPublicKeyBase64, contextId)
        return decryptWithAesGcm(EncryptedPayload(iv, ciphertext), sharedKey, contextId.toByteArray())
    }

    private fun deriveSharedAesKey(peerPublicKeyBase64: String, infoContext: String): SecretKey {
        getOrCreateIdentityPublicKeyBase64()
        val privBytes = Base64.decode(encryptedPrefs.getString(KEY_PRIVATE, null)!!, Base64.NO_WRAP)
        val pubBytes = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)

        val keyFactory = KeyFactory.getInstance("EC")
        val privateKey: PrivateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privBytes))
        val peerPublicKey: PublicKey = keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        val sharedSecret = agreement.generateSecret()

        val derived = hkdfSha256(
            ikm = sharedSecret,
            salt = "wesal-e2ee-v1".toByteArray(Charsets.UTF_8),
            info = infoContext.toByteArray(Charsets.UTF_8),
            length = 32
        )
        return SecretKeySpec(derived, "AES")
    }

    private fun encryptWithAesGcm(plaintext: ByteArray, key: SecretKey, aad: ByteArray): EncryptedPayload {
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedPayload(iv = iv, ciphertext = ciphertext)
    }

    private fun decryptWithAesGcm(payload: EncryptedPayload, key: SecretKey, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, payload.iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(payload.ciphertext)
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
            counter++
        }
        return okm
    }

    data class EncryptedPayload(
        val iv: ByteArray,
        val ciphertext: ByteArray
    ) {
        fun toWireString(): String {
            val ivStr = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctStr = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            return "$ivStr.$ctStr"
        }

        companion object {
            fun fromWireString(wire: String): EncryptedPayload {
                val parts = wire.split('.')
                require(parts.size == 2) { "Malformed encrypted payload" }
                return EncryptedPayload(
                    iv = Base64.decode(parts[0], Base64.NO_WRAP),
                    ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
                )
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "wesal_e2ee_keystore_prefs"
        private const val KEY_PUBLIC = "identity_ec_pub_b64"
        private const val KEY_PRIVATE = "identity_ec_priv_b64"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}
