package io.pelle.sak.s3.auth

import java.io.EOFException
import java.io.InputStream

class AwsChunkedInputStream(
    private val source: InputStream,
) : InputStream() {
    private var remainingInChunk = 0L
    private var firstChunk = true
    private var finished = false

    override fun read(): Int {
        val buffer = ByteArray(1)
        val read = read(buffer, 0, 1)
        return if (read == -1) -1 else buffer[0].toInt() and 0xff
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
            header.substringBefore(';').trim().toLongOrNull(16)
                ?: throw EOFException("malformed aws-chunked chunk header '$header'")

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

    private fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            val byte = source.read()
            if (byte == -1) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(byte.toChar())
        }
    }
}
