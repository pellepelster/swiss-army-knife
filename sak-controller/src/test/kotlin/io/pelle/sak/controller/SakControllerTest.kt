package io.pelle.sak.controller

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.ConfigLoader
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.pelle.sak.config.SakConfig
import io.pelle.sak.config.SakConfigLoader
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
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class SakControllerTest {
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
              s3:
                access_key_id: smoke-key
                secret_access_key: smoke-secret
                buckets:
                  bucket1: {}
            """.trimIndent(),
        )
        return ConfigLoader.loadAll(BUNDLED_CONFIG, configFile.toString())
    }

    private fun sakConfig(): SakConfig = SakConfigLoader.load(config())

    private fun withServer(block: (port: Int) -> Unit) {
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
    ) = S3Client
        .builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .forcePathStyle(true)
        .httpClient(httpClient)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("smoke-key", "smoke-secret")))
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
        config.property("ktor.application.modules").getList() shouldBe listOf("io.pelle.sak.controller.SakControllerKt.module")
        sakConfig().dataDir shouldBe dataDir
    }

    @Test
    fun `the deployed configuration in config is valid`() {
        System.setProperty("SAK_S3_ACCESS_KEY_ID", "key")
        System.setProperty("SAK_S3_SECRET_ACCESS_KEY", "secret")
        try {
            val config = SakConfigLoader.load(ConfigLoader.loadAll(BUNDLED_CONFIG, "../config/controller-config.yaml"))

            config.s3.accessKeyId shouldBe "key"
            config.s3.secretAccessKey shouldBe "secret"
        } finally {
            System.clearProperty("SAK_S3_ACCESS_KEY_ID")
            System.clearProperty("SAK_S3_SECRET_ACCESS_KEY")
        }
    }

    @Test
    fun `serves the s3 api at s3 with the configured credentials and data dir`() {
        withServer { port -> s3Client("http://localhost:$port/s3").putAndGet() }
    }

    @Test
    fun `rejects buckets that are not configured`() {
        withServer { port ->
            shouldThrow<NoSuchBucketException> {
                s3Client("http://localhost:$port/s3").putObject({ it.bucket("bucket2").key("hello.txt") }, RequestBody.fromString("hello"))
            }
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
