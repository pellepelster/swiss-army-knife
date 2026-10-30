package io.pelle.sak.e2e.local

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.pelle.sak.e2e.util.ADMIN
import io.pelle.sak.e2e.util.FRONTEND_URL
import io.pelle.sak.e2e.util.LocalData
import io.pelle.sak.e2e.util.MAILPIT_URL
import io.pelle.sak.e2e.util.Mailpit
import io.pelle.sak.e2e.util.NEW_NOTE
import io.pelle.sak.e2e.util.NOTES
import io.pelle.sak.e2e.util.PHOTO_NOTE
import io.pelle.sak.e2e.util.RECORDING_NOTE
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val VIDEOS: Path = Paths.get(System.getProperty("sak.e2e.videos") ?: "build/videos")

// the redirect the tour creates; it goes before the tour, so the tour can create it again
private const val TOUR_REDIRECT = "go.example.org"

// the video of the apps in build/videos/web.webm, on a desktop: the login by e-mail with its mail,
// the notes app, a bucket and a redirect created. Against the environment of `mise run local:run`,
// at a pace a viewer can follow
@Tag("video")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebVideoTest {
    private lateinit var playwright: Playwright
    private lateinit var browser: Browser
    private lateinit var data: LocalData

    @BeforeAll
    fun start() {
        Files.createDirectories(VIDEOS)
        playwright = Playwright.create()
        // every action a little slower, a fake microphone for the recording
        browser =
            playwright.chromium().launch(
                BrowserType
                    .LaunchOptions()
                    .setSlowMo(250.0)
                    .setArgs(listOf("--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream")),
            )
        data = LocalData(playwright).also { it.seed() }
        data.api.delete("/api/v1/redirects/$TOUR_REDIRECT")
    }

    @AfterAll
    fun stop() {
        playwright.close()
    }

    // the name of a deleted bucket stays taken: the first of blog, blog-2, … that is free
    private fun freeBucket(): String =
        generateSequence(1) { it + 1 }
            .map { if (it == 1) "blog" else "blog-$it" }
            .first { data.api.get("/api/v1/static-hosting/$it").status() == 404 }

    private fun Page.pause(milliseconds: Double = 1_500.0) = waitForTimeout(milliseconds)

    private fun Locator.typeSlowly(text: String) {
        click()
        pressSequentially(text, Locator.PressSequentiallyOptions().setDelay(70.0))
    }

    private fun Page.button(label: String) = locator("button[aria-label='$label']:visible").first()

    @Test
    fun `the tour of the apps`() {
        Mailpit.clearInbox()
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setBaseURL(FRONTEND_URL)
                    .setViewportSize(1280, 800)
                    .setPermissions(listOf("microphone"))
                    .setRecordVideoDir(VIDEOS.resolve("raw"))
                    .setRecordVideoSize(1280, 800),
            )
        val page = context.newPage()

        // the login by e-mail, the mail in mailpit, the link
        page.navigate("/login")
        page.getByLabel("E-mail address").typeSlowly(ADMIN)
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Send login link")).click()
        assertThat(page.getByTestId("login-sent")).isVisible()
        page.pause()
        val link = Mailpit.loginLink(ADMIN)
        page.navigate("$MAILPIT_URL/view/${Mailpit.lastMessageId(ADMIN)}")
        page.pause(2_500.0)
        page.navigate(link)
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Apps"))).isVisible()
        page.pause()

        // notes: a text note in pink, a photo, a voice note
        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Notes")).first().click()
        page.waitForFunction(
            "text => [...document.querySelectorAll('textarea')].some(area => area.value === text)",
            NOTES.first().first,
        )
        page.pause()
        val newest = page.locator("[data-testid^='note-']").first()
        page.button("New note").click()
        newest.locator("textarea").typeSlowly(NEW_NOTE)
        newest.locator("button[aria-label='pink']").click()
        page.pause()
        val chooser = page.waitForFileChooser { page.button("New note with a photo").click() }
        chooser.setFiles(data.photo())
        assertThat(page.locator(".note-photos img").first()).isVisible()
        newest.locator("textarea").typeSlowly(PHOTO_NOTE)
        page.pause()
        page.button("New note with a recording").click()
        page.pause(3_000.0)
        page.locator("[aria-label^='Stop the recording']").click()
        assertThat(page.locator("button[aria-label^='Play ']").first()).isVisible()
        newest.locator("textarea").typeSlowly(RECORDING_NOTE)
        page.pause()

        // static hosting: a new bucket served at a domain, its tabs
        page.navigate("/")
        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Static hosting")).first().click()
        page.pause()
        page.locator("a[href='/static-hosting/create']").click()
        val bucket = freeBucket()
        page.locator("#bucket-name").typeSlowly(bucket)
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add domain")).click()
        page.locator("#domain-0").typeSlowly("$bucket.example.org")
        page.locator("button[type=submit]").click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName(bucket))).isVisible()
        page.pause()
        for (tab in listOf("Credentials", "Publishing")) {
            page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName(tab)).click()
            page.pause()
        }

        // redirects: a domain that leads to another one
        page.navigate("/")
        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Redirects")).first().click()
        page.pause()
        page.locator("a[href='/redirects/create']").click()
        page.locator("#source-domain").typeSlowly(TOUR_REDIRECT)
        page.locator("#target-domain").check()
        page.locator("#target-domain-name").typeSlowly("example.org")
        page.locator("button[type=submit]").click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName(TOUR_REDIRECT))).isVisible()
        page.pause(2_500.0)

        val video = checkNotNull(page.video()) { "the context records no video" }
        context.close()
        video.saveAs(VIDEOS.resolve("web.webm"))
        video.delete()
    }
}
