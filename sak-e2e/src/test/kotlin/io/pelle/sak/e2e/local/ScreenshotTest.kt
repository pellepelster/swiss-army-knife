package io.pelle.sak.e2e.local

import com.microsoft.playwright.APIRequest
import com.microsoft.playwright.APIRequestContext
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.RequestOptions
import com.microsoft.playwright.options.ScreenshotAnimations
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

// the admin of config/local-config.yaml and the local admin password (SAK_LOCAL_ADMIN_PASSWORD_HASH
// of mise.toml)
private const val ADMIN = "admin@localhost"
private const val ADMIN_PASSWORD = "a password of sixteen"

// where the screenshots go, docs/screenshots/ by e2eScreenshots
private val SCREENSHOTS: Path = Paths.get(System.getProperty("sak.e2e.screenshots") ?: "build/screenshots")

// the notes shown on the notes page, one of each color; the run removes them again before it
// creates them, other notes of the local environment stay
private val NOTES =
    listOf(
        "Buy oat milk, coffee and two lemons" to "yellow",
        "Call the dentist about the appointment on Friday" to "pink",
        "Ideas for the garden: tomatoes, basil, a bench under the apple tree" to "green",
        "Read: Designing Data-Intensive Applications" to "blue",
        "Book the train to Hamburg" to "orange",
    )
private const val NEW_NOTE = "Pick up the parcel at the post office"
private const val PHOTO_NOTE = "The view from the hill last night"
private const val RECORDING_NOTE = "Voice memo: what to say in the talk on Monday"

// a bucket and two redirects, kept from run to run: the name of a deleted bucket stays taken
private const val BUCKET = "docs-site"
private const val BUCKET_DOMAIN = "docs.example.org"
private val REDIRECTS = listOf("old.example.org" to "example.org", "www.example.org" to "example.org")

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
    private lateinit var api: APIRequestContext

    // the session of the admin, what a signed in page keeps in its local storage
    private lateinit var session: JsonObject

    @BeforeAll
    fun start() {
        Files.createDirectories(SCREENSHOTS)
        playwright = Playwright.create()
        // a fake microphone that is let in without asking, for the recording
        browser =
            playwright.chromium().launch(
                BrowserType.LaunchOptions().setArgs(listOf("--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream")),
            )
        session = passwordLogin()
        api =
            playwright.request().newContext(
                APIRequest
                    .NewContextOptions()
                    .setBaseURL(FRONTEND_URL)
                    .setExtraHTTPHeaders(mapOf("Authorization" to "Bearer ${session.getValue("sessionToken").jsonPrimitive.content}")),
            )
        seed()
    }

    @AfterAll
    fun stop() {
        playwright.close()
    }

    // one sign in with the password for the data of the run, the pages sign in with it too
    private fun passwordLogin(): JsonObject {
        val request = playwright.request().newContext(APIRequest.NewContextOptions().setBaseURL(FRONTEND_URL))
        val answer =
            request.post(
                "/api/v1/auth/login/password",
                RequestOptions.create().setData(mapOf("password" to ADMIN_PASSWORD, "redirect" to "/")),
            )
        check(answer.ok()) { "the password login answered ${answer.status()}, is mise run local:run up?" }
        return Json.parseToJsonElement(answer.text()).jsonObject.also { request.dispose() }
    }

    private fun json(response: com.microsoft.playwright.APIResponse) = Json.parseToJsonElement(response.text())

    // the notes, the bucket and the redirects the pages show
    private fun seed() {
        seedNotes()
        if (api.get("/api/v1/static-hosting/$BUCKET").status() == 404) {
            api.post("/api/v1/static-hosting", RequestOptions.create().setData(mapOf("name" to BUCKET, "domains" to listOf(BUCKET_DOMAIN))))
        }
        REDIRECTS.forEach { (source, target) ->
            if (api.get("/api/v1/redirects/$source").status() == 404) {
                api.post(
                    "/api/v1/redirects",
                    RequestOptions.create().setData(mapOf("sourceDomain" to source, "target" to mapOf("type" to "domain", "domain" to target))),
                )
            }
        }
    }

    // the notes of NOTES alone: those of a run before and empty ones go, other notes stay
    private fun seedNotes() {
        val texts = NOTES.map { it.first }.toSet() + NEW_NOTE + PHOTO_NOTE + RECORDING_NOTE
        json(api.get("/api/v1/notes?limit=100"))
            .jsonObject
            .getValue("items")
            .jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("text").jsonPrimitive.content in texts || it.getValue("text").jsonPrimitive.content.isEmpty() }
            .forEach { api.delete("/api/v1/notes/${it.getValue("id").jsonPrimitive.content}") }
        NOTES.reversed().forEach { (text, color) ->
            val created =
                api.post(
                    "/api/v1/notes",
                    RequestOptions.create().setData(
                        buildJsonObject {
                            put("text", text)
                            put("color", color)
                        }.toString(),
                    ).setHeader("Content-Type", "application/json"),
                )
            check(created.ok()) { "a note could not be created: ${created.status()} ${created.text()}" }
        }
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
        if (signedIn) {
            val stored =
                buildJsonObject {
                    put("token", session.getValue("sessionToken").jsonPrimitive.content)
                    put("email", session.getValue("email").jsonPrimitive.content)
                    put("expiresAt", session.getValue("expiresAt").jsonPrimitive.content)
                    put("roles", session.getValue("roles"))
                }
            // the session as a string literal of javascript
            val literal = Json.encodeToString(String.serializer(), stored.toString())
            context.addInitScript("localStorage.setItem('sak.session', $literal)")
        }
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
        seedNotes()
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
        chooser.setFiles(photo())
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

    // a photo for the note: an evening sky over hills, drawn once per run
    private fun photo(): Path {
        val file = Files.createTempFile("sak-photo", ".png")
        val image = BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            paint = GradientPaint(0f, 0f, Color(0xF9, 0xA8, 0x5D), 0f, 600f, Color(0x6A, 0x4C, 0x93))
            fillRect(0, 0, 1200, 900)
            color = Color(0xFF, 0xE0, 0x8A)
            fillOval(760, 260, 180, 180)
            color = Color(0x3B, 0x5B, 0x4F)
            fillOval(-200, 560, 900, 600)
            color = Color(0x2E, 0x48, 0x3F)
            fillOval(400, 600, 1100, 600)
            dispose()
        }
        ImageIO.write(image, "png", file.toFile())
        return file
    }
}
