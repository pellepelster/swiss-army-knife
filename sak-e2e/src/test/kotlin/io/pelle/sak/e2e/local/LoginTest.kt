package io.pelle.sak.e2e.local

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.pelle.sak.e2e.util.ADMIN
import io.pelle.sak.e2e.util.FRONTEND_URL
import io.pelle.sak.e2e.util.Mailpit
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.regex.Pattern

// the backend sends 5 links an hour to an address (ADMIN), so every run asks for one link only

// the login of the apps in chromium, one test after the other: they share the inbox of mailpit,
// the rate limits of the backend and the link of the first test
@Tag("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LoginTest {
    private lateinit var playwright: Playwright
    private lateinit var browser: Browser
    private lateinit var context: BrowserContext
    private lateinit var page: Page

    // the link of the run, asked for once
    private var link: String? = null

    private val login = Pattern.compile("/login")

    @BeforeAll
    fun startBrowser() {
        playwright = Playwright.create()
        browser = playwright.chromium().launch()
    }

    @AfterAll
    fun stopBrowser() {
        playwright.close()
    }

    @BeforeEach
    fun openPage() {
        Mailpit.clearInbox()
        context = browser.newContext(Browser.NewContextOptions().setBaseURL(FRONTEND_URL))
        page = context.newPage()
    }

    @AfterEach
    fun closePage() {
        context.close()
    }

    @Test
    @Order(1)
    fun `signs in with the e-mailed link and signs out`() {
        // the apps need a session, the login remembers where it started from
        page.navigate("/")
        assertThat(page).hasURL(login)
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Sign in"))).isVisible()

        page.getByLabel("E-mail address").fill(ADMIN)
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Send login link")).click()
        assertThat(page.getByTestId("login-sent")).containsText(ADMIN)

        val link = Mailpit.loginLink(ADMIN).also { link = it }
        page.navigate(link)

        // signed in, the token is gone from the address and the history
        val apps = page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Apps"))
        assertThat(apps).isVisible()
        page.url() shouldNotContain "token="
        page.reload()
        assertThat(apps).isVisible()

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign out")).click()
        assertThat(page).hasURL(login)
        page.navigate("/")
        assertThat(page).hasURL(login)
    }

    @Test
    @Order(2)
    fun `a sign-in link works only once`() {
        val used = checkNotNull(link) { "the test before signs in with the link" }
        val failed = Page.GetByRoleOptions().setName("Sign in failed")

        // used by the test before: neither another browser nor the same one signs in with it again
        val other = browser.newContext(Browser.NewContextOptions().setBaseURL(FRONTEND_URL))
        val again = other.newPage()
        again.navigate(used)
        assertThat(again.getByRole(AriaRole.HEADING, failed)).isVisible()
        assertThat(again.getByRole(AriaRole.ALERT)).containsText("invalid, expired or was already used")
        again.navigate("/")
        assertThat(again).hasURL(login)
        other.close()

        page.navigate(used)
        assertThat(page.getByRole(AriaRole.HEADING, failed)).isVisible()
        page.navigate("/")
        assertThat(page).hasURL(login)
    }

    @Test
    @Order(3)
    fun `answers an address that may not sign in alike and sends it nothing`() {
        val stranger = "stranger@localhost"
        page.navigate("/login")
        page.getByLabel("E-mail address").fill(stranger)
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Send login link")).click()
        assertThat(page.getByTestId("login-sent")).containsText(stranger)

        // the backend decides after it answered, give it the time a mail would take
        page.waitForTimeout(3_000.0)
        Mailpit.mailsTo(stranger) shouldBe 0
    }

    @Test
    @Order(4)
    fun `refuses a link that is not one`() {
        page.navigate("/signin?token=not-a-token")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Sign in failed"))).isVisible()
        page.url() shouldNotContain "token="
    }
}
