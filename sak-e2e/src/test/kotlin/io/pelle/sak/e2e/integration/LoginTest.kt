package io.pelle.sak.e2e.integration

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.string.shouldNotContain
import io.pelle.sak.e2e.util.TestDeployments
import io.pelle.sak.e2e.util.TestEnvironment
import io.pelle.sak.e2e.util.step
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith

private fun Page.heading(name: String) = getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName(name))

private fun Page.button(name: String) = getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(name))

// the sign in of each shared deployment in the browser: the password of the single user one, the
// e-mailed link of the multi user one, whose mail a mailtrap sandbox receives
@Tag("integration")
@Order(1)
@ExtendWith(TestDeployments::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginTest {
    private lateinit var environment: TestEnvironment

    @BeforeAll
    fun deployments(environment: TestEnvironment) {
        this.environment = environment
    }

    @Test
    fun `the admin of the single user deployment signs in with the password`() {
        environment.context(environment.single).use { context ->
            val page = context.newPage()
            page.navigate("/")
            assertThat(page.heading("Sign in")).isVisible()
            step("a wrong password is refused", environment.single)
            page.getByLabel("Admin password").fill("not the password of the deployment")
            page.button("Sign in").click()
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("The password is not right.")

            environment.single.signIn(page)
            step("signing out", environment.single)
            page.button("Sign out").click()
            page.navigate("/")
            assertThat(page.heading("Sign in")).isVisible()
        }
    }

    @Test
    fun `the admin of the multi user deployment signs in with an e-mailed link that works once`() {
        val multi = environment.multi
        val link =
            environment.context(multi).use { context ->
                val page = context.newPage()
                page.navigate("/")
                assertThat(page.heading("Sign in")).isVisible()
                step("asking for a login link for ${multi.adminEmail}", multi)
                val known = environment.mailtrap.mailIds(multi.adminEmail)
                page.getByLabel("E-mail address").fill(multi.adminEmail)
                page.button("Send login link").click()
                assertThat(page.getByTestId("login-sent")).containsText(multi.adminEmail)

                val link = environment.mailtrap.loginLink(multi.adminEmail, known)
                step("opening the link of the mail", multi)
                page.navigate(link)
                assertThat(page.heading("Apps")).isVisible()
                // the token leaves the address once it is exchanged
                page.url() shouldNotContain "token="
                page.reload()
                assertThat(page.heading("Apps")).isVisible()
                page.button("Sign out").click()
                link
            }
        environment.context(multi).use { context ->
            val page = context.newPage()
            step("opening the used link again, it is refused", multi)
            page.navigate(link)
            assertThat(page.heading("Sign in failed")).isVisible()
        }
    }

    @Test
    fun `the admin of the multi user deployment also signs in with the password`() {
        environment.context(environment.multi).use { context ->
            environment.multi.signIn(context.newPage())
        }
    }
}
