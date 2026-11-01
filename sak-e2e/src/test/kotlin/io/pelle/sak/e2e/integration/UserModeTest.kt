package io.pelle.sak.e2e.integration

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.shouldBe
import io.pelle.sak.e2e.util.Sakctl
import io.pelle.sak.e2e.util.TestDeployments
import io.pelle.sak.e2e.util.TestEnvironment
import io.pelle.sak.e2e.util.step
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith

// a single user deployment deployed again as a multi user one: the admin signs in with the password
// before and after, and after also with an e-mailed link. The last test of the run, because it
// changes the shared single user deployment for good
@Tag("integration")
@Order(5)
@ExtendWith(TestDeployments::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserModeTest {
    private lateinit var environment: TestEnvironment

    @BeforeAll
    fun deployments(environment: TestEnvironment) {
        this.environment = environment
    }

    @Test
    fun `a single user deployment deployed again as multi user signs in with the password and by e-mail`() {
        val single = environment.single
        single.requireCreated()
        val adminEmail = "${single.name}@example.org"

        step("single user: the admin signs in with the password, there is no e-mail login", single)
        environment.context(single).use { context ->
            val page = context.newPage()
            page.navigate("/login")
            assertThat(page.getByLabel("Admin password")).isVisible()
            assertThat(page.getByLabel("E-mail address")).isHidden()
            single.signIn(page)
        }

        step("deploying again as multi user, the admin $adminEmail, the mail server of the sandbox", single)
        // the user and the password at the mail server, never arguments
        val sakctl = Sakctl(environment.token, single.password, environment.smtpEnvironment, prefix = "[${single.name}] ")
        sakctl.run(
            "deploy",
            single.name,
            "--user-mode",
            "multi",
            "--admin-email",
            adminEmail,
            "--smtp-host",
            environment.smtpHost,
            "--smtp-port",
            environment.smtpPort,
            "--smtp-starttls",
            "true",
            "--yes",
        ) shouldBe 0

        step("multi user: the admin still signs in with the password", single)
        environment.context(single).use { context ->
            single.signIn(context.newPage())
        }

        step("multi user: the admin signs in with a link sent to $adminEmail", single)
        environment.context(single).use { context ->
            val page = context.newPage()
            page.navigate("/")
            assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Sign in"))).isVisible()
            val known = environment.mailtrap.mailIds(adminEmail)
            page.getByLabel("E-mail address").fill(adminEmail)
            page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Send login link")).click()
            assertThat(page.getByTestId("login-sent")).containsText(adminEmail)
            page.navigate(environment.mailtrap.loginLink(adminEmail, known))
            assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Apps"))).isVisible()
        }
    }
}
