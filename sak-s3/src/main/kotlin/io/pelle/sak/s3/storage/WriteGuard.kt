package io.pelle.sak.s3.storage

import java.io.FilterInputStream
import java.io.InputStream

// the room a write to a bucket may take: one key must not fill the data volume, which also has the
// database, the certificates of caddy and the logs. A write starts only while the volume has more
// than minFreeBytes free and stops when it falls below while it streams (checked every checkEvery
// bytes); an object or a part has at most maxBytes, as in S3
class WriteGuard(
    // the bytes free on the volume of the buckets
    private val freeBytes: () -> Long,
    val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
    val maxBytes: Long = MAX_OBJECT_BYTES,
    // how many bytes are read between two looks at the free bytes
    private val checkEvery: Long = CHECK_EVERY,
) {
    fun requireRoom() {
        if (freeBytes() < minFreeBytes) throw InsufficientStorageException(minFreeBytes)
    }

    // the content, read only up to maxBytes and while the volume has room
    fun guarded(
        content: InputStream,
        maxBytes: Long = this.maxBytes,
    ): InputStream = Guarded(content, maxBytes)

    private inner class Guarded(
        delegate: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(delegate) {
        private var read = 0L
        private var nextCheck = checkEvery

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = super.read(buffer, offset, length).also { if (it > 0) count(it) }

        private fun count(bytes: Int) {
            read += bytes
            if (read > maxBytes) throw EntityTooLargeException(maxBytes)
            if (read >= nextCheck) {
                nextCheck += checkEvery
                requireRoom()
            }
        }
    }

    companion object {
        // room for the database, the certificates and the logs to go on
        const val DEFAULT_MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024

        // the largest object of one request and the largest part, as S3 has them
        const val MAX_OBJECT_BYTES = 5L * 1024 * 1024 * 1024

        // the largest object made of parts, as S3 has it
        const val MAX_MULTIPART_OBJECT_BYTES = 5L * 1024 * 1024 * 1024 * 1024

        const val CHECK_EVERY = 64L * 1024 * 1024
    }
}
