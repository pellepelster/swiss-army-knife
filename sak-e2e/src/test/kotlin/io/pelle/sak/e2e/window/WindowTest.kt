package io.pelle.sak.e2e.window

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.ScreenshotAnimations
import com.microsoft.playwright.options.ScreenshotCaret
import io.pelle.sak.e2e.util.WINDOW_HEIGHT
import io.pelle.sak.e2e.util.WINDOW_WIDTH
import io.pelle.sak.e2e.util.openWindow
import io.pelle.sak.e2e.util.resume
import io.pelle.sak.e2e.util.waitForHold
import io.pelle.sak.e2e.util.window
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val SCREENSHOTS: Path = Paths.get(System.getProperty("sak.e2e.screenshots") ?: "build/screenshots")
private val VIDEOS: Path = Paths.get(System.getProperty("sak.e2e.videos") ?: "build/videos")

// what the tour types: the deployment example1 on krawallbu.de in domain mode, single user
private const val TOKEN = "kGq7XnL2cVb9WmR4tYp8sZ1aD6fH3jK5eQ0uI2oP7lN9xC4vB8mT1rE6yU3wA5sD"
private const val NAME = "example1"
private const val PASSWORD = "correct horse battery staple"
private const val ROOT_DOMAIN = "krawallbu.de"

// the events of the channels where the screenshots stop them: the image half pulled, the 20th task
// of the playbook running (10 resources with 2 events each, the start of the provisioning, 2
// events a task)
private const val CHECKS_HOLD = 6
private const val CREATE_HOLD = 20 + 1 + 2 * 20

// a tour through the window: the checks, a provider token, the create of example1 with its plan
// and its run, the page of the new deployment with its check, the list, and the password that
// opens the deployment again; `step` gets the name of each view as it is shown, `type` types into a field
private fun Page.tour(
    step: (String) -> Unit,
    type: (Locator, String) -> Unit,
) {
    // the checks, then the providers: no token yet
    assertThat(locator("#checks li").first()).isVisible()
    step("checks")
    assertThat(locator("#view-providers")).isVisible()
    step("providers")

    locator("#providers-add").click()
    locator("#provider-types button").first().click()
    type(locator("#token-input"), TOKEN)
    step("token")
    locator("#token-add").click()
    assertThat(locator("#provider-tokens li")).hasCount(1)
    step("providers-token")
    locator("#providers-next").click()

    // no deployment yet, a new one
    assertThat(locator("#deployments-status")).not().isEmpty()
    step("deployments-empty")
    locator("#deployments-create").click()
    type(locator("#create-name"), NAME)
    locator("#create-password").focus()
    type(locator("#create-password"), PASSWORD)
    type(locator("#create-password-repeat"), PASSWORD)
    locator("#create-location").selectOption("hel1")
    locator("#create-root-domain").selectOption("krawallbu.de")
    locator("#create-dns-mode-domain").check()
    locator("#create-name").scrollIntoViewIfNeeded()
    step("create")

    // multiple users need a mail server; a test e-mail goes to the address the dialog asks for,
    // then back to a single user, the deployment of the tour
    locator("#create-user-mode-multi").check()
    // the sender is noreply@<root domain> until it is changed
    assertThat(locator("#create-smtp-from")).hasValue("noreply@krawallbu.de")
    assertThat(locator("#create-smtp-test")).isDisabled()
    type(locator("#create-smtp-host"), "smtp.example.org")
    type(locator("#create-smtp-username"), "sak@example.org")
    type(locator("#create-smtp-password"), "mail server password")
    locator("#create-smtp-test").click()
    assertThat(locator("#smtp-test")).isVisible()
    assertThat(locator("#smtp-test-text")).containsText("noreply@krawallbu.de")
    type(locator("#smtp-test-to"), "admin@example.org")
    step("create-smtp-test")
    locator("#smtp-test button[value=send]").click()
    assertThat(locator("#create-smtp-status")).containsText("took the test e-mail to admin@example.org")
    locator("#create-smtp-test").scrollIntoViewIfNeeded()
    step("create-smtp")
    locator("#create-user-mode-single").check()

    // the plan with its changes, then the run: the resources and the provisioning, done at the end
    locator("#create-plan").click()
    assertThat(locator("#view-plan")).isVisible()
    assertThat(locator("#plan-deploy")).isVisible()
    step("plan")
    locator("#plan-deploy").click()
    assertThat(locator("#view-run")).isVisible()
    step("provisioning")
    assertThat(locator("#run-done")).isEnabled(EnabledTimeout.long)
    step("done")

    // the page of the new deployment reads its configuration once, shows it above the resources
    // and checks it with it: everything up to date, so deploy changes stays disabled and check is
    // the green button; force deploy is there after the check
    locator("#run-done").click()
    assertThat(locator("#view-deployment")).isVisible()
    assertThat(locator("#deployment-config li").first()).containsText(ROOT_DOMAIN)
    assertThat(locator("#deployment-config")).containsText("sak@krawallbu.de")
    assertThat(locator("#deployment-force")).isEnabled()
    assertThat(locator("#deployment-deploy")).isDisabled()
    assertThat(locator("#deployment-check")).hasClass(java.util.regex.Pattern.compile("btn-primary"))
    step("deployment")

    // config…: the form of a create, filled in with the configuration the page read
    locator("#deployment-edit-config").click()
    assertThat(locator("#view-create")).isVisible()
    assertThat(locator("#create-title")).hasText("Config")
    assertThat(locator("#create-smtp-from")).hasValue("sak@krawallbu.de")
    assertThat(locator("#create-dns-mode-domain")).isChecked()
    // cancel leads to the list, with the deployment, and the password opens it again
    locator("#create-cancel").click()
    assertThat(locator("#deployments li.deployment")).hasCount(1)
    step("deployments")
    locator("#deployments .deployment-open").first().click()
    assertThat(locator("#view-password")).isVisible()
    type(locator("#password-input"), PASSWORD)
    assertThat(locator("#password-next")).isEnabled()
    step("password")
    locator("#password-next").click()
    assertThat(locator("#view-deployment")).isVisible()
}

