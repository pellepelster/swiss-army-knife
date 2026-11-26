package io.pelle.sak.api.storage

import io.pelle.sak.api.storage.model.internal.EntityTooLargeException
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.HexFormat

// a content as it is read: counted, at most maxBytes (an EntityTooLargeException beyond), and its
// sha-256 taken along the way, so a store checks both the same way (ST-5)
class CheckedContent(
    content: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(content) {
    private val digest = MessageDigest.getInstance("SHA-256")

    // the bytes read so far
    var size = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count(byteArrayOf(it.toByte()), 0, 1) }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = super.read(buffer, offset, length).also { if (it > 0) count(buffer, offset, it) }

    private fun count(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        size += length
        if (size > maxBytes) throw EntityTooLargeException(maxBytes)
        digest.update(bytes, offset, length)
    }

    // whether what was read has the sha-256, in hex; any content when it is null. Once all is read
    fun matches(sha256: String?) = sha256 == null || HexFormat.of().formatHex(digest.digest()) == sha256
}
