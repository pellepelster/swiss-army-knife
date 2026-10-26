package io.pelle.sak.s3.auth

import java.io.InputStream
import java.security.MessageDigest

class PayloadHashMismatchException(
    val expected: String,
    val actual: String,
) : RuntimeException("payload sha256 '$actual' does not match the declared x-amz-content-sha256 '$expected'")

// hashes everything read from the source and fails at its end when the hash is not the
// declared one, so a write fed from this stream fails before the blob is moved into place
class Sha256VerifyingInputStream(
    private val source: InputStream,
    private val expectedHex: String,
) : InputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var verified = false

    override fun read(): Int {
        val byte = source.read()
        if (byte >= 0) digest.update(byte.toByte()) else verify()
        return byte
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        val read = source.read(b, off, len)
        if (read > 0) digest.update(b, off, read) else verify()
        return read
    }

    override fun close() = source.close()

    private fun verify() {
        if (verified) return
        verified = true
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedHex, ignoreCase = true)) throw PayloadHashMismatchException(expectedHex, actual)
    }
}
