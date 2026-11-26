package io.pelle.sak.s3.auth

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

internal class AwsChunkedFormatException(
    message: String,
) : IOException(message)

// a chunk or the trailer of a signed streamed upload whose signature does not hold (SEC-23)
internal class ChunkSignatureMismatchException(
    message: String,
) : IOException(message)

// the checksum of the trailer, or the decoded length, is not the one of what came (SEC-23)
internal class ChecksumMismatchException(
    message: String,
) : IOException(message)

// the data of an aws-chunked body. With signing every chunk is verified against the chain of
// signatures that starts at the signature of the request, and a trailer against its own (SEC-23);
// the checksum the trailer names (x-amz-trailer) and the decoded length are verified too. All is
// verified before the end of the stream is told, so a body that fails is never read to its end
internal class AwsChunkedInputStream(
    private val source: InputStream,
    // null for chunks without signatures (STREAMING-UNSIGNED-PAYLOAD-TRAILER)
    private val signing: ChunkSigning? = null,
    // whether the trailer of a signed body is signed itself (STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER)
    private val signedTrailer: Boolean = false,
    // the header of the checksum in the trailer, e.g. x-amz-checksum-crc32, null for none
    private val trailer: String? = null,
    // the length of the data, x-amz-decoded-content-length, null when not told
    private val decodedLength: Long? = null,
) : InputStream() {
    private var remainingInChunk = 0L
    private var firstChunk = true
    private var finished = false
    private val singleByte = ByteArray(1)

    // the signature the header of the chunk read now claims, and the sha256 of its data so far
    private var chunkSignature: String? = null
    private val chunkDigest = MessageDigest.getInstance("SHA-256")

    private val checksum =
        trailer?.let { TrailerChecksum.of(it) ?: throw AwsChunkedFormatException("unsupported trailing checksum '${it.take(64)}'") }
    private var length = 0L

    override fun read(): Int {
        val read = read(singleByte, 0, 1)
        return if (read == -1) -1 else singleByte[0].toInt() and 0xff
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        if (finished) return -1

        if (remainingInChunk == 0L && !nextChunk()) {
            finished = true
            return -1
        }

        val read = source.read(b, off, minOf(len.toLong(), remainingInChunk).toInt())
        if (read == -1) throw EOFException("premature end of aws-chunked stream")
        remainingInChunk -= read
        length += read
        if (signing != null) chunkDigest.update(b, off, read)
        checksum?.update(b, off, read)
        return read
    }

    private fun nextChunk(): Boolean {
        if (!firstChunk) {
            readLine()
            verifyChunk()
        }
        firstChunk = false

        val header = readLine() ?: throw EOFException("missing aws-chunked chunk header")
        val size =
            header.substringBefore(';').trim().toLongOrNull(16)?.takeIf { it >= 0 }
                ?: throw AwsChunkedFormatException("malformed aws-chunked chunk header '${header.take(64)}'")
        chunkSignature = header.substringAfter(';', "").trim().takeIf { it.startsWith(CHUNK_SIGNATURE) }?.removePrefix(CHUNK_SIGNATURE)

        if (size == 0L) {
            // the last chunk is signed too, over no data
            verifyChunk()
            readTrailer()
            verifyEnd()
            return false
        }

        remainingInChunk = size
        return true
    }

    // the signature of the chunk just read, which the next one chains to
    private fun verifyChunk() {
        val signing = signing ?: return
        val signature = chunkSignature ?: throw ChunkSignatureMismatchException("a chunk of a signed aws-chunked body has no signature")
        if (!signing.chunk(signature, chunkDigest.digest())) throw ChunkSignatureMismatchException("the signature of a chunk does not match")
    }

    // the lines after the last chunk up to the empty one: the checksum, and for a signed trailer its
    // signature, which signs the other lines as they came, each with \n
    private fun readTrailer() {
        val lines = mutableListOf<String>()
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            lines += line
        }
        val (signatures, headers) = lines.partition { it.startsWith(TRAILER_SIGNATURE) }
        if (signing != null && signedTrailer) {
            val signature = signatures.singleOrNull()?.removePrefix(TRAILER_SIGNATURE)?.trim()
                ?: throw ChunkSignatureMismatchException("the trailer of a signed aws-chunked body has no signature")
            if (!signing.trailer(signature, headers.joinToString("") { "$it\n" })) {
                throw ChunkSignatureMismatchException("the signature of the trailer does not match")
            }
        }
        val checksum = checksum ?: return
        val value =
            headers.firstOrNull { it.substringBefore(':').trim().equals(trailer, ignoreCase = true) }?.substringAfter(':')?.trim()
                ?: throw ChecksumMismatchException("the trailer has no $trailer")
        if (!MessageDigest.isEqual(Base64.getEncoder().encodeToString(checksum.value()).toByteArray(), value.toByteArray())) {
            throw ChecksumMismatchException("the $trailer of the trailer does not match the data")
        }
    }

    private fun verifyEnd() {
        if (decodedLength != null && length != decodedLength) {
            throw ChecksumMismatchException("the data has $length bytes, x-amz-decoded-content-length says $decodedLength")
        }
    }

    // chunk headers and trailers are short, a client sending an endless line must not grow
    // the buffer without bounds
    private fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            val byte = source.read()
            if (byte == -1) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length >= MAX_LINE_LENGTH) throw AwsChunkedFormatException("aws-chunked line exceeds $MAX_LINE_LENGTH bytes")
            line.append(byte.toChar())
        }
    }

    companion object {
        const val MAX_LINE_LENGTH = 4096

        private const val CHUNK_SIGNATURE = "chunk-signature="

        private const val TRAILER_SIGNATURE = "x-amz-trailer-signature:"
    }
}
