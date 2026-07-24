package tw.kevinzhang.moneylook.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

data class ProtectedCardPan(
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val fingerprint: String,
) {
    override fun toString(): String = "ProtectedCardPan([REDACTED])"
}

/** Encrypts PANs before they enter Room and provides a stable local-only fingerprint. */
interface CardPanProtector {
    fun protect(pan: String): ProtectedCardPan
    fun reveal(ciphertext: ByteArray, iv: ByteArray): String
}

/**
 * Android Keystore-backed storage protection. The storage key intentionally does not require
 * per-use user authentication because WorkManager must be able to persist a background sync.
 * Callers must put biometric/device-auth policy in front of [reveal].
 */
@Singleton
class AndroidKeystoreCardPanProtector @Inject constructor() : CardPanProtector {
    override fun protect(pan: String): ProtectedCardPan {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        return ProtectedCardPan(
            ciphertext = cipher.doFinal(pan.toByteArray(StandardCharsets.US_ASCII)),
            iv = cipher.iv,
            fingerprint = keyedFingerprint(pan),
        )
    }

    override fun reveal(ciphertext: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), javax.crypto.spec.GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(StandardCharsets.US_ASCII)
    }

    private fun keyedFingerprint(pan: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(fingerprintKey())
        return mac.doFinal(pan.toByteArray(StandardCharsets.US_ASCII)).toHex()
    }

    @Synchronized
    private fun encryptionKey(): SecretKey = keyStoreSecretKey(ENCRYPTION_ALIAS) ?: run {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ENCRYPTION_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    @Synchronized
    private fun fingerprintKey(): SecretKey = keyStoreSecretKey(FINGERPRINT_ALIAS) ?: run {
        val generator = KeyGenerator.getInstance(HMAC_ALGORITHM, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                FINGERPRINT_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            ).build(),
        )
        generator.generateKey()
    }

    private fun keyStoreSecretKey(alias: String): SecretKey? = KeyStore.getInstance(KEYSTORE_PROVIDER)
        .apply { load(null) }
        .getKey(alias, null) as? SecretKey

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val ENCRYPTION_ALIAS = "moneylook.card-pan.aes.v1"
        const val FINGERPRINT_ALIAS = "moneylook.card-pan.fingerprint.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val TAG_BITS = 128
    }
}
