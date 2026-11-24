package io.pelle.sak.storage.model

import java.io.InputStream

// a blob to read: its size, and its content, opened again for every read
class BlobContent(
    val size: Long,
    private val opener: () -> InputStream,
) {
    fun open(): InputStream = opener()
}
