package io.pelle.sak.e2e.window

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ScreenshotAnimations
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

private val SCREENSHOTS: Path = Paths.get(System.getProperty("sak.e2e.screenshots") ?: "build/screenshots")

// the binary of `cargo build` in sak-cli/src-tauri (mise run docs:screenshots builds it)
private val SAKCTL: Path = Paths.get(System.getProperty("sak.e2e.sakctl") ?: "../sak-cli/src-tauri/target/debug/sakctl")

// the fonts of sak-theme, IBM Plex Mono for the terminal
private val THEME: Path = Paths.get(System.getProperty("sak.e2e.theme") ?: "../sak-theme")

// the command line of sakctl for the docs: what `sakctl --help` prints, in a terminal in the colors
// of the terminal blocks of the docs (site/assets/css/sak-code-block.css); the docs draw the window
// around it (sakctl-cli-*.png, site/layouts/_default/_markup/render-image.html)
@Tag("screenshots")
class CliScreenshotTest {
    @Test
    fun `the help of the command line`() {
        val process = ProcessBuilder(SAKCTL.toString(), "--help").redirectErrorStream(true).start()
        val help = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "sakctl --help failed: $help" }

        // a file next to the fonts, so the page loads them as files
        val page = Files.createTempFile(THEME.toAbsolutePath(), "cli-", ".html")
        try {
            Files.writeString(page, html("sakctl --help", help))
            Playwright.create().use { playwright ->
                val browser = playwright.chromium().launch()
                val context = browser.newContext(Browser.NewContextOptions().setViewportSize(900, 400).setDeviceScaleFactor(2.0))
                val tab = context.newPage()
                tab.navigate(page.toUri().toString())
                tab.evaluate("document.fonts.ready")
                Files.createDirectories(SCREENSHOTS)
                tab.locator(".terminal").screenshot(
                    com.microsoft.playwright.Locator
                        .ScreenshotOptions()
                        .setPath(SCREENSHOTS.resolve("sakctl-cli-help.png"))
                        .setAnimations(ScreenshotAnimations.DISABLED),
                )
            }
        } finally {
            Files.deleteIfExists(page)
        }
    }

    // the lines of the terminal as they are, in a pre; nothing indents them
    private fun html(
        command: String,
        output: String,
    ) = "<!doctype html><html><head><meta charset=\"utf-8\"><link rel=\"stylesheet\" href=\"fonts.css\"><style>" +
        "body { margin: 0; background: #1d1c1a; }" +
        ".terminal { display: inline-block; margin: 0; padding: 18px 22px; background: #1d1c1a; color: #ece8dd;" +
        " font: 13px/1.45 'IBM Plex Mono', monospace; white-space: pre; }" +
        ".prompt { color: #8fd18b; }" +
        "</style></head><body><pre class=\"terminal\"><span class=\"prompt\">$ </span>" +
        escape(command) + "\n" + escape(output.trimEnd()) + "</pre></body></html>"

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
