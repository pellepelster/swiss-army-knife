package io.pelle.sak.s3.auth

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// what the chunks of a signed streamed upload (STREAMING-AWS4-HMAC-SHA256-PAYLOAD[-TRAILER]) are
// verified with (SEC-23): the signing key of the request, its time and scope, and its signature, the
// seed of the chain. Each chunk signs its data and the signature before it, the trailer its headers
internal class ChunkSigning internal constructor(
    private val signingKey: ByteArray,
    private val amzDate: String,
    private val scope: String,
    seedSignature: String,
) {
    private var previous = seedSignature

    // whether the signature of the chunk with this sha256 holds; it is the previous one of the next
    internal fun chunk(
        signature: String,
        dataSha256: ByteArray,
    ): Boolean = next(signature, "AWS4-HMAC-SHA256-PAYLOAD", "$EMPTY_SHA256\n${dataSha256.toHex()}")

    // whether the signature of the trailer holds, over its headers as they were sent, each with \n
    internal fun trailer(
        signature: String,
        headers: String,
    ): Boolean = next(signature, "AWS4-HMAC-SHA256-TRAILER", sha256(headers.toByteArray()).toHex())

    private fun next(
        signature: String,
        algorithm: String,
        hashes: String,
    ): Boolean {
        val expected = hmacSha256("$algorithm\n$amzDate\n$scope\n$previous\n$hashes").toHex()
        if (!MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray())) return false
        previous = expected
        return true
    }

    private fun hmacSha256(data: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(signingKey, "HmacSHA256"))
            doFinal(data.toByteArray())
        }

    private companion object {
        val EMPTY_SHA256 = sha256(ByteArray(0)).toHex()

        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
