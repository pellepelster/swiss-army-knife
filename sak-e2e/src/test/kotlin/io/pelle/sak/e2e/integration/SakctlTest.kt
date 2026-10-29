package io.pelle.sak.e2e.integration

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.security.SecureRandom
import java.util.UUID

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

// every deployment of the tests gets its certificates from the staging ca of let's encrypt, which
// has none of the limits of the production one; a deploy and a destroy keep it from the labels,
// sakctl trusts its roots when it talks to the deployment
private val STAGING_CA = arrayOf("--acme-ca", "staging")

// where the servers of the tests are created; a server type can be sold out in a location for a while
private val LOCATION = System.getenv("SAK_E2E_LOCATION") ?: "hel1"

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
    ): Int =
        sakctl.run("create", name, *users, "--dns-mode", dnsMode, "--root-domain", rootDomain, "--location", LOCATION, *STAGING_CA)

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

    // the api of the backend on the server, through `sakctl ssh`: past the dns and the
    // certificates of the staging ca. The admin signs in with the password, which goes on the
    // standard input of sakctl, never into an argument; each line `NOTE <text>` is the text of a note
    private fun api(
        name: String,
        request: String,
    ): List<String> {
        val script =
            """
            set -eu
            api=http://127.0.0.1:8080/api/v1
            token=${'$'}(curl -fsS -X POST -H 'Content-Type: application/json' --data-binary @- ${'$'}api/auth/login/password \
              | python3 -c 'import json, sys; print(json.load(sys.stdin)["sessionToken"])')
            $request
            curl -fsS -H "Authorization: Bearer ${'$'}token" "${'$'}api/notes?limit=100" \
              | python3 -c 'import json, sys; [print("NOTE " + note["text"]) for note in json.load(sys.stdin)["items"]]'
            """.trimIndent()
        val (exit, lines) = sakctl.output("ssh", name, "--", "bash -c ${shellQuoted(script)}", input = """{"password": "$password"}""")
        exit shouldBe EXIT_OK
        return lines.filter { it.startsWith("NOTE ") }.map { it.removePrefix("NOTE ") }
    }

    private fun shellQuoted(text: String) = "'" + text.replace("'", "'\\''") + "'"

    // the texts of the notes of the admin
    private fun notes(name: String): List<String> = api(name, "")

    private fun addNote(
        name: String,
        text: String,
    ) {
        api(name, """curl -fsS -o /dev/null -X POST -H "Authorization: Bearer ${'$'}token" -H 'Content-Type: application/json' -d '{"text": "$text"}' ${'$'}api/notes""")
            .shouldContain(text)
    }

    // where `sakctl backup` writes the backups of a deployment
    private fun backups(name: String) = File(System.getProperty("user.home"), ".sak/backups/$name")

    @Test
    fun `a root domain has one domain deployment or only subdomain deployments`() {
        val (domain, subdomain, refused) = List(3) { testName() }
        try {
            // a deployment that is not there is destroyed already
            destroy(domain) shouldBe EXIT_OK

            // a create waits until the deployment answers at its public endpoint
            create(domain, "domain", DOMAIN_ZONE) shouldBe EXIT_OK
            // a deploy without --dns-mode, --root-domain and --user-mode keeps all three from the
            // labels: the records of the domain mode stay, nothing switches it to a subdomain
            // deployment; it runs the whole playbook again, task by task
            sakctl.output("deploy", domain).let { (exit, lines) ->
                exit shouldBe EXIT_OK
                lines.any { it.contains("[ok] ") || it.contains("[changed] ") } shouldBe true
            }
            project
                .resources { it == domain }
                .filter { it.kind == "dns record" }
                .map { it.name }
                .shouldContainAll("@.$DOMAIN_ZONE/A", "*.$DOMAIN_ZONE/A", "@.$DOMAIN_ZONE/AAAA", "*.$DOMAIN_ZONE/AAAA")
            // multi user below a name of its own for each run
            create(subdomain, "subdomain", SUBDOMAIN_ZONE, *MULTI_USER) shouldBe EXIT_OK
            // the api key sakctl derives is the one whose public half the provisioning wrote: the
            // backend answers a signed request with its running configuration, without a secret in it
            configShow(subdomain).let { lines ->
                lines.any { it.contains("smtp.host $FAKE_SMTP_HOST") } shouldBe true
                lines.none { it.contains(password) || it.contains("sak_") } shouldBe true
            }

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
    fun `a backup brings the data back into a deployment created anew`() {
        val name = testName()
        val text = UUID.randomUUID().toString()
        try {
            create(name, "subdomain", SUBDOMAIN_ZONE) shouldBe EXIT_OK
            notes(name).shouldBeEmpty()
            addNote(name, text)

            sakctl.run("backup", name) shouldBe EXIT_OK
            val backup = backups(name).listFiles().orEmpty().single()
            backup.name shouldMatch Regex("${Regex.escape(name)}-\\d{8}T\\d{6}Z\\.age\\.sak")
            // encrypted on the server: the text of the note is nowhere in the file
            String(backup.readBytes(), Charsets.ISO_8859_1).contains(text) shouldBe false

            destroy(name, "--delete-data") shouldBe EXIT_OK
            project.resources { it == name }.shouldBeEmpty()

            create(name, "subdomain", SUBDOMAIN_ZONE) shouldBe EXIT_OK
            notes(name).shouldBeEmpty()

            sakctl.run("restore", name, backup.path, "--yes") shouldBe EXIT_OK
            notes(name) shouldContain text
        } finally {
            destroy(name, "--delete-data") shouldBe EXIT_OK
            backups(name).deleteRecursively()
        }
        project.resources { it == name }.shouldBeEmpty()
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