private object EnabledTimeout {
    // the create of the tour takes about a minute
    val long: com.microsoft.playwright.assertions.LocatorAssertions.IsEnabledOptions =
        com.microsoft.playwright.assertions.LocatorAssertions
            .IsEnabledOptions()
            .setTimeout(180_000.0)
}

// the screenshots of the window in docs/development/screenshots/window-<view>[-dark].png, light and dark
@Tag("screenshots")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WindowScreenshotTest {
    private lateinit var playwright: Playwright
    private lateinit var browser: Browser

    @BeforeAll
    fun start() {
        Files.createDirectories(SCREENSHOTS)
        playwright = Playwright.create()
        browser = playwright.chromium().launch()
    }

    @AfterAll
    fun stop() {
        playwright.close()
    }

    @Test
    fun `the window in the light and the dark theme`() {
        for ((scheme, suffix) in listOf(ColorScheme.LIGHT to "", ColorScheme.DARK to "-dark")) {
            val context = browser.window(scheme)
            val page = context.openWindow(pace = 0.05, hold = mapOf("run_checks" to CHECKS_HOLD, "apply_deployment" to CREATE_HOLD))
            fun shot(name: String) =
                page.screenshot(
                    Page
                        .ScreenshotOptions()
                        .setPath(SCREENSHOTS.resolve("window-$name$suffix.png"))
                        .setAnimations(ScreenshotAnimations.DISABLED)
                        .setCaret(ScreenshotCaret.HIDE),
                )
            page.tour(
                step = { view ->
                    when (view) {
                        // while the checks and the create run
                        "checks" -> {
                            page.waitForHold("run_checks")
                            shot(view)
                            page.resume("run_checks")
                        }
                        "provisioning" -> {
                            page.waitForHold("apply_deployment")
                            shot(view)
                            page.resume("apply_deployment")
                        }
                        else -> shot(view)
                    }
                },
                type = { field, text -> field.fill(text) },
            )
            context.close()
        }
    }
}

// the video of the window in build/videos/window.webm: the tour at the pace of the app, typed
// like a person types
@Tag("video")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WindowVideoTest {
    private lateinit var playwright: Playwright
    private lateinit var browser: Browser

    @BeforeAll
    fun start() {
        Files.createDirectories(VIDEOS)
        playwright = Playwright.create()
        browser = playwright.chromium().launch()
    }

    @AfterAll
    fun stop() {
        playwright.close()
    }

    @Test
    fun `the tour of the window`() {
        val context =
            browser.window {
                setRecordVideoDir(VIDEOS.resolve("raw"))
                setRecordVideoSize(WINDOW_WIDTH, WINDOW_HEIGHT)
            }
        val page = context.openWindow()
        page.tour(
            // a moment on each view to take it in
            step = { page.waitForTimeout(1_500.0) },
            type = { field, text ->
                field.click()
                field.pressSequentially(text, Locator.PressSequentiallyOptions().setDelay(60.0))
            },
        )
        page.waitForTimeout(2_000.0)
        val video = checkNotNull(page.video()) { "the context records no video" }
        context.close()
        video.saveAs(VIDEOS.resolve("window.webm"))
        video.delete()
    }
}
