package io.pelle.sak.backend.secrets

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// a value encrypted under the secret key of keyVersion, nonce and ciphertext base64
data class Encrypted(
    val keyVersion: Int,
    val nonce: String,
    val ciphertext: String,
)

class SecretKeyException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

// encrypts with AES-256-GCM under the secret keys (sak.secret_keys) by their version, the highest
// encrypts, every one decrypts what it encrypted. The keys are random (32 bytes at least, base64),
// the AES key of each is derived with HKDF-SHA256, no slow derivation is needed. What a value is
// for (e.g. its type and scope) is authenticated with it, it does not decrypt for anything else.
// The development key of config/local-config.yaml is refused for any other domain than localhost
class SecretCipher(
    keys: Map<Int, String>,
    rootDomain: String,
) {
    private val aesKeys: Map<Int, SecretKeySpec>

    val activeVersion: Int

    init {
        if (keys.isEmpty()) throw SecretKeyException("sak.secret_keys needs at least one key")
        if (keys.keys.any { it < 1 }) throw SecretKeyException("the versions of sak.secret_keys start at 1")
        if (rootDomain != DEVELOPMENT_DOMAIN && DEVELOPMENT_KEY in keys.values) {
            throw SecretKeyException("the development key of sak.secret_keys is public, it is only taken for $DEVELOPMENT_DOMAIN")
        }
        aesKeys =
            keys.mapValues { (version, key) ->
                val bytes =
                    try {
                        Base64.getDecoder().decode(key.trim())
                    } catch (e: IllegalArgumentException) {
                        throw SecretKeyException("the key $version of sak.secret_keys is no base64", e)
                    }
                if (bytes.size < MIN_KEY_BYTES) {
                    throw SecretKeyException("the key $version of sak.secret_keys has ${bytes.size} bytes, it needs $MIN_KEY_BYTES")
                }
                SecretKeySpec(hkdf(bytes), "AES")
            }
        activeVersion = keys.keys.max()
    }

    fun encrypt(
        plaintext: String,
        associatedData: String,
    ): Encrypted {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = cipher(Cipher.ENCRYPT_MODE, activeVersion, nonce, associatedData)
        return Encrypted(activeVersion, encode(nonce), encode(cipher.doFinal(plaintext.toByteArray())))
    }

    // fails for a value encrypted under a key that is not configured, tampered with or meant for
    // something else
    fun decrypt(
        encrypted: Encrypted,
        associatedData: String,
    ): String =
        try {
            val cipher = cipher(Cipher.DECRYPT_MODE, encrypted.keyVersion, decode(encrypted.nonce), associatedData)
            String(cipher.doFinal(decode(encrypted.ciphertext)))
        } catch (e: GeneralSecurityException) {
            throw SecretKeyException("a secret of key version ${encrypted.keyVersion} does not decrypt", e)
        } catch (e: IllegalArgumentException) {
            throw SecretKeyException("a secret of key version ${encrypted.keyVersion} is no base64", e)
        }

    private fun cipher(
        mode: Int,
        version: Int,
        nonce: ByteArray,
        associatedData: String,
    ): Cipher {
        val key = aesKeys[version] ?: throw SecretKeyException("there is no key $version in sak.secret_keys")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(associatedData.toByteArray())
        }
    }

    companion object {
        // public, config/local-config.yaml has it; 32 bytes, base64
        const val DEVELOPMENT_KEY = "c2FrLWxvY2FsLWRldmVsb3BtZW50LWtleS1ub3Qtc2VjcmV0"

        private const val DEVELOPMENT_DOMAIN = "localhost"

        const val MIN_KEY_BYTES = 32

        private const val NONCE_BYTES = 12

        private const val TAG_BITS = 128

        private val random = SecureRandom()

        private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

        private fun decode(value: String) = Base64.getDecoder().decode(value)

        // HKDF-SHA256 (RFC 5869) to 32 bytes: extract with a fixed salt, expand with what the key
        // is for
        private fun hkdf(key: ByteArray): ByteArray {
            fun hmac(
                key: ByteArray,
                data: ByteArray,
            ) = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }
            val pseudoRandomKey = hmac("sak secrets".toByteArray(), key)
            return hmac(pseudoRandomKey, "aes-256-gcm".toByteArray() + byteArrayOf(1))
        }
    }
}
