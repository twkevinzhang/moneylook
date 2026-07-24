package tw.kevinzhang.moneylook.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

data class ProtectedSourceFingerprint(val value: String, val keyVersion: Int)

/** Per-install HMAC protection for source metadata recorded in the audit trail. */
interface SourceFingerprintProtector {
    fun fingerprint(vararg components: String): ProtectedSourceFingerprint
}

@Singleton
class AndroidKeystoreSourceFingerprintProtector @Inject constructor() : SourceFingerprintProtector {
    override fun fingerprint(vararg components: String): ProtectedSourceFingerprint {
        val canonical = components.joinToString("\u001f") { "${it.length}:$it" }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(key())
        val value = mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return ProtectedSourceFingerprint(value, KEY_VERSION)
    }

    @Synchronized
    private fun key(): SecretKey = (KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        .getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
        val generator = KeyGenerator.getInstance(HMAC_ALGORITHM, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .build(),
        )
        generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val KEY_VERSION = 1
        const val KEY_ALIAS = "moneylook.source-fingerprint.hmac.v1"
    }
}
