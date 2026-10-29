package io.pelle.sak.backend

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldNotContain
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.ConfigLoader
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.pelle.sak.config.DnsMode
import io.pelle.sak.config.SakConfig
import io.pelle.sak.config.SakConfigLoader
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.backend.hosting.CreateBucketResult
import io.pelle.sak.backend.hosting.S3BucketService
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
        val database = SakDatabase.open(databaseFile(sakConfig()))
        val buckets = S3BucketService(database)
        listOf("bucket1", "bucket2").associateWith {
            (buckets.create(it) as CreateBucketResult.Created).bucket.let { bucket ->
                AwsBasicCredentials.create(bucket.accessKeyId, buckets.keyPair(bucket.name)!!.second.value)
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

    // the template of the controller role rendered as ansible renders it, with the inventory sakctl
    // writes: its variables, its filters and its one condition; anything else left is an error
    private fun renderTemplate(smtp: Boolean): Path {
        val template = Files.readString(Path.of("../ansible/roles/controller/templates/controller-config.yaml.j2"))
        val block = Regex("""\{% if sak_smtp_enabled \| bool %}\n(.*?)\{% endif %}\n""", RegexOption.DOT_MATCHES_ALL)
        val rendered =
            template
                .replace(block) { if (smtp) it.groupValues[1] else "" }
                .replace(Regex("""\{% if sak_acme_ca [^%]*%}\n(.*?)\{% endif %}\n""", RegexOption.DOT_MATCHES_ALL)) { it.groupValues[1] }
                .replace("{{ sak_acme_ca }}", "https://acme-staging-v02.api.letsencrypt.org/directory")
                .replace("{{ sak_name }}", "alpha")
                .replace("{{ sak_dns_mode }}", "domain")
                .replace("{{ sak_root_domain }}", "pelle.io")
                .replace("{{ sak_api_public_key | to_json }}", "\"zQsVaUWSE58kHpvnSflQK13SMb1VdS23SZ9PAf7DvGQ=\"")
                .replace("{{ sak_admin_email }}", "admin@example.org")
                .replace("{{ sak_smtp_tls | bool | lower }}", "true")
                .replace("{{ sak_smtp_starttls | bool | lower }}", "false")
        rendered shouldNotContain "{{"
        rendered shouldNotContain "{%"
        return tempDir.resolve("controller-config.yaml").also { Files.writeString(it, rendered) }
    }

    // backend.env as sakctl writes it, as the environment the backend reads
    private fun <T> withBackendEnv(
        smtp: Boolean,
        block: () -> T,
    ): T {
        val environment =
            mapOf(
                "SAK_ADMIN_PASSWORD_HASH" to "\$argon2id\$v=19\$m=65536,t=3,p=1\$al/AGZtQJD5CTnS21yvIXg\$L7n0a0d/Gu4awCZm7tuz99SWhVvUedmj0UABpTBu6l4",
                "SAK_IPV4" to "203.0.113.1",
                "SAK_IPV6" to "2001:db8::1",
            ) +
                if (smtp) {
                    mapOf(
                        "SAK_SMTP_HOST" to "smtp.example.org",
                        "SAK_SMTP_PORT" to "465",
                        "SAK_SMTP_USERNAME" to "smtp-user",
                        "SAK_SMTP_PASSWORD" to "smtp-password",
                    )
                } else {
                    emptyMap()
                }
        environment.forEach { (name, value) -> System.setProperty(name, value) }
        try {
            return block()
        } finally {
            environment.keys.forEach { System.clearProperty(it) }
        }
    }

    @Test
    fun `the deployed configuration, the template of the controller role, is valid with a mail server`() {
        val config = withBackendEnv(smtp = true) { SakConfigLoader.load(ConfigLoader.loadAll(BUNDLED_CONFIG, renderTemplate(smtp = true).toString())) }

        config.name shouldBe "alpha"
        config.dnsMode shouldBe DnsMode.DOMAIN
        // the staging ca of the integration tests, caddy takes it as its issuer
        config.acmeCa shouldBe "https://acme-staging-v02.api.letsencrypt.org/directory"
        caddyConfig(config, emptyMap(), emptyMap(), 8080).apps!!.tls!!.automation!!.policies.single().issuers.single().ca shouldBe
            "https://acme-staging-v02.api.letsencrypt.org/directory"
        config.frontendUrl shouldBe "https://sak.pelle.io"
        config.auth!!.admins shouldBe listOf("admin@example.org")
        config.auth!!.adminPassword!! shouldStartWith "\$argon2id\$"
        config.auth!!.smtp shouldBe
            SakConfig.SmtpConfig(
                host = "smtp.example.org",
                port = 465,
                username = "smtp-user",
                password = "smtp-password",
                from = "noreply@pelle.io",
                tls = true,
                starttls = false,
            )
        config.openrouter shouldBe null
        config.addresses shouldBe SakConfig.AddressesConfig(listOf("203.0.113.1"), listOf("2001:db8::1"))
    }

    @Test
    fun `the deployed configuration without a mail server is single user, only the password login`() {
        val config = withBackendEnv(smtp = false) { SakConfigLoader.load(ConfigLoader.loadAll(BUNDLED_CONFIG, renderTemplate(smtp = false).toString())) }

        config.auth!!.smtp shouldBe null
        config.auth!!.admins shouldBe emptyList()
        config.auth!!.adminPassword!! shouldStartWith "\$argon2id\$"
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
                            .putHeader("X-Forwarded-Host", "api.example.org")
                            .build(),
                    ).build(),
            )
        }
    }
}
