package io.pelle.sak.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64

// the admin password, checked against its argon2id hash in the phc string format
// ($argon2id$v=19$m=<kib>,t=<passes>,p=<lanes>$<salt>$<hash>, base64 without padding) that sakctl
// writes into the configuration; the password itself is never kept
class AdminPassword(
    phc: String,
) {
    private val memoryKib: Int
    private val iterations: Int
    private val parallelism: Int
    private val salt: ByteArray
    private val hash: ByteArray

    init {
        val parts = phc.split('$')
        require(parts.size == 6 && parts[0].isEmpty() && parts[1] == "argon2id" && parts[2] == "v=19") {
            "the admin password is no argon2id hash of version 19"
        }
        val parameters =
            parts[3].split(',').associate {
                val (key, value) = it.split('=', limit = 2)
                key to value.toInt()
            }
        memoryKib = requireNotNull(parameters["m"]) { "the admin password hash has no memory cost" }
        iterations = requireNotNull(parameters["t"]) { "the admin password hash has no time cost" }
        parallelism = requireNotNull(parameters["p"]) { "the admin password hash has no parallelism" }
        val decoder = Base64.getDecoder()
        salt = decoder.decode(parts[4])
        hash = decoder.decode(parts[5])
    }

    // whether the password has the hash, the password as nfc like sakctl hashes it, so it does not
    // matter how it was typed; compared in constant time
    fun matches(password: String): Boolean {
        val parameters =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build()
        val actual = ByteArray(hash.size)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(), actual)
        return MessageDigest.isEqual(hash, actual)
    }

    // the hash never shows
    override fun toString() = "AdminPassword(argon2id)"
}
