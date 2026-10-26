package io.pelle.sak.auth

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
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
    // tokens name the key they were signed with, so the key can be rotated later
    val keyId: String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKey.encoded))
            .take(16)

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

        // the key is generated on first start and kept, so sessions survive restarts; it is
        // written to a temporary file first, a crash never leaves half a key behind
        fun loadOrCreate(file: Path): SigningKey {
            if (Files.exists(file)) {
                return fromPem(Files.readString(file))
            }

            Files.createDirectories(file.parent)
            val key = generate()
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.deleteIfExists(tmp)
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.writeString(tmp, key.toPem())
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
            return key
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
