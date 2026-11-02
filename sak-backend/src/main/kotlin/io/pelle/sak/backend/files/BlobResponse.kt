package io.pelle.sak.backend.files

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.pelle.sak.storage.BlobContent

// a blob of the binary storage as the body of a response, read while it is sent, with its length
class BlobResponse(
    private val content: BlobContent,
    override val contentType: ContentType,
) : OutgoingContent.ReadChannelContent() {
    override val contentLength: Long get() = content.size

    override fun readFrom(): ByteReadChannel = content.open().toByteReadChannel()
}
