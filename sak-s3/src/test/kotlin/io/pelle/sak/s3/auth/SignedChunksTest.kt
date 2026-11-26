package io.pelle.sak.s3.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// the chunks of a signed streamed upload (SEC-23), with the example of the documentation of S3:
// "Signature Calculations for the Authorization Header: Transferring Payload in Multiple Chunks"
class SignedChunksTest {
    private val date = "20130524T000000Z"
    private val scope = "20130524/us-east-1/s3/aws4_request"
    private val seed = "4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9"

    private fun hmac(
        key: ByteArray,
        data: String,
    ): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data.toByteArray()) }

    private val signingKey =
        listOf("20130524", "us-east-1", "s3", "aws4_request").fold("AWS4wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY".toByteArray(), ::hmac)

    private fun signing() = ChunkSigning(signingKey, date, scope, seed)

    // the body of the example: 65536 and 1024 bytes of 'a', then the last chunk, with their signatures
    private fun body(
        data: ByteArray = ByteArray(66560) { 'a'.code.toByte() },
        signatures: List<String?> =
            listOf(
                "ad80c730a21e5b8d04586a2213dd63b9a0e99e0e2307b0ade35a65485a288648",
                "0055627c9e194cb4542bae2aa5492e3c1575bbb81b612b7d234b86a503ef5497",
                "b6c6ea8a5354eaf15b3cb7646744f4275b71ea724fed81ceb9323e279d449df9",
            ),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        listOf(data.copyOfRange(0, 65536), data.copyOfRange(65536, data.size), ByteArray(0)).zip(signatures).forEach { (chunk, signature) ->
            out.write("${chunk.size.toString(16)}${signature?.let { ";chunk-signature=$it" }.orEmpty()}\r\n".toByteArray())
            out.write(chunk)
            if (chunk.isNotEmpty()) out.write("\r\n".toByteArray())
        }
        out.write("\r\n".toByteArray())
        return out.toByteArray()
    }

    @Test
    fun `the chunks of the example of S3 are taken with their signatures`() {
        AwsChunkedInputStream(body().inputStream(), signing(), decodedLength = 66560).readBytes() shouldBe ByteArray(66560) { 'a'.code.toByte() }
    }

    @Test
    fun `a changed byte, a missing or a wrong signature of a chunk is refused`() {
        val changed = ByteArray(66560) { 'a'.code.toByte() }.also { it[70_000 - 4000] = 'b'.code.toByte() }
        shouldThrow<ChunkSignatureMismatchException> { AwsChunkedInputStream(body(changed).inputStream(), signing()).readBytes() }
        shouldThrow<ChunkSignatureMismatchException> {
            AwsChunkedInputStream(body(signatures = listOf(null, null, null)).inputStream(), signing()).readBytes()
        }
        // the signatures chain: one of another place is wrong here
        val swapped = listOf("0055627c9e194cb4542bae2aa5492e3c1575bbb81b612b7d234b86a503ef5497", "ad80c730a21e5b8d04586a2213dd63b9a0e99e0e2307b0ade35a65485a288648", "x")
        shouldThrow<ChunkSignatureMismatchException> { AwsChunkedInputStream(body(signatures = swapped).inputStream(), signing()).readBytes() }
        // the last chunk is signed too: a wrong signature of it is refused
        val last = String(body(), Charsets.ISO_8859_1).replace("b6c6ea8a", "00000000").toByteArray(Charsets.ISO_8859_1)
        shouldThrow<ChunkSignatureMismatchException> { AwsChunkedInputStream(last.inputStream(), signing()).readBytes() }
    }

    @Test
    fun `a signed trailer holds over its checksum, which holds over the data`() {
        val data = "hello world".toByteArray()
        val crc = Base64.getEncoder().encodeToString(CRC32().apply { update(data) }.value.let { java.nio.ByteBuffer.allocate(4).putInt(it.toInt()).array() })
        // the chain as a client makes it
        fun sign(
            algorithm: String,
            hashes: String,
        ): String {
            val previous = chainSignature ?: seed
            val signature = hmac(signingKey, "$algorithm\n$date\n$scope\n$previous\n$hashes").joinToString("") { "%02x".format(it) }
            chainSignature = signature
            return signature
        }
        chainSignature = null
        val sha = { bytes: ByteArray -> java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
        val empty = sha(ByteArray(0))
        val first = sign("AWS4-HMAC-SHA256-PAYLOAD", "$empty\n${sha(data)}")
        val last = sign("AWS4-HMAC-SHA256-PAYLOAD", "$empty\n$empty")
        fun body(checksum: String): ByteArray {
            val saved = chainSignature
            val trailerSignature = sign("AWS4-HMAC-SHA256-TRAILER", sha("x-amz-checksum-crc32:$checksum\n".toByteArray()))
            chainSignature = saved
            return ("b;chunk-signature=$first\r\nhello world\r\n0;chunk-signature=$last\r\n" +
                "x-amz-checksum-crc32:$checksum\r\nx-amz-trailer-signature:$trailerSignature\r\n\r\n").toByteArray()
        }
        fun read(body: ByteArray) =
            AwsChunkedInputStream(body.inputStream(), signing(), signedTrailer = true, trailer = "x-amz-checksum-crc32", decodedLength = 11).readBytes()

        read(body(crc)) shouldBe data
        // a checksum of other data, signed, is refused for the data
        shouldThrow<ChecksumMismatchException> { read(body("AAAAAA==")) }
        // a trailer changed after it was signed
        shouldThrow<ChunkSignatureMismatchException> { read(String(body(crc)).replace(crc, "AAAAAA==").toByteArray()) }
    }

    private var chainSignature: String? = null

    @Test
    fun `an unsigned body has its trailing checksum and its length verified`() {
        val data = "hello world".toByteArray()
        val crc = Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(4).putInt(CRC32().apply { update(data) }.value.toInt()).array())
        fun body(checksum: String) = "b\r\nhello world\r\n0\r\nx-amz-checksum-crc32:$checksum\r\n\r\n".toByteArray()

        AwsChunkedInputStream(body(crc).inputStream(), trailer = "x-amz-checksum-crc32", decodedLength = 11).readBytes() shouldBe data
        shouldThrow<ChecksumMismatchException> { AwsChunkedInputStream(body("AAAAAA==").inputStream(), trailer = "x-amz-checksum-crc32").readBytes() }
        shouldThrow<ChecksumMismatchException> { AwsChunkedInputStream(body(crc).inputStream(), trailer = "x-amz-checksum-crc32", decodedLength = 12).readBytes() }
        shouldThrow<AwsChunkedFormatException> { AwsChunkedInputStream(body(crc).inputStream(), trailer = "x-amz-checksum-md5") }
    }

    @Test
    fun `the checksums of S3 have their check values`() {
        fun value(header: String) =
            TrailerChecksum.of(header)!!.apply { "123456789".toByteArray().let { update(it, 0, it.size) } }.value().joinToString("") { "%02x".format(it) }
        value("x-amz-checksum-crc32") shouldBe "cbf43926"
        value("x-amz-checksum-crc32c") shouldBe "e3069283"
        value("x-amz-checksum-crc64nvme") shouldBe "ae8b14860a799888"
        value("x-amz-checksum-sha1") shouldBe "f7c3bc1d808e04732adf679965ccc34ca7ae3441"
    }
}
