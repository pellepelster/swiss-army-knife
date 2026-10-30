package io.pelle.sak.e2e.util

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import java.io.File
import java.security.SecureRandom
import kotlin.concurrent.thread

// the zones the deployments use, both must exist in the project: one for the domain deployment,
// one for the subdomain deployment
val DOMAIN_ZONE: String = System.getenv("SAK_E2E_DOMAIN_ZONE") ?: "krawallbude.de"
val SUBDOMAIN_ZONE: String = System.getenv("SAK_E2E_SUBDOMAIN_ZONE") ?: "krawallbu.de"

// where the servers of the tests are created; a server type can be sold out in a location for a while
val LOCATION: String = System.getenv("SAK_E2E_LOCATION") ?: "hel1"

// every deployment of the tests gets its certificates from the staging ca of let's encrypt, which
// has none of the limits of the production one
val STAGING_CA = arrayOf("--acme-ca", "staging")

// the deployments of the tests are `test-<id>-…`, everything else in the project stays untouched
const val TEST_PREFIX = "test-"

private val random = SecureRandom()

private fun randomText(
    length: Int,
    alphabet: String,
) = (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

// `test-` and 5 random lower case letters and digits, a name of its own for each run
fun testName(): String = TEST_PREFIX + randomText(5, "abcdefghijklmnopqrstuvwxyz0123456789")

private fun requiredEnv(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name is not set, refer to sak-e2e/README.md")

/**
 * the two deployments every integration test shares, because a server takes minutes and costs
 * money: one single user in domain mode, one multi user in subdomain mode that sends its login
 * links to a mailtrap sandbox. Created once for the whole run, in parallel, with sakctl only;
 * destroyed with sakctl when the run ends, also after a failure. What an earlier run left goes first
 */
class TestEnvironment : AutoCloseable {
    val token = requiredEnv("HCLOUD_TOKEN")
    val project = HetznerProject(token)
    val mailtrap = MailtrapSandbox(requiredEnv("MAILTRAP_API_TOKEN"), requiredEnv("SAK_E2E_SMTP_USERNAME"))
    val smtpHost = requiredEnv("SAK_E2E_SMTP_HOST")
    private val smtpPort = requiredEnv("SAK_E2E_SMTP_PORT")
    // the tag of the images the deployments run, when the run sets one: e.g. <version>-rc, the
    // images of a release before it is released; else the version of sakctl
    private val imageTag = listOfNotNull(System.getenv("SAK_DOCKER_IMAGE_TAG")?.takeIf { it.isNotBlank() }?.let { "SAK_DOCKER_IMAGE_TAG" to it }).toMap()
    private val smtpEnvironment =
        imageTag + mapOf("SAK_SMTP_USERNAME" to requiredEnv("SAK_E2E_SMTP_USERNAME"), "SAK_SMTP_PASSWORD" to requiredEnv("SAK_E2E_SMTP_PASSWORD"))

    private val base = testName()

    private fun password() = randomText(24, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")

    val single: Deployment =
        password().let { password ->
            Deployment("$base-single", UserMode.SINGLE, "domain", DOMAIN_ZONE, password, "", Sakctl(token, password, imageTag, prefix = "[$base-single] "))
        }

    // the admin is a synthetic address, the sandbox receives everything sent to it
    val multi: Deployment =
        password().let { password ->
            Deployment(
                "$base-multi",
                UserMode.MULTI,
                "subdomain",
                SUBDOMAIN_ZONE,
                password,
                "$base@example.org",
                Sakctl(token, password, smtpEnvironment, prefix = "[$base-multi] "),
            )
        }

    val deployments = listOf(single, multi)

    private lateinit var playwright: Playwright
    private lateinit var browser: Browser

    fun deployment(mode: String): Deployment = deployments.single { it.toString() == mode }

    private var started = false

    /** what an earlier run left, the sandbox empty, then both deployments at once; once a run */
    @Synchronized
    fun create() {
        if (started) return
        started = true
        step("removing what an earlier run left of test-* deployments")
        project.deleteResources { it.startsWith(TEST_PREFIX) }
        check(project.resources { it.startsWith(TEST_PREFIX) }.isEmpty()) { "resources of an earlier run are left" }
        val zones = project.zones()
        check(DOMAIN_ZONE in zones && SUBDOMAIN_ZONE in zones) { "the project needs the zones $DOMAIN_ZONE and $SUBDOMAIN_ZONE" }
        step("emptying the mailtrap sandbox")
        mailtrap.clean()
        val users =
            mapOf(
                single to arrayOf("--user-mode", "single"),
                multi to
                    arrayOf(
                        "--user-mode",
                        "multi",
                        "--admin-email",
                        multi.adminEmail,
                        "--smtp-host",
                        smtpHost,
                        "--smtp-port",
                        smtpPort,
                        "--smtp-starttls",
                        "true",
                    ),
            )
        step("creating ${single.name} (single user, domain mode on $DOMAIN_ZONE) and ${multi.name} (multi user, subdomain mode on $SUBDOMAIN_ZONE) in $LOCATION at once, this takes 10 to 15 minutes")
        deployments
            .map { deployment ->
                thread(name = deployment.name) {
                    deployment.created =
                        deployment.sakctl.run(
                            "create",
                            deployment.name,
                            *users.getValue(deployment),
                            "--dns-mode",
                            deployment.dnsMode,
                            "--root-domain",
                            deployment.rootDomain,
                            "--location",
                            LOCATION,
                            *STAGING_CA,
                        )
                    step("sakctl create ended with ${deployment.created}", deployment)
                }
            }.forEach { it.join() }
        for (deployment in deployments.filter { it.created == 0 }) {
            deployment.ipv4 = project.serverIpv4(deployment.name)
            step("${deployment.frontendUrl} answers at ${deployment.ipv4}", deployment)
        }
        // the names of a deployment lead to its server at once, whatever the dns of this machine
        // still remembers of them
        val rules =
            deployments
                .filter { it.created == 0 }
                .joinToString(",") { "MAP *.${it.baseDomain} ${it.ipv4}" }
        step("starting chromium")
        playwright = Playwright.create()
        browser = playwright.chromium().launch(BrowserType.LaunchOptions().setArgs(listOf("--host-resolver-rules=$rules")))
    }

    /** a browser of its own for `deployment`: its frontend, the certificates of the staging ca */
    fun context(deployment: Deployment): BrowserContext {
        deployment.requireCreated()
        return browser.newContext(Browser.NewContextOptions().setBaseURL(deployment.frontendUrl).setIgnoreHTTPSErrors(true))
    }

    /** both deployments destroyed with their data, nothing of the run left, the sandbox empty */
    override fun close() {
        if (::browser.isInitialized) browser.close()
        if (::playwright.isInitialized) playwright.close()
        val failures = mutableListOf<String>()
        for (deployment in deployments) {
            step("destroying the deployment and its data", deployment)
            val exit = deployment.sakctl.run("destroy", deployment.name, "--delete-data")
            if (exit != 0) failures += "sakctl destroy ${deployment.name} ended with $exit"
            File(System.getProperty("user.home"), ".sak/backups/${deployment.name}").deleteRecursively()
        }
        step("checking that nothing of the run is left")
        val left = project.resources { it.startsWith(base) }
        if (left.isNotEmpty()) failures += "resources are left: $left"
        runCatching { mailtrap.clean() }.onFailure { failures += "the sandbox was not emptied: ${it.message}" }
        check(failures.isEmpty()) { failures.joinToString("; ") }
        step("the deployments of the run are gone")
    }
}

/**
 * gives a test the shared deployments (TestEnvironment) as a parameter: created by the first test
 * that asks, kept in the root store of junit, which closes it when the whole run ends
 */
class TestDeployments : ParameterResolver {
    override fun supportsParameter(
        parameter: ParameterContext,
        extension: ExtensionContext,
    ) = parameter.parameter.type == TestEnvironment::class.java

    override fun resolveParameter(
        parameter: ParameterContext,
        extension: ExtensionContext,
    ): TestEnvironment =
        extension.root
            .getStore(ExtensionContext.Namespace.create(TestDeployments::class.java))
            // in the store before anything is created, so the store closes it also after a failure
            .getOrComputeIfAbsent(TestEnvironment::class.java.name, { TestEnvironment() }, TestEnvironment::class.java)
            .also { it.create() }
}
