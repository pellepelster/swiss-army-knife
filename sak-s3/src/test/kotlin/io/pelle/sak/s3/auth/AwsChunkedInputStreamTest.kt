package io.pelle.sak.s3.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.EOFException

class AwsChunkedInputStreamTest {
    private fun chunked(
        vararg chunks: ByteArray,
        extension: String = "",
        trailers: List<String> = emptyList(),
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        chunks.forEach { chunk ->
            out.write("${chunk.size.toString(16)}$extension\r\n".toByteArray())
            out.write(chunk)
            out.write("\r\n".toByteArray())
        }
        out.write("0$extension\r\n".toByteArray())
        trailers.forEach { out.write("$it\r\n".toByteArray()) }
        out.write("\r\n".toByteArray())
        return out.toByteArray()
    }

    @Test
    fun `decodes a single chunk`() {
        val decoded = AwsChunkedInputStream(chunked("hello world".toByteArray()).inputStream()).readBytes()

        decoded shouldBe "hello world".toByteArray()
    }

    @Test
    fun `decodes multiple chunks byte exact`() {
        val chunk1 = ByteArray(65536) { it.toByte() }
        val chunk2 = ByteArray(1234) { (it * 7).toByte() }
        val chunk3 = "tail".toByteArray()

        val decoded = AwsChunkedInputStream(chunked(chunk1, chunk2, chunk3).inputStream()).readBytes()

        decoded shouldBe chunk1 + chunk2 + chunk3
    }

    @Test
    fun `decodes chunks with chunk signatures`() {
        val payload =
            chunked(
                "signed data".toByteArray(),
                extension = ";chunk-signature=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
            )

        AwsChunkedInputStream(payload.inputStream()).readBytes() shouldBe "signed data".toByteArray()
    }

    @Test
    fun `discards trailer headers`() {
        val payload =
            chunked(
                "data with trailer".toByteArray(),
                trailers = listOf("x-amz-checksum-crc32:AAAAAA==", "x-amz-trailer-signature:abcdef"),
            )

        AwsChunkedInputStream(payload.inputStream()).readBytes() shouldBe "data with trailer".toByteArray()
    }

    @Test
    fun `decodes an empty payload`() {
        AwsChunkedInputStream(chunked().inputStream()).readBytes() shouldBe ByteArray(0)
    }

    @Test
    fun `single byte reads return the same payload`() {
        val stream = AwsChunkedInputStream(chunked("abc".toByteArray()).inputStream())

        val bytes = generateSequence { stream.read().takeIf { it != -1 } }.map { it.toByte() }.toList().toByteArray()

        bytes shouldBe "abc".toByteArray()
    }

    @Test
    fun `truncated stream throws EOFException`() {
        val payload = "b\r\nhello".toByteArray()

        shouldThrow<EOFException> { AwsChunkedInputStream(payload.inputStream()).readBytes() }
    }
}
