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
import io.kotest.matchers.string.shouldNotContain

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
        config.s3DataDir shouldBe Path.of("/something/s3")
        config.secretKeys.keys shouldBe setOf(1, 2)
        config.frontendUrl shouldBe "https://apps.example.org"
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
    fun `tells no secret of the config when it is printed`() {
        val config =
            load(
                """
                data_dir: /data
                root_domain: example.org
                secret_keys:
                  1: the-first-secret-key
                  2: the-second-secret-key
                auth:
                  smtp:
                    host: smtp.example.org
                    username: login
                    password: the-smtp-password
                    from: login@example.org
                openrouter:
                  api_token: the-openrouter-token
                """.trimIndent(),
            )

        // e.g. in a log line or a failing assertion
        listOf(config.toString(), "$config", config.auth.toString(), config.openrouter.toString()).forEach { printed ->
            listOf("the-first-secret-key", "the-second-secret-key", "the-smtp-password", "the-openrouter-token").forEach {
                printed shouldNotContain it
            }
        }
        config.toString() shouldContain "secretKeys=versions [1, 2]"
        config.toString() shouldContain "smtp.example.org"
    }

    @Test
    fun `fails on the allowed_emails the admins were called before`() {
        shouldThrow<RuntimeException> {
            load("data_dir: /data\n${ROOT_DOMAIN}auth:\n  allowed_emails:\n    - admin@example.org\n  smtp:\n    host: localhost\n    from: login@example.org\n")
        }.message shouldContain "allowed_emails"
    }

    @Test
    fun `auth is disabled by default`() {
        load("data_dir: /data\n$ROOT_DOMAIN").auth shouldBe null
    }

    @Test
    fun `smtp defaults to starttls on port 587 and the frontend url can be overridden`() {
        val config =
            load(
                "data_dir: /data\n${ROOT_DOMAIN}auth:\n  frontend_url: http://localhost:4200\n  smtp:\n    host: localhost\n    from: login@example.org\n",
            )

        config.frontendUrl shouldBe "http://localhost:4200"
        config.auth!!.admins shouldBe emptyList()
        config.auth.smtp.port shouldBe 587
        config.auth.smtp.tls shouldBe false
        config.auth.smtp.starttls shouldBe true
    }

    @Test
    fun `s3 data dir is s3 below the data dir`() {
        load("data_dir: /data\n$ROOT_DOMAIN").s3DataDir shouldBe Path.of("/data/s3")
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
        Files.writeString(override, "sak:\n  data_dir: /var/lib/sak\n" + "$ROOT_DOMAIN".prependIndent("  "))

        val config = SakConfigLoader.load(ConfigLoader.load(base.toString()).mergeWith(ConfigLoader.load(override.toString())))

        config.dataDir shouldBe Path.of("/var/lib/sak")
        config.caddyAdminUrl shouldBe "http://localhost:3019"
        config.rootDomain shouldBe "example.org"
    }

    @Test
    fun `reads the addresses of the server as comma separated lists, as terraform writes them`() {
        System.setProperty("SAK_TEST_IPV4", "203.0.113.1, 203.0.113.2")
        System.setProperty("SAK_TEST_IPV6", "2001:db8::1")
        try {
            load("data_dir: /data\n${ROOT_DOMAIN}addresses:\n  ipv4: \$SAK_TEST_IPV4\n  ipv6: \$SAK_TEST_IPV6\n").addresses shouldBe
                SakConfig.AddressesConfig(listOf("203.0.113.1", "203.0.113.2"), listOf("2001:db8::1"))
        } finally {
            System.clearProperty("SAK_TEST_IPV4")
            System.clearProperty("SAK_TEST_IPV6")
        }
    }

    @Test
    fun `has no addresses unless they are given`() {
        load("data_dir: /data\n$ROOT_DOMAIN").addresses shouldBe SakConfig.AddressesConfig()
        load("data_dir: /data\n${ROOT_DOMAIN}addresses:\n  ipv4: ''\n").addresses.ipv4 shouldBe emptyList()
    }

    @Test
    fun `fails on addresses of the other family or host names`() {
        shouldThrow<RuntimeException> { load("data_dir: /data\n${ROOT_DOMAIN}addresses:\n  ipv4: 203.0.113.1, 2001:db8::1\n") }
            .message shouldContain "'2001:db8::1' is no ipv4 address"
        shouldThrow<RuntimeException> { load("data_dir: /data\n${ROOT_DOMAIN}addresses:\n  ipv6: 203.0.113.1\n") }
            .message shouldContain "'203.0.113.1' is no ipv6 address"
        shouldThrow<RuntimeException> { load("data_dir: /data\n${ROOT_DOMAIN}addresses:\n  ipv4: example.org\n") }
            .message shouldContain "'example.org' is no ipv4 address"
    }

    @Test
    fun `checks the domains with public name servers unless others are given`() {
        load("data_dir: /data\n$ROOT_DOMAIN").dns.servers shouldBe listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
        load("data_dir: /data\n${ROOT_DOMAIN}dns:\n  servers:\n    - 192.0.2.53\n    - 2001:db8::53\n").dns.servers shouldBe listOf("192.0.2.53", "2001:db8::53")
        shouldThrow<RuntimeException> { load("data_dir: /data\n${ROOT_DOMAIN}dns:\n  servers:\n    - dns.example.org\n") }
            .message shouldContain "the name server 'dns.example.org' is no ip address"
    }

    @Test
    fun `caddy admin url defaults to localhost 2019`() {
        load("data_dir: /data\n$ROOT_DOMAIN").caddyAdminUrl shouldBe SakConfig.DEFAULT_CADDY_ADMIN_URL
    }

    @Test
    fun `fails on the redirects, they moved to the database`() {
        shouldThrow<RuntimeException> { load("data_dir: /data\n${ROOT_DOMAIN}redirects:\n  example.com: example.org\n") }
    }

    @Test
    fun `fails when data dir is missing`() {
        shouldThrow<RuntimeException> { load("$ROOT_DOMAIN") }
    }

    @Test
    fun `fails when root domain is missing`() {
        shouldThrow<RuntimeException> { load("data_dir: /data\n") }
    }

    @Test
    fun `fails on the s3 buckets, they moved to the database with their domains`() {
        shouldThrow<RuntimeException> { load("data_dir: /data\n${ROOT_DOMAIN}s3:\n  buckets:\n    site: {}\n") }
    }

    companion object {
        private const val ROOT_DOMAIN = "root_domain: example.org\n"

    }
}
