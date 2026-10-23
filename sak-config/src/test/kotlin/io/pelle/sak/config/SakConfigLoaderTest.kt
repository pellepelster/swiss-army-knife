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
        config.redirects shouldBe mapOf("www.example.org" to "example.org")
        config.s3.accessKeyId shouldBe "sak"
        config.s3.secretAccessKey shouldBe "changeme"
        config.s3.buckets.keys shouldBe setOf("backups", "media")
        config.s3.buckets.getValue("backups").domains shouldBe emptyList()
        config.s3.buckets.getValue("media").domains shouldBe listOf("media.example.org")
        config.s3DataDir shouldBe Path.of("/somewhere/s3")
    }

    @Test
    fun `s3 data dir defaults to s3 below the data dir`() {
        load("data_dir: /data\n$ROOT_DOMAIN$S3").s3DataDir shouldBe Path.of("/data/s3")
    }

    @Test
    fun `buckets default to none`() {
        load("data_dir: /data\n$ROOT_DOMAIN$S3").s3.buckets shouldBe emptyMap()
    }

    @Test
    fun `resolves the s3 credentials from environment variables`() {
        System.setProperty("SAK_TEST_ACCESS_KEY_ID", "env-key")
        System.setProperty("SAK_TEST_SECRET_ACCESS_KEY", "env-secret")
        try {
            val config = load("data_dir: /data\n${ROOT_DOMAIN}s3:\n  access_key_id: \$SAK_TEST_ACCESS_KEY_ID\n  secret_access_key: \$SAK_TEST_SECRET_ACCESS_KEY\n")

            config.s3.accessKeyId shouldBe "env-key"
            config.s3.secretAccessKey shouldBe "env-secret"
        } finally {
            System.clearProperty("SAK_TEST_ACCESS_KEY_ID")
            System.clearProperty("SAK_TEST_SECRET_ACCESS_KEY")
        }
    }

    @Test
    fun `fails when a referenced environment variable is missing`() {
        shouldThrow<ApplicationConfigurationException> {
            load("data_dir: /data\n${ROOT_DOMAIN}s3:\n  access_key_id: \$SAK_TEST_MISSING\n  secret_access_key: secret\n")
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
        config.s3.accessKeyId shouldBe "key"
    }

    @Test
    fun `caddy admin url defaults to localhost 2019`() {
        load("data_dir: /data\n$ROOT_DOMAIN$S3").caddyAdminUrl shouldBe SakConfig.DEFAULT_CADDY_ADMIN_URL
    }

    @Test
    fun `redirects default to none`() {
        load("data_dir: /data\n$ROOT_DOMAIN$S3").redirects shouldBe emptyMap()
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
    fun `fails when s3 is missing`() {
        shouldThrow<RuntimeException> { load("data_dir: /data\n$ROOT_DOMAIN") }
    }

    @Test
    fun `fails when s3 credentials are incomplete`() {
        shouldThrow<RuntimeException> { load("data_dir: /data\n${ROOT_DOMAIN}s3:\n  access_key_id: key\n") }
    }

    companion object {
        private const val ROOT_DOMAIN = "root_domain: example.org\n"

        private const val S3 = "s3:\n  access_key_id: key\n  secret_access_key: secret\n"
    }
}
