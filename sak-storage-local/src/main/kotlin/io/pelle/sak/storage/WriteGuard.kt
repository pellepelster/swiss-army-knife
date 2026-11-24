package io.pelle.sak.storage

import io.pelle.sak.storage.model.InsufficientStorageException
import io.pelle.sak.storage.model.EntityTooLargeException
import java.io.FilterInputStream
import java.io.InputStream

// the room a write to the data volume may take, for every store of user data on it (the buckets, the
// repositories of restic, the file storage): one write must not fill the volume, which also holds
// the database, the certificates of caddy and the logs. A write starts only while the volume has more
// than minFreeBytes free and stops when it falls below while it streams (checked every checkEvery
// bytes); one content has at most maxBytes
class WriteGuard(
    // the bytes free on the data volume
    private val freeBytes: () -> Long,
    // the bytes that stay free, for the database, the certificates and the logs to go on; who makes the
    // guard knows the volume
    val minFreeBytes: Long,
    // the most one content has, what its store allows; none without one
    val maxBytes: Long = Long.MAX_VALUE,
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
        const val CHECK_EVERY = 64L * 1024 * 1024
    }
}
