package io.pelle.sak.auth

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

// the P-256 key pair the tokens are signed with (ES256), both halves are kept in one pem
// file because the jdk can not derive the public key from the private one
class SigningKey(
    val privateKey: ECPrivateKey,
    val publicKey: ECPublicKey,
) {
    fun toPem(): String = pem(PRIVATE_KEY, privateKey.encoded) + pem(PUBLIC_KEY, publicKey.encoded)

    companion object {
        private const val PRIVATE_KEY = "PRIVATE KEY"

        private const val PUBLIC_KEY = "PUBLIC KEY"

        fun generate(): SigningKey {
            val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            return SigningKey(keyPair.private as ECPrivateKey, keyPair.public as ECPublicKey)
        }

        fun fromPem(pem: String): SigningKey {
            val keyFactory = KeyFactory.getInstance("EC")
            return SigningKey(
                keyFactory.generatePrivate(PKCS8EncodedKeySpec(block(pem, PRIVATE_KEY))) as ECPrivateKey,
                keyFactory.generatePublic(X509EncodedKeySpec(block(pem, PUBLIC_KEY))) as ECPublicKey,
            )
        }


        private fun pem(
            type: String,
            der: ByteArray,
        ) = "-----BEGIN $type-----\n${Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)}\n-----END $type-----\n"

        private fun block(
            pem: String,
            type: String,
        ): ByteArray {
            val body =
                pem.substringAfter("-----BEGIN $type-----", "").substringBefore("-----END $type-----", "").takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("pem has no '$type' block")
            return Base64.getMimeDecoder().decode(body)
        }
    }
}
