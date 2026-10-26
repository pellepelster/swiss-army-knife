package io.pelle.sak.s3.auth

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

class AwsChunkedFormatException(
    message: String,
) : IOException(message)

class AwsChunkedInputStream(
    private val source: InputStream,
) : InputStream() {
    private var remainingInChunk = 0L
    private var firstChunk = true
    private var finished = false
    private val singleByte = ByteArray(1)

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
        return read
    }

    private fun nextChunk(): Boolean {
        if (!firstChunk) {
            readLine()
        }
        firstChunk = false

        val header = readLine() ?: throw EOFException("missing aws-chunked chunk header")
        val size =
            header.substringBefore(';').trim().toLongOrNull(16)?.takeIf { it >= 0 }
                ?: throw AwsChunkedFormatException("malformed aws-chunked chunk header '${header.take(64)}'")

        if (size == 0L) {
            while (true) {
                val line = readLine() ?: break
                if (line.isEmpty()) break
            }
            return false
        }

        remainingInChunk = size
        return true
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
    }
}
