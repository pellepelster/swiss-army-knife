package io.pelle.sak.s3.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class Sha256VerifyingInputStreamTest {
    private val content = "payload under test".toByteArray()
    private val contentSha256 = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    @Test
    fun `passes the content through when the hash matches`() {
        Sha256VerifyingInputStream(content.inputStream(), contentSha256).use { it.readBytes() } shouldBe content
        Sha256VerifyingInputStream(content.inputStream(), contentSha256.uppercase()).use { it.readBytes() } shouldBe content
    }

    @Test
    fun `fails at the end of the content when the hash does not match`() {
        val stream = Sha256VerifyingInputStream(content.inputStream(), "0".repeat(64))

        stream.read() shouldBe content[0].toInt()
        shouldThrow<PayloadHashMismatchException> { stream.readBytes() }
    }

    @Test
    fun `the empty body hash verifies an empty stream`() {
        Sha256VerifyingInputStream(ByteArray(0).inputStream(), SigV4Verifier.EMPTY_BODY_SHA256).use { it.readBytes() } shouldBe ByteArray(0)
        shouldThrow<PayloadHashMismatchException> {
            Sha256VerifyingInputStream(content.inputStream(), SigV4Verifier.EMPTY_BODY_SHA256).use { it.readBytes() }
        }
    }
}
