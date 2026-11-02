package io.pelle.sak.openrouter

import java.io.InputStream
import java.net.http.HttpRequest
import io.pelle.sak.storage.BlobContent
import java.util.Base64

// in the json of a request, where the base64 of the file goes; no other value of a request holds it
internal const val BASE64_DATA = "SAK_BASE64_DATA_PLACEHOLDER"

// the json with the base64 of the content in place of BASE64_DATA, read from the storage while it is
// sent: the memory is a block, not the content (a recording of five minutes is 9.6 MB, its base64
// 12.8 MB). Base64 needs no escaping in json
internal fun base64Body(
    json: String,
    content: BlobContent,
): HttpRequest.BodyPublisher {
    val parts = json.split(BASE64_DATA)
    require(parts.size == 2) { "the json has no place for the data" }
    val encodedSize = (content.size + 2) / 3 * 4
    val data =
        if (encodedSize == 0L) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream { Base64InputStream(content.open()) },
                encodedSize,
            )
        }
    return HttpRequest.BodyPublishers.concat(
        HttpRequest.BodyPublishers.ofString(parts[0]),
        data,
        HttpRequest.BodyPublishers.ofString(parts[1]),
    )
}

// the base64 of the input, encoded block by block
internal class Base64InputStream(
    private val input: InputStream,
) : InputStream() {
    private val encoder = Base64.getEncoder()

    private var block = ByteArray(0)

    private var position = 0

    // false at the end of the input
    private fun fill(): Boolean {
        while (position >= block.size) {
            // a multiple of 3, only the last block is padded
            val raw = input.readNBytes(BLOCK_SIZE)
            if (raw.isEmpty()) return false
            block = encoder.encode(raw)
            position = 0
        }
        return true
    }

    override fun read(): Int = if (fill()) block[position++].toInt() and 0xFF else -1

    override fun read(
        target: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (!fill()) return -1
        val count = minOf(length, block.size - position)
        System.arraycopy(block, position, target, offset, count)
        position += count
        return count
    }

    override fun close() = input.close()

    private companion object {
        const val BLOCK_SIZE = 3 * 16 * 1024
    }
}
