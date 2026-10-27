package io.pelle.sak.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.server.config.ApplicationConfigurationException
import io.ktor.server.config.ConfigLoader
import io.ktor.server.config.mergeWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SakConfigLoaderTest {
    @TempDir
    lateinit var tempDir: Path

    private fun load(yaml: String): SakConfig {
        val file = tempDir.resolve("application.yaml")
        Files.writeString(file, "sak:\n" + yaml.prependIndent("  "))
        return SakConfigLoader.load(ConfigLoader.load(file.toString()))
    }

    @Test
    fun `loads config from classpath resource`() {
        val config = SakConfigLoader.load(ConfigLoader.load("sak.yaml"))

        config.dataDir shouldBe Path.of("/something")
        config.rootDomain shouldBe "example.org"
        config.caddyAdminUrl shouldBe "http://localhost:3019"
        config.s3.buckets.keys shouldBe setOf("backups", "media")
        config.s3.buckets.getValue("backups").domains shouldBe emptyList()
        config.s3.buckets.getValue("media").domains shouldBe listOf("media.example.org")
        config.s3DataDir shouldBe Path.of("/something/s3")
        config.authDataDir shouldBe Path.of("/something/auth")
        config.consoleUrl shouldBe "https://console.example.org"
        config.auth shouldBe
            SakConfig.AuthConfig(
                admins = listOf("Admin@example.org"),
                smtp =
                    SakConfig.SmtpConfig(
                        host = "smtp.example.org",
                        port = 465,
                        username = "login",
                        password = "changeme",
                        from = "login@example.org",
                        tls = true,
                    ),
            )
    }

    @Test
    fun `fails on the allowed_emails the admins were called before`() {
        shouldThrow<RuntimeException> {
            load("data_dir: /data\n$ROOT_DOMAIN${S3}auth:\n  allowed_emails:\n    - admin@example.org\n  smtp:\n    host: localhost\n    from: login@example.org\n")
        }.message shouldContain "allowed_emails"
    }

    @Test
    fun `auth is disabled by default`() {
        load("data_dir: /data\n$ROOT_DOMAIN$S3").auth shouldBe null
    }

    @Test
    fun `smtp defaults to starttls on port 587 and the console url can be overridden`() {
        val config =
            load(
                "data_dir: /data\n$ROOT_DOMAIN${S3}auth:\n  console_url: http://localhost:4200\n  smtp:\n    host: localhost\n    from: login@example.org\n",
            )

        config.consoleUrl shouldBe "http://localhost:4200"
        config.auth!!.admins shouldBe emptyList()
        config.auth.smtp.port shouldBe 587
        config.auth.smtp.tls shouldBe false
        config.auth.smtp.starttls shouldBe true
    }

    @Test
    fun `s3 data dir is s3 below the data dir`() {
        load("data_dir: /data\n$ROOT_DOMAIN$S3").s3DataDir shouldBe Path.of("/data/s3")
    }

    @Test
    fun `buckets default to none`() {
        load("data_dir: /data\n$ROOT_DOMAIN$S3").s3.buckets shouldBe emptyMap()
    }

    @Test
    fun `resolves values from environment variables`() {
        System.setProperty("SAK_TEST_ROOT_DOMAIN", "env.example.org")
        try {
            load("data_dir: /data\nroot_domain: \$SAK_TEST_ROOT_DOMAIN\n").rootDomain shouldBe "env.example.org"
        } finally {
            System.clearProperty("SAK_TEST_ROOT_DOMAIN")
        }
    }

    @Test
    fun `fails when a referenced environment variable is missing`() {
        shouldThrow<ApplicationConfigurationException> {
            load("data_dir: /data\nroot_domain: \$SAK_TEST_MISSING\n")
        }.message shouldContain "SAK_TEST_MISSING"
    }

    @Test
    fun `later configuration files override earlier ones`() {
        val base = tempDir.resolve("base.yaml")
        Files.writeString(base, "sak:\n  data_dir: /data\n  caddy_admin_url: http://localhost:3019\n")
        val override = tempDir.resolve("override.yaml")
        Files.writeString(override, "sak:\n  data_dir: /var/lib/sak\n" + "$ROOT_DOMAIN$S3".prependIndent("  "))

        val config = SakConfigLoader.load(ConfigLoader.load(base.toString()).mergeWith(ConfigLoader.load(override.toString())))

        config.dataDir shouldBe Path.of("/var/lib/sak")
        config.caddyAdminUrl shouldBe "http://localhost:3019"
        config.rootDomain shouldBe "example.org"
    }

    @Test
    fun `caddy admin url defaults to localhost 2019`() {
        load("data_dir: /data\n$ROOT_DOMAIN$S3").caddyAdminUrl shouldBe SakConfig.DEFAULT_CADDY_ADMIN_URL
    }

    @Test
    fun `fails on the redirects, they moved to the database`() {
        shouldThrow<RuntimeException> { load("data_dir: /data\n${ROOT_DOMAIN}redirects:\n  example.com: example.org\n$S3") }
    }

    @Test
    fun `fails when data dir is missing`() {
        shouldThrow<RuntimeException> { load("$ROOT_DOMAIN$S3") }
    }

    @Test
    fun `fails when root domain is missing`() {
        shouldThrow<RuntimeException> { load("data_dir: /data\n$S3") }
    }

    @Test
    fun `s3 defaults to no domains of buckets`() {
        load("data_dir: /data\n$ROOT_DOMAIN").s3.buckets shouldBe emptyMap()
    }

    @Test
    fun `fails on the s3 key pair there was before every bucket got its own`() {
        shouldThrow<RuntimeException> { load("data_dir: /data\n${ROOT_DOMAIN}s3:\n  access_key_id: key\n  secret_access_key: secret\n") }
    }

    companion object {
        private const val ROOT_DOMAIN = "root_domain: example.org\n"

        private const val S3 = "s3:\n  buckets: {}\n"
    }
}
