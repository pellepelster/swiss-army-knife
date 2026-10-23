package io.pelle.sak.s3.auth

import io.pelle.sak.s3.S3AccessCallback
import io.pelle.sak.s3.S3AccessResult
import io.pelle.sak.s3.S3Operation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import software.amazon.awssdk.http.ContentStreamProvider
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SigV4VerifierTest {
    private val callback =
        object : S3AccessCallback {
            override fun resolveSecretKey(
                accessKeyId: String,
                bucket: String?,
            ) = if (accessKeyId == ACCESS_KEY_ID) SECRET_KEY else null

            override fun checkAccess(
                accessKeyId: String,
                bucket: String,
                operation: S3Operation,
                key: String?,
            ) = S3AccessResult.GRANTED
        }

    private val verifier = SigV4Verifier(callback, Duration.ofMinutes(15))

    private fun sign(
        method: SdkHttpMethod,
        path: String,
        query: Map<String, String> = emptyMap(),
        payload: ByteArray? = null,
        signPayload: Boolean = false,
        accessKeyId: String = ACCESS_KEY_ID,
        secretKey: String = SECRET_KEY,
    ): SignedRequest {
        val request =
            SdkHttpRequest
                .builder()
                .method(method)
                .protocol("http")
                .host("localhost")
                .port(8080)
                .encodedPath(path)
                .apply { query.forEach { (name, value) -> appendRawQueryParameter(name, value) } }
                .build()

        return AwsV4HttpSigner.create().sign {
            it
                .identity(AwsCredentialsIdentity.create(accessKeyId, secretKey))
                .request(request)
                .payload(payload?.let { bytes -> ContentStreamProvider { bytes.inputStream() } })
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "s3")
                .putProperty(AwsV4HttpSigner.REGION_NAME, "us-east-1")
                .putProperty(AwsV4FamilyHttpSigner.PAYLOAD_SIGNING_ENABLED, signPayload)
                .putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, false)
                .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, false)
        }
    }

    private fun toSigV4Request(signed: SignedRequest): SigV4Request {
        val request = signed.request()
        return SigV4Request(
            method = request.method().name,
            rawUri = request.encodedPath(),
            rawQuery = request.uri.rawQuery,
            headers = request.headers().mapKeys { it.key.lowercase() },
        )
    }

    @Test
    fun `accepts a get request signed by the aws sdk`() {
        val signed = sign(SdkHttpMethod.GET, "/s3/bucket1", query = mapOf("list-type" to "2", "prefix" to "a%20b/c"))

        verifier.verify(toSigV4Request(signed)) shouldBe SigV4Result.Authenticated(ACCESS_KEY_ID)
    }

    @Test
    fun `accepts a valueless query parameter as sent for multipart uploads`() {
        val signed = sign(SdkHttpMethod.POST, "/s3/bucket1/key.bin", query = mapOf("uploads" to ""))

        verifier.verify(toSigV4Request(signed)) shouldBe SigV4Result.Authenticated(ACCESS_KEY_ID)
    }

    @Test
    fun `accepts an encoded object key path`() {
        val signed = sign(SdkHttpMethod.PUT, "/s3/bucket1/css/a%20file%20%28v2%29.css")

        verifier.verify(toSigV4Request(signed)) shouldBe SigV4Result.Authenticated(ACCESS_KEY_ID)
    }

    @Test
    fun `accepts a put request with signed payload hash`() {
        val signed = sign(SdkHttpMethod.PUT, "/s3/bucket1/data.bin", payload = "hello world".toByteArray(), signPayload = true)

        verifier.verify(toSigV4Request(signed)) shouldBe SigV4Result.Authenticated(ACCESS_KEY_ID)
    }

    @Test
    fun `accepts a put request with unsigned payload`() {
        val signed = sign(SdkHttpMethod.PUT, "/s3/bucket1/data.bin", payload = "hello world".toByteArray(), signPayload = false)

        verifier.verify(toSigV4Request(signed)) shouldBe SigV4Result.Authenticated(ACCESS_KEY_ID)
    }

    @Test
    fun `verifies the aws sig v4 test suite get-vanilla vector`() {
        val testSuiteCallback =
            object : S3AccessCallback {
                override fun resolveSecretKey(
                    accessKeyId: String,
                    bucket: String?,
                ) = if (accessKeyId == "AKIDEXAMPLE") "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY" else null

                override fun checkAccess(
                    accessKeyId: String,
                    bucket: String,
                    operation: S3Operation,
                    key: String?,
                ) = S3AccessResult.GRANTED
            }
        val fixedClock = Clock.fixed(Instant.parse("2015-08-30T12:36:00Z"), ZoneOffset.UTC)

        val result =
            SigV4Verifier(testSuiteCallback, Duration.ofMinutes(15), fixedClock).verify(
                SigV4Request(
                    method = "GET",
                    rawUri = "/",
                    rawQuery = null,
                    headers =
                        mapOf(
                            "host" to listOf("example.amazonaws.com"),
                            "x-amz-date" to listOf("20150830T123600Z"),
                            "authorization" to
                                listOf(
                                    "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                                        "SignedHeaders=host;x-amz-date, " +
                                        "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
                                ),
                        ),
                ),
            )

        result shouldBe SigV4Result.Authenticated("AKIDEXAMPLE")
    }

    @Test
    fun `rejects a request signed with a wrong secret`() {
        val signed = sign(SdkHttpMethod.GET, "/s3/bucket1", secretKey = "wrong-secret")

        val result = verifier.verify(toSigV4Request(signed)).shouldBeInstanceOf<SigV4Result.Rejected>()
        result.code shouldBe "SignatureDoesNotMatch"
        result.status shouldBe 403
    }

    @Test
    fun `rejects an unknown access key id`() {
        val signed = sign(SdkHttpMethod.GET, "/s3/bucket1", accessKeyId = "UNKNOWNKEY")

        val result = verifier.verify(toSigV4Request(signed)).shouldBeInstanceOf<SigV4Result.Rejected>()
        result.code shouldBe "InvalidAccessKeyId"
        result.status shouldBe 403
    }

    @Test
    fun `rejects a request outside the allowed clock skew`() {
        val skewedVerifier =
            SigV4Verifier(callback, Duration.ofMinutes(15), Clock.offset(Clock.systemUTC(), Duration.ofHours(2)))
        val signed = sign(SdkHttpMethod.GET, "/s3/bucket1")

        val result = skewedVerifier.verify(toSigV4Request(signed)).shouldBeInstanceOf<SigV4Result.Rejected>()
        result.code shouldBe "RequestTimeTooSkewed"
        result.status shouldBe 403
    }

    @Test
    fun `rejects a request without authorization header`() {
        val result =
            verifier
                .verify(SigV4Request("GET", "/s3/bucket1", null, emptyMap()))
                .shouldBeInstanceOf<SigV4Result.Rejected>()

        result.code shouldBe "AccessDenied"
        result.status shouldBe 403
    }

    @Test
    fun `rejects presigned url requests as not implemented`() {
        val result =
            verifier
                .verify(SigV4Request("GET", "/s3/bucket1/key.txt", "X-Amz-Signature=abcdef&X-Amz-Algorithm=AWS4-HMAC-SHA256", emptyMap()))
                .shouldBeInstanceOf<SigV4Result.Rejected>()

        result.code shouldBe "NotImplemented"
        result.status shouldBe 501
    }

    @Test
    fun `passes the request bucket to the access callback`() {
        val buckets = mutableListOf<String?>()
        val capturingCallback =
            object : S3AccessCallback {
                override fun resolveSecretKey(
                    accessKeyId: String,
                    bucket: String?,
                ): String? {
                    buckets.add(bucket)
                    return SECRET_KEY
                }

                override fun checkAccess(
                    accessKeyId: String,
                    bucket: String,
                    operation: S3Operation,
                    key: String?,
                ) = S3AccessResult.GRANTED
            }
        val capturingVerifier = SigV4Verifier(capturingCallback, Duration.ofMinutes(15))

        capturingVerifier.verify(toSigV4Request(sign(SdkHttpMethod.GET, "/s3/bucket1")).copy(bucket = "bucket1"))
        capturingVerifier.verify(toSigV4Request(sign(SdkHttpMethod.GET, "/s3/bucket1")))

        buckets shouldBe listOf("bucket1", null)
    }

    @Test
    fun `rejects a tampered request`() {
        val signed = sign(SdkHttpMethod.GET, "/s3/bucket1", query = mapOf("prefix" to "original"))
        val tampered = toSigV4Request(signed).copy(rawQuery = "prefix=tampered")

        verifier.verify(tampered).shouldBeInstanceOf<SigV4Result.Rejected>().code shouldBe "SignatureDoesNotMatch"
    }

    companion object {
        private const val ACCESS_KEY_ID = "test-access-key"
        private const val SECRET_KEY = "test-secret-key"
    }
}
