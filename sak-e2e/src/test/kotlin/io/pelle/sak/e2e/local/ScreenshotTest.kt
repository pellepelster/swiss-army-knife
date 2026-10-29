package io.pelle.sak.e2e.local

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.ScreenshotAnimations
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths


// where the screenshots go, docs/screenshots/ by e2eScreenshots
private val SCREENSHOTS: Path = Paths.get(System.getProperty("sak.e2e.screenshots") ?: "build/screenshots")

// the sizes a page is shown at, each screenshot is taken in both
enum class Device(
    val width: Int,
    val height: Int,
    val scale: Double,
    val mobile: Boolean,
) {
    DESKTOP(1280, 800, 1.0, false),
    MOBILE(390, 844, 2.0, true),
}

// the screenshots of the docs: the login with the password and by e-mail (the mail too), the apps
// in light and dark, the notes app, static hosting and redirects; only the page, no browser around
// it. Against the environment of `mise run local:run`, in the light theme unless named
@Tag("screenshots")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScreenshotTest {
    private lateinit var playwright: Playwright
    private lateinit var browser: Browser
    private lateinit var data: LocalData

    @BeforeAll
    fun start() {
        Files.createDirectories(SCREENSHOTS)
        playwright = Playwright.create()
        // a fake microphone that is let in without asking, for the recording
        browser =
            playwright.chromium().launch(
                BrowserType.LaunchOptions().setArgs(listOf("--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream")),
            )
        data = LocalData(playwright).also { it.seed() }
    }

    @AfterAll
    fun stop() {
        playwright.close()
    }

    private fun context(
        device: Device,
        signedIn: Boolean,
        scheme: ColorScheme = ColorScheme.LIGHT,
    ): BrowserContext {
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(FRONTEND_URL)
                    .setViewportSize(device.width, device.height)
                    .setDeviceScaleFactor(device.scale)
                    .setIsMobile(device.mobile)
                    .setHasTouch(device.mobile)
                    .setColorScheme(scheme)
                    .setPermissions(listOf("microphone")),
            )
        if (signedIn) data.signIn(context)
        return context
    }

    // the page alone, without moving parts
    private fun Page.shot(
        name: String,
        device: Device,
    ) {
        waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        screenshot(
            Page
                .ScreenshotOptions()
                .setPath(SCREENSHOTS.resolve("$name-${device.name.lowercase()}.png"))
                .setAnimations(ScreenshotAnimations.DISABLED)
                .setCaret(com.microsoft.playwright.options.ScreenshotCaret.HIDE),
        )
    }

    private fun Page.button(label: String) = locator("button[aria-label='$label']:visible").first()

    @ParameterizedTest
    @EnumSource(Device::class)
    fun `the login with the password`(device: Device) {
        val context = context(device, signedIn = false)
        val page = context.newPage()
        page.navigate("/login")
        page.getByTestId("use-password").click()
        page.getByLabel("Admin password").fill(ADMIN_PASSWORD)
        page.shot("login-password", device)
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign in")).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Apps"))).isVisible()
        context.close()
    }

    @ParameterizedTest
    @EnumSource(Device::class)
    fun `the login by e-mail and its mail`(device: Device) {
        Mailpit.clearInbox()
        val context = context(device, signedIn = false)
        val page = context.newPage()
        page.navigate("/login")
        page.getByLabel("E-mail address").fill(ADMIN)
        page.shot("login-email", device)
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Send login link")).click()
        assertThat(page.getByTestId("login-sent")).isVisible()
        page.shot("login-email-sent", device)

        // the mail as mailpit shows it
        val link = Mailpit.loginLink(ADMIN)
        page.navigate("$MAILPIT_URL/view/${Mailpit.lastMessageId(ADMIN)}")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        page.shot("login-email-mail", device)

        page.navigate(link)
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Apps"))).isVisible()
        context.close()
    }

    @ParameterizedTest
    @EnumSource(Device::class)
    fun `the apps in the light and the dark theme`(device: Device) {
        for ((scheme, name) in listOf(ColorScheme.LIGHT to "apps", ColorScheme.DARK to "apps-dark")) {
            val context = context(device, signedIn = true, scheme = scheme)
            val page = context.newPage()
            page.navigate("/")
            assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Apps"))).isVisible()
            page.shot(name, device)
            context.close()
        }
    }

    @ParameterizedTest
    @EnumSource(Device::class)
    fun `the notes app`(device: Device) {
        data.seedNotes()
        val context = context(device, signedIn = true)
        val page = context.newPage()
        page.navigate("/notes")
        // the text of a note is the value of its textarea
        page.waitForFunction(
            "text => [...document.querySelectorAll('textarea')].some(area => area.value === text)",
            NOTES.first().first,
        )
        page.shot("notes", device)

        // a text note, written right on the page; the newest note is the first
        val newest = page.locator("[data-testid^='note-'] textarea").first()
        page.button("New note").click()
        newest.fill(NEW_NOTE)
        page.waitForTimeout(1_000.0)
        page.shot("notes-new-text", device)

        // a note with a photo, from the camera on a phone
        val chooser = page.waitForFileChooser { page.button("New note with a photo").click() }
        chooser.setFiles(data.photo())
        assertThat(page.locator(".note-photos img").first()).isVisible()
        newest.fill(PHOTO_NOTE)
        page.waitForTimeout(1_000.0)
        page.shot("notes-photo", device)

        // a note with a recording: the overlay while it records, then the note
        page.button("New note with a recording").click()
        assertThat(page.locator("[aria-label^='Stop the recording']")).isVisible()
        page.waitForTimeout(2_000.0)
        page.shot("notes-recording", device)
        page.locator("[aria-label^='Stop the recording']").click()
        assertThat(page.locator("button[aria-label^='Play ']").first()).isVisible()
        newest.fill(RECORDING_NOTE)
        page.waitForTimeout(1_000.0)
        page.shot("notes-recorded", device)
        context.close()
    }

    @ParameterizedTest
    @EnumSource(Device::class)
    fun `static hosting`(device: Device) {
        val context = context(device, signedIn = true)
        val page = context.newPage()
        page.navigate("/static-hosting")
        assertThat(page.getByText(BUCKET).first()).isVisible()
        page.shot("hosting", device)
        page.navigate("/static-hosting/$BUCKET")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName(BUCKET))).isVisible()
        page.shot("hosting-bucket", device)
        context.close()
    }

    @ParameterizedTest
    @EnumSource(Device::class)
    fun `redirects`(device: Device) {
        val context = context(device, signedIn = true)
        val page = context.newPage()
        page.navigate("/redirects")
        assertThat(page.getByText(REDIRECTS.first().first).first()).isVisible()
        page.shot("redirects", device)
        page.navigate("/redirects/${REDIRECTS.first().first}")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName(REDIRECTS.first().first))).isVisible()
        page.shot("redirects-redirect", device)
        context.close()
    }

}
