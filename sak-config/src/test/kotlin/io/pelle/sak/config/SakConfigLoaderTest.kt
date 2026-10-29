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
        // the name and the dns mode of a backend that is no deployment: local, subdomain
        config.frontendUrl shouldBe "https://sak.local.example.org"
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
                auth:
                  admins: [admin@example.org]
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
            listOf("the-smtp-password", "the-openrouter-token").forEach {
                printed shouldNotContain it
            }
        }
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
                "data_dir: /data\n${ROOT_DOMAIN}auth:\n  frontend_url: http://localhost:4200\n  admins: [admin@example.org]\n  smtp:\n    host: localhost\n    from: login@example.org\n",
            )

        config.frontendUrl shouldBe "http://localhost:4200"
        config.auth!!.smtp!!.port shouldBe 587
        config.auth.smtp!!.tls shouldBe false
        config.auth.smtp!!.starttls shouldBe true
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
    fun `reads the addresses of the server as comma separated lists, as the environment file holds them`() {
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
    fun `the name is local unless the provisioning writes the one of the deployment`() {
        load("data_dir: /data\n$ROOT_DOMAIN").name shouldBe "local"
        load("name: alpha-1\ndata_dir: /data\n$ROOT_DOMAIN").name shouldBe "alpha-1"
        listOf("Alpha", "-alpha", "alpha-", "a_b", "\"\"", "a".repeat(64)).forEach { name ->
            shouldThrow<IllegalArgumentException> { load("name: $name\ndata_dir: /data\n$ROOT_DOMAIN") }
        }
    }

    @Test
    fun `the dns mode is subdomain unless the provisioning writes the one of the deployment`() {
        load("data_dir: /data\n$ROOT_DOMAIN").dnsMode shouldBe DnsMode.SUBDOMAIN
        load("dns_mode: domain\ndata_dir: /data\n$ROOT_DOMAIN").dnsMode shouldBe DnsMode.DOMAIN
        load("dns_mode: subdomain\ndata_dir: /data\n$ROOT_DOMAIN").dnsMode shouldBe DnsMode.SUBDOMAIN
        shouldThrow<RuntimeException> { load("dns_mode: whole\ndata_dir: /data\n$ROOT_DOMAIN") }
    }

    @Test
    fun `the hosts are below the root domain in domain mode and below the subdomain of the name otherwise`() {
        val domain = load("name: alpha\ndns_mode: domain\ndata_dir: /data\n$ROOT_DOMAIN")
        domain.baseDomain shouldBe "example.org"
        domain.frontendHost shouldBe "sak.example.org"
        domain.apiHost shouldBe "api.example.org"
        domain.frontendUrl shouldBe "https://sak.example.org"

        val subdomain = load("name: alpha\ndns_mode: subdomain\ndata_dir: /data\n$ROOT_DOMAIN")
        subdomain.baseDomain shouldBe "alpha.example.org"
        subdomain.frontendHost shouldBe "sak.alpha.example.org"
        subdomain.apiHost shouldBe "api.alpha.example.org"
        subdomain.frontendUrl shouldBe "https://sak.alpha.example.org"
    }

    @Test
    fun `the admin password comes from the environment, a hash starting with a dollar`() {
        // a value starting with $ in the file is an environment variable to ktor, the hash is
        // therefore always given through the environment (backend.env)
        System.setProperty("SAK_TEST_ADMIN_PASSWORD", "\$argon2id\$v=19\$m=19456,t=2,p=1\$qEK7\$o63n")
        try {
            val auth = load("data_dir: /data\n${ROOT_DOMAIN}auth:\n  admins: [Admin@example.org]\n  admin_password: \$SAK_TEST_ADMIN_PASSWORD\n").auth!!
            auth.adminPassword shouldBe "\$argon2id\$v=19\$m=19456,t=2,p=1\$qEK7\$o63n"
            auth.admins shouldBe listOf("Admin@example.org")
            auth.smtp shouldBe null
            auth.toString() shouldNotContain "o63n"
        } finally {
            System.clearProperty("SAK_TEST_ADMIN_PASSWORD")
        }
    }

    @Test
    fun `auth needs a way to sign in, and the e-mail login needs an admin`() {
        // neither a mail server nor an admin password
        shouldThrow<IllegalArgumentException> { load("data_dir: /data\n${ROOT_DOMAIN}auth:\n  admins: [admin@example.org]\n") }
        // a mail server without an admin to send the first link to
        shouldThrow<IllegalArgumentException> {
            load("data_dir: /data\n${ROOT_DOMAIN}auth:\n  smtp:\n    host: localhost\n    from: login@example.org\n")
        }
        System.setProperty("SAK_TEST_ADMIN_PASSWORD", "\$argon2id\$v=19\$m=19456,t=2,p=1\$qEK7\$o63n")
        try {
            // single user: the password alone, the admin has no address
            load("data_dir: /data\n${ROOT_DOMAIN}auth:\n  admin_password: \$SAK_TEST_ADMIN_PASSWORD\n").auth!!.admins shouldBe emptyList()
        } finally {
            System.clearProperty("SAK_TEST_ADMIN_PASSWORD")
        }
        // no argon2id hash
        System.setProperty("SAK_TEST_ADMIN_PASSWORD", "plain-password")
        try {
            shouldThrow<IllegalArgumentException> {
                load("data_dir: /data\n${ROOT_DOMAIN}auth:\n  admins: [admin@example.org]\n  admin_password: \$SAK_TEST_ADMIN_PASSWORD\n")
            }
        } finally {
            System.clearProperty("SAK_TEST_ADMIN_PASSWORD")
        }
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
