package io.pelle.sak.e2e.integration

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.security.SecureRandom

// the exit codes of sakctl (sak-cli/src-tauri/src/core/error.rs)
private const val EXIT_OK = 0
private const val EXIT_DOMAIN_TAKEN = 3
private const val EXIT_MIXED_DNS_MODES = 4
private const val EXIT_NOT_OWNED = 5

// the zones the deployments use, both must exist in the project: one for a domain deployment,
// one for a subdomain deployment
private val DOMAIN_ZONE = System.getenv("SAK_E2E_DOMAIN_ZONE") ?: "krawallbude.de"
private val SUBDOMAIN_ZONE = System.getenv("SAK_E2E_SUBDOMAIN_ZONE") ?: "krawallbu.de"

// the users of a test deployment: single user, or multi user with a mail server that is never
// asked, nobody signs in by e-mail
private val SINGLE_USER = arrayOf("--user-mode", "single")
private const val FAKE_SMTP_HOST = "smtp.example.org"
private val MULTI_USER = arrayOf("--user-mode", "multi", "--admin-email", "e2e@example.org", "--smtp-host", FAKE_SMTP_HOST)

// the deployments of the tests are `test-<id>`, everything else in the project stays untouched
private const val TEST_PREFIX = "test-"

private val random = SecureRandom()

// `test-` and 5 random lower case letters and digits, a name of its own for each run
private fun testName(): String = TEST_PREFIX + (1..5).map { "abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)] }.joinToString("")

// the command line of sakctl against the Hetzner Cloud project of HCLOUD_TOKEN: real servers
// are created, provisioned and destroyed, which takes long and costs money
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SakctlTest {
    private val token = System.getenv("HCLOUD_TOKEN") ?: error("HCLOUD_TOKEN is not set")
    private val project = HetznerProject(token)
    private val password = (1..24).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[random.nextInt(62)] }.joinToString("")

    // the password goes in SAK_PASSWORD, sakctl takes it from there instead of asking
    private val sakctl = Sakctl(token, password)

    // what an earlier run left behind
    @BeforeAll
    fun removeTestResources() {
        project.deleteResources { it.startsWith(TEST_PREFIX) }
        project.resources { it.startsWith(TEST_PREFIX) }.shouldBeEmpty()
        val zones = project.zones()
        zones shouldContain DOMAIN_ZONE
        zones shouldContain SUBDOMAIN_ZONE
    }

    private fun create(
        name: String,
        dnsMode: String,
        rootDomain: String,
        vararg users: String = SINGLE_USER,
    ): Int = sakctl.run("create", name, *users, "--dns-mode", dnsMode, "--root-domain", rootDomain)

    // `config show` once the backend answers, it starts after the provisioning: the lines it printed
    private fun configShow(name: String): List<String> {
        repeat(30) {
            val (exit, lines) = sakctl.output("config", "show", name)
            if (exit == EXIT_OK) return lines
            Thread.sleep(10_000)
        }
        error("config show of $name did not succeed within 5 minutes")
    }

    private fun destroy(
        name: String,
        vararg options: String,
    ): Int = sakctl.run("destroy", name, *options)

    @Test
    fun `a root domain has one domain deployment or only subdomain deployments`() {
        val (domain, subdomain, refused) = List(3) { testName() }
        try {
            // a deployment that is not there is destroyed already
            destroy(domain) shouldBe EXIT_OK

            create(domain, "domain", DOMAIN_ZONE, *MULTI_USER) shouldBe EXIT_OK
            // the admin api token sakctl derives is the one whose hash the provisioning wrote: the
            // backend answers its running configuration, without a secret in it
            configShow(domain).let { lines ->
                lines.any { it.contains("smtp.host $FAKE_SMTP_HOST") } shouldBe true
                lines.none { it.contains(password) || it.contains("sak_") } shouldBe true
            }
            // a deploy without --dns-mode and --root-domain keeps both: the records of the domain mode
            // stay, nothing switches it to a subdomain deployment
            // a deploy runs the whole playbook again, task by task
            sakctl.output("deploy", domain, *MULTI_USER).let { (exit, lines) ->
                exit shouldBe EXIT_OK
                lines.any { it.contains("[ok] ") || it.contains("[changed] ") } shouldBe true
            }
            project
                .resources { it == domain }
                .filter { it.kind == "dns record" }
                .map { it.name }
                .shouldContainAll("@.$DOMAIN_ZONE/A", "*.$DOMAIN_ZONE/A", "@.$DOMAIN_ZONE/AAAA", "*.$DOMAIN_ZONE/AAAA")
            create(subdomain, "subdomain", SUBDOMAIN_ZONE) shouldBe EXIT_OK

            // the domain deployment manages the whole root domain: neither a second domain
            // deployment nor a subdomain deployment can use it, each with its own exit code
            create(refused, "domain", DOMAIN_ZONE) shouldBe EXIT_DOMAIN_TAKEN
            create(refused, "subdomain", DOMAIN_ZONE) shouldBe EXIT_MIXED_DNS_MODES
        } finally {
            for (name in listOf(domain, subdomain, refused)) {
                destroy(name, "--delete-data") shouldBe EXIT_OK
            }
        }
        val names = setOf(domain, subdomain, refused)
        project.resources { it in names }.shouldBeEmpty()
    }

    @Test
    fun `a resource of someone else with the name of a deployment is neither removed nor taken over`() {
        val name = testName()
        // the cloud name the ssh key of the deployment would have
        val keyName = "sak-$name"
        project.createForeignSshKey(keyName)
        try {
            // a destroy finds the key by its name, but it has neither the name label nor the password
            // hash of the deployment
            destroy(name, "--delete-data") shouldBe EXIT_NOT_OWNED
            project.sshKeyExists(keyName) shouldBe true

            // a create stops at the plan, before anything is created
            create(name, "subdomain", SUBDOMAIN_ZONE) shouldBe EXIT_NOT_OWNED
            project.sshKeyExists(keyName) shouldBe true
            project.resources { it == name }.shouldBeEmpty()
        } finally {
            project.deleteSshKey(keyName)
        }
        project.sshKeyExists(name) shouldBe false
    }
}
