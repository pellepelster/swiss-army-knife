package io.pelle.sak.e2e.window

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.ScreenshotAnimations
import com.microsoft.playwright.options.ScreenshotCaret
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

// the events of the channels where the screenshots stop them: the image half pulled, the 20th task
// of the playbook running (10 resources with 2 events each, the start of the provisioning, 2
// events a task)
private const val CHECKS_HOLD = 6
private const val CREATE_HOLD = 20 + 1 + 2 * 20

// a tour through the window: the checks, a provider token, the create of example1 with its plan
// and its run, the page of the new deployment and its check, the list, and the password that opens
// the deployment again; `step` gets the name of each view as it is shown, `type` types into a field
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

    // the page of the new deployment and its check: everything up to date, so no deploy changes
    locator("#run-done").click()
    assertThat(locator("#view-deployment")).isVisible()
    step("deployment")
    locator("#deployment-check").click()
    assertThat(locator("#view-plan")).isVisible()
    assertThat(locator("#plan-deploy")).isHidden()
    step("plan-check")
    locator("#plan-cancel").click()
    assertThat(locator("#view-deployment")).isVisible()

    // the list with it, and the password that opens it again
    locator("#deployment-back").click()
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
            val page = context.openWindow(pace = 0.05, hold = mapOf("run_checks" to CHECKS_HOLD, "create_deployment" to CREATE_HOLD))
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
                            page.waitForHold("create_deployment")
                            shot(view)
                            page.resume("create_deployment")
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
