package io.pelle.sak.backend

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.ConfigLoader
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.pelle.sak.config.SakConfig
import io.pelle.sak.config.SakConfigLoader
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.s3.CreateBucketResult
import io.pelle.sak.model.s3.S3BucketService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class SakBackendTest {
    @TempDir
    lateinit var tempDir: Path

    private val dataDir: Path get() = tempDir.resolve("data")

    private fun config(): ApplicationConfig {
        val configFile = tempDir.resolve("config.yaml")
        Files.writeString(
            configFile,
            """
            ---
            sak:
              data_dir: $dataDir
              root_domain: example.org
            """.trimIndent(),
        )
        return ConfigLoader.loadAll(BUNDLED_CONFIG, configFile.toString())
    }

    private fun sakConfig(): SakConfig = SakConfigLoader.load(config())

    // bucket1 with its key pair, bucket2 with one that may not access bucket1
    private val keys by lazy {
        val buckets = S3BucketService(SakDatabase.open(databaseFile(sakConfig())))
        listOf("bucket1", "bucket2").associateWith {
            (buckets.create(it) as CreateBucketResult.Created).bucket.let { bucket ->
                AwsBasicCredentials.create(bucket.accessKeyId, bucket.secretAccessKey)
            }
        }
    }

    private fun withServer(block: (port: Int) -> Unit) {
        keys
        val config = sakConfig()
        val server = embeddedServer(Netty, port = 0) { sak(config) }.start()
        try {
            block(runBlocking { server.engine.resolvedConnectors().first().port })
        } finally {
            server.stop()
        }
    }

    private fun s3Client(
        endpoint: String,
        httpClient: SdkHttpClient = ApacheHttpClient.create(),
        credentials: AwsBasicCredentials = keys.getValue("bucket1"),
    ) = S3Client
        .builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .forcePathStyle(true)
        .httpClient(httpClient)
        .credentialsProvider(StaticCredentialsProvider.create(credentials))
        .build()

    private fun S3Client.putAndGet() {
        putObject({ it.bucket("bucket1").key("dir/hello.txt") }, RequestBody.fromString("hello sak"))

        String(getObject { it.bucket("bucket1").key("dir/hello.txt") }.readAllBytes()) shouldBe "hello sak"
        Files.readString(dataDir.resolve("s3/buckets/bucket1/data/dir/hello.txt")) shouldBe "hello sak"
    }

    @Test
    fun `deployed configuration overrides the bundled defaults`() {
        val config = config()

        config.property("ktor.deployment.port").getString() shouldBe "8080"
        config.property("ktor.application.modules").getList() shouldBe listOf("io.pelle.sak.backend.SakBackendKt.module")
        sakConfig().dataDir shouldBe dataDir
    }

    @Test
    fun `the deployed configuration in config is valid`() {
        val environment =
            mapOf(
                "SAK_SMTP_HOST" to "smtp.example.org",
                "SAK_SMTP_PORT" to "465",
                "SAK_SMTP_USERNAME" to "smtp-user",
                "SAK_SMTP_PASSWORD" to "smtp-password",
                "SAK_OPENROUTER_API_TOKEN" to "openrouter-token",
            )
        environment.forEach { (name, value) -> System.setProperty(name, value) }
        try {
            val config = SakConfigLoader.load(ConfigLoader.loadAll(BUNDLED_CONFIG, "../config/controller-config.yaml"))

            config.consoleUrl shouldBe "https://console.pelle.io"
            config.auth!!.smtp shouldBe
                SakConfig.SmtpConfig(
                    host = "smtp.example.org",
                    port = 465,
                    username = "smtp-user",
                    password = "smtp-password",
                    from = "noreply@mail.pelle.io",
                    tls = true,
                )
            config.openrouter shouldBe SakConfig.OpenRouterConfig(apiToken = "openrouter-token")
        } finally {
            environment.keys.forEach { System.clearProperty(it) }
        }
    }

    @Test
    fun `serves the s3 api at s3 with the key pair of the bucket and the data dir`() {
        withServer { port -> s3Client("http://localhost:$port/s3").putAndGet() }
    }

    @Test
    fun `the key pair of a bucket may access that bucket only`() {
        withServer { port ->
            val bucket2 = s3Client("http://localhost:$port/s3", credentials = keys.getValue("bucket2"))
            shouldThrow<S3Exception> {
                bucket2.putObject({ it.bucket("bucket1").key("hello.txt") }, RequestBody.fromString("hello"))
            }.statusCode() shouldBe 403
            shouldThrow<S3Exception> {
                s3Client("http://localhost:$port/s3", credentials = AwsBasicCredentials.create("UNKNOWN", "secret"))
                    .putObject({ it.bucket("bucket1").key("hello.txt") }, RequestBody.fromString("hello"))
            }.statusCode() shouldBe 403
        }
    }

    @Test
    fun `accepts s3 requests signed without the s3 prefix when proxied like caddy does`() {
        withServer { port -> s3Client("http://localhost:$port", CaddyLikeProxy(ApacheHttpClient.create())).putAndGet() }
    }

    private class CaddyLikeProxy(
        private val delegate: SdkHttpClient,
    ) : SdkHttpClient by delegate {
        override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest {
            val httpRequest = request.httpRequest()
            return delegate.prepareRequest(
                HttpExecuteRequest
                    .builder()
                    .contentStreamProvider(request.contentStreamProvider().orElse(null))
                    .request(
                        httpRequest
                            .toBuilder()
                            .encodedPath("$S3_PATH_PREFIX${httpRequest.encodedPath()}")
                            .putHeader("X-Forwarded-Host", "s3.example.org")
                            .build(),
                    ).build(),
            )
        }
    }
}
