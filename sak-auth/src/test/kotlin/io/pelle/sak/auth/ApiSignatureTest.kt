package io.pelle.sak.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ApiSignatureTest {
    // the time of the vector, as the clock of the backend
    private fun signature(at: Long = TIMESTAMP) = ApiSignature(PUBLIC_KEY, "api.example.org", "/api", Clock.fixed(Instant.ofEpochSecond(at), ZoneOffset.UTC))

    private fun header(
        timestamp: Long = TIMESTAMP,
        nonce: String = NONCE,
        sig: String = SIGNATURE,
    ) = "SAK-Ed25519 ts=$timestamp,nonce=$nonce,sig=$sig"

    @Test
    fun `takes what sakctl signs`() {
        ApiSignature.signedText("GET", "api.example.org", "/v1/config", TIMESTAMP, NONCE) shouldBe
            "sak-api-v1\nGET\napi.example.org\n/v1/config\n1767225600\n00112233445566778899aabbccddeeff\n" +
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        signature().refusal("GET", "/api/v1/config", header(), hasBody = false) shouldBe null
        // a minute away either way
        signature(TIMESTAMP + 60).refusal("GET", "/api/v1/config", header(), hasBody = false) shouldBe null
        signature(TIMESTAMP - 60).refusal("GET", "/api/v1/config", header(), hasBody = false) shouldBe null
    }

    @Test
    fun `takes a signature once`() {
        val signature = signature()
        signature.refusal("GET", "/api/v1/config", header(), hasBody = false) shouldBe null
        signature.refusal("GET", "/api/v1/config", header(), hasBody = false)!! shouldContain "seen before"
    }

    @Test
    fun `refuses a request signed for something else`() {
        val signature = signature()
        // another path, method or query; the host is the backend's own, not of the request
        signature.refusal("GET", "/api/v1/notes", header(), hasBody = false)!! shouldContain "does not hold"
        signature.refusal("DELETE", "/api/v1/config", header(), hasBody = false)!! shouldContain "does not hold"
        signature.refusal("GET", "/api/v1/config?x=1", header(), hasBody = false)!! shouldContain "does not hold"
        ApiSignature(PUBLIC_KEY, "api.other.org", "/api", Clock.fixed(Instant.ofEpochSecond(TIMESTAMP), ZoneOffset.UTC))
            .refusal("GET", "/api/v1/config", header(), hasBody = false)!! shouldContain "does not hold"
        // another time or nonce than signed
        signature.refusal("GET", "/api/v1/config", header(timestamp = TIMESTAMP + 1), hasBody = false)!! shouldContain "does not hold"
        signature.refusal("GET", "/api/v1/config", header(nonce = "f".repeat(32)), hasBody = false)!! shouldContain "does not hold"
        // another key
        ApiSignature("A".repeat(43) + "=", "api.example.org", "/api", Clock.fixed(Instant.ofEpochSecond(TIMESTAMP), ZoneOffset.UTC))
            .refusal("GET", "/api/v1/config", header(), hasBody = false) shouldBe "the signature does not hold"
    }

    @Test
    fun `refuses an old request, a body, another path and a header that is none`() {
        signature(TIMESTAMP + 121).refusal("GET", "/api/v1/config", header(), hasBody = false)!! shouldContain "120 seconds"
        signature(TIMESTAMP - 121).refusal("GET", "/api/v1/config", header(), hasBody = false)!! shouldContain "120 seconds"
        signature().refusal("GET", "/api/v1/config", header(), hasBody = true) shouldBe "a signed request carries no body"
        signature().refusal("GET", "/v1/config", header(), hasBody = false)!! shouldContain "not below /api"
        listOf("SAK-Ed25519 ", "SAK-Ed25519 ts=1,nonce=$NONCE", header(nonce = "xyz"), header(sig = "no base64!")).forEach {
            signature().refusal("GET", "/api/v1/config", it, hasBody = false) shouldBe
                if (it == header(nonce = "xyz")) "the nonce is no 32 hex characters" else "no ts, nonce or sig"
        }
    }

    @Test
    fun `logs a client by its network only`() {
        maskedAddress("203.0.113.7") shouldBe "203.0.113.0"
        maskedAddress("2001:db8:1:2:3:4:5:6") shouldBe "2001:db8:1::"
    }

    companion object {
        // the vectors of sakctl: API_PUBLIC_KEY_VECTOR in sak-cli/src-tauri/src/core/keys.rs and
        // SIGNATURE_VECTOR in sak-cli/src-tauri/src/core/config.rs, the key of `alpha` with
        // `a password of sixteen`
        const val PUBLIC_KEY = "zQsVaUWSE58kHpvnSflQK13SMb1VdS23SZ9PAf7DvGQ="
        const val TIMESTAMP = 1_767_225_600L
        const val NONCE = "00112233445566778899aabbccddeeff"
        const val SIGNATURE = "KUot+EGbCGfsihgrwiN9+XeBDYbp37EILgeTXouu4c2N8zpExVRCLfivPOmrLm+Ty97/KGlKzO/99SqWuJbjCA=="
    }
}
