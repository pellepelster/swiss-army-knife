package io.pelle.sak.e2e.integration

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.pelle.sak.e2e.util.DOMAIN_ZONE
import io.pelle.sak.e2e.util.Deployment
import io.pelle.sak.e2e.util.LOCATION
import io.pelle.sak.e2e.util.STAGING_CA
import io.pelle.sak.e2e.util.SUBDOMAIN_ZONE
import io.pelle.sak.e2e.util.Sakctl
import io.pelle.sak.e2e.util.TestDeployments
import io.pelle.sak.e2e.util.TestEnvironment
import io.pelle.sak.e2e.util.UserMode
import io.pelle.sak.e2e.util.step
import io.pelle.sak.e2e.util.testName
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith

// the exit codes of sakctl (sak-cli/src-tauri/src/core/error.rs)
private const val EXIT_OK = 0
private const val EXIT_DOMAIN_TAKEN = 3
private const val EXIT_MIXED_DNS_MODES = 4
private const val EXIT_NOT_OWNED = 5

// the rules of the command line of sakctl, on the shared deployments: what it refuses stops before
// anything is created, so none of these tests creates a server of its own
@Tag("integration")
@Order(4)
@ExtendWith(TestDeployments::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SakctlTest {
    private lateinit var environment: TestEnvironment

    @BeforeAll
    fun deployments(environment: TestEnvironment) {
        this.environment = environment
    }

    private fun create(
        sakctl: Sakctl,
        name: String,
        dnsMode: String,
        rootDomain: String,
    ): Int =
        sakctl.run("create", name, "--user-mode", "single", "--dns-mode", dnsMode, "--root-domain", rootDomain, "--location", LOCATION, *STAGING_CA)

    @Test
    fun `a root domain has one domain deployment or only subdomain deployments`() {
        environment.single.requireCreated()
        environment.multi.requireCreated()
        val refused = testName()
        val sakctl = environment.single.sakctl
        step("$DOMAIN_ZONE has the domain deployment ${environment.single.name}: a second one and a subdomain one are refused")
        create(sakctl, refused, "domain", DOMAIN_ZONE) shouldBe EXIT_DOMAIN_TAKEN
        create(sakctl, refused, "subdomain", DOMAIN_ZONE) shouldBe EXIT_MIXED_DNS_MODES
        step("$SUBDOMAIN_ZONE has the subdomain deployment ${environment.multi.name}: a domain one is refused")
        create(sakctl, refused, "domain", SUBDOMAIN_ZONE) shouldBe EXIT_MIXED_DNS_MODES
        environment.project.resources { it == refused }.shouldBeEmpty()
    }

    @Test
    fun `a resource of someone else with the name of a deployment is neither removed nor taken over`() {
        val name = testName()
        // the cloud name the ssh key of the deployment would have
        val keyName = "sak-$name"
        step("creating an ssh key $keyName without labels, as a user of the project would")
        environment.project.createForeignSshKey(keyName)
        try {
            val sakctl = environment.single.sakctl
            // a destroy finds the key by its name, but it has neither the name label nor the
            // password hash of the deployment
            sakctl.run("destroy", name, "--delete-data", "--yes") shouldBe EXIT_NOT_OWNED
            environment.project.sshKeyExists(keyName) shouldBe true
            // a create stops at the plan, before anything is created
            create(sakctl, name, "subdomain", SUBDOMAIN_ZONE) shouldBe EXIT_NOT_OWNED
            environment.project.sshKeyExists(keyName) shouldBe true
            environment.project.resources { it == name }.shouldBeEmpty()
        } finally {
            environment.project.deleteSshKey(keyName)
        }
    }

    // `config show` once it succeeds: sakctl asks the api through the dns of this machine, which can
    // remember an older address of a new name for some minutes. The lines it printed
    private fun configShow(deployment: Deployment): List<String> {
        repeat(30) { attempt ->
            val (exit, lines) = deployment.sakctl.output("config", "show", deployment.name)
            if (exit == EXIT_OK) return lines
            step("config show ended with $exit, trying again in 10 s (${attempt + 1} of 30)", deployment)
            Thread.sleep(10_000)
        }
        error("config show of ${deployment.name} did not succeed within 5 minutes")
    }

    @Test
    fun `config show answers the running configuration without a secret`() {
        for (deployment in environment.deployments) {
            deployment.requireCreated()
            step("reading the running configuration with a signed request", deployment)
            val lines = configShow(deployment)
            lines.none { it.contains(deployment.password) || it.contains("sak_") } shouldBe true
            when (deployment.userMode) {
                UserMode.SINGLE -> lines.any { it.contains("No mail server") } shouldBe true
                UserMode.MULTI -> lines.any { it.contains("smtp.host ${environment.smtpHost}") } shouldBe true
            }
        }
    }

    @Test
    fun `a deploy keeps the settings of the deployment and runs the whole playbook`() {
        val single = environment.single
        single.requireCreated()
        step("deploying again without --dns-mode, --root-domain and --user-mode, the playbook runs", single)
        val (exit, lines) = single.sakctl.output("deploy", single.name)
        exit shouldBe EXIT_OK
        // the command line always forces the playbook, task by task
        lines.any { it.contains("[ok] ") || it.contains("[changed] ") } shouldBe true
        environment.project
            .resources { it == single.name }
            .filter { it.kind == "dns record" }
            .map { it.name }
            .shouldContainAll("@.$DOMAIN_ZONE/A", "*.$DOMAIN_ZONE/A", "@.$DOMAIN_ZONE/AAAA", "*.$DOMAIN_ZONE/AAAA")
    }
}
