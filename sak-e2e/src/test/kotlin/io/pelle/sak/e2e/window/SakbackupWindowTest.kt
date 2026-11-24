package io.pelle.sak.e2e.window

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.pelle.sak.e2e.util.BACKUP_UI
import io.pelle.sak.e2e.util.backupFixtures
import io.pelle.sak.e2e.util.openWindow
import io.pelle.sak.e2e.util.window
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

// the window of sakbackup in chromium, rust replaced by the fixtures of gui/window_fixtures.rs: the
// checks at the start, the sign-in after them, the footer of the update. `mise run backup:test:window`
@Tag("window")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SakbackupWindowTest {
    private lateinit var playwright: Playwright
    private lateinit var browser: Browser

    @BeforeAll
    fun start() {
        playwright = Playwright.create()
        browser = playwright.chromium().launch()
    }

    @AfterAll
    fun stop() {
        browser.close()
        playwright.close()
    }

    @Test
    fun `with restic found the splash screen shows its version and the sign-in follows`() {
        browser.window(ui = BACKUP_UI, fixtures = backupFixtures("restic")).use { context ->
            val page = context.openWindow()
            val check = page.locator("#checks li")
            assertThat(check).hasCount(1)
            assertThat(check.locator(".check-name")).hasText("Find restic")
            assertThat(check.locator(".check-detail")).hasText("restic 0.16.4")
            // the sign-in replaces the splash screen once the checks passed
            assertThat(page.locator("#view-login")).isVisible()
            assertThat(page.locator("#view-splash")).isHidden()
            // the footer below every view
            assertThat(page.locator("#update-version")).hasText("sakbackup latest")
            assertThat(page.locator("#update-button")).hasText("Check for updates")
            assertThat(page.locator(".app-footer")).isVisible()
        }
    }

    @Test
    fun `without restic the splash screen says so and only quit is left`() {
        browser.window(ui = BACKUP_UI, fixtures = backupFixtures("no-restic")).use { context ->
            val page = context.openWindow()
            val check = page.locator("#checks li")
            assertThat(check).hasAttribute("data-state", "failed")
            assertThat(check.locator(".check-detail")).containsText("SAK Backup needs restic")
            assertThat(page.locator("#splash-quit")).isEnabled()
            assertThat(page.locator("#splash-quit")).isFocused()
            assertThat(page.locator("#view-splash")).isVisible()
            assertThat(page.locator("#view-login")).hasCount(0)
            // the failure of the row is enough, no second text below the list
            assertThat(page.locator("#checks-error")).isHidden()
        }
    }

    @Test
    fun `a running backup with a path wider than the window keeps its page and its cancel button in the window`() {
        browser.window(ui = BACKUP_UI, fixtures = backupFixtures("excludes")).use { context ->
            val page = context.openWindow()
            // the run stays at its first event, a file with a very long path
            page.evaluate("() => { window.__sakHold = { run_backup: 1 }; }")
            page.locator("#nav button[data-page=plans]").click()
            page.locator("#plans-list button", com.microsoft.playwright.Page.LocatorOptions().setHasText("Back up now")).first().click()
            val file = page.locator("#run-file")
            assertThat(file).isVisible()
            assertThat(file).containsText("a-file-with-a-long-name.sqlite")
            assertThat(page.locator("#run-numbers")).containsText("40387 of 490015 files")
            // the numbers are not read out; the line of the start is gone once the backup runs
            assertThat(page.locator("#run-status")).hasText("")
            // the path is cut within the page, the page and its button stay in the window
            val width = page.evaluate("() => window.innerWidth").toString().toDouble()
            val run = page.locator(".run-page").boundingBox()!!
            check(run.x + run.width <= width) { "the run page ends at ${run.x + run.width} of $width" }
            check(page.evaluate("() => { const f = document.getElementById('run-file'); return f.scrollWidth > f.clientWidth; }") == true) { "the path is not cut" }
            assertThat(page.locator("#run-close")).isInViewport()
            page.screenshot(com.microsoft.playwright.Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("build/sakbackup-run-long-path.png")))
        }
    }

    @Test
    fun `the plan that runs is striped up to its percent, the navigation shows that a run goes on`() {
        browser.window(ui = BACKUP_UI, fixtures = backupFixtures("excludes")).use { context ->
            val page = context.openWindow()
            page.evaluate("() => { window.__sakHold = { run_backup: 1 }; }")
            val plans = page.locator("#nav button[data-page=plans]")
            plans.click()
            page.locator("#plans-list button", com.microsoft.playwright.Page.LocatorOptions().setHasText("Back up now")).first().click()
            assertThat(page.locator("#run-file")).isVisible()
            // back on the list while the run is held at 9%
            plans.click()
            val row = page.locator("#plans-list li").first()
            assertThat(row).hasClass(java.util.regex.Pattern.compile("\\brow-running\\b"))
            assertThat(row.locator(".row-percent")).hasText("9%")
            check(row.evaluate("(item) => item.style.getPropertyValue('--run-progress')") == "9%")
            assertThat(row.locator(".btn-running")).hasText("Show the run")
            assertThat(plans).hasAttribute("data-running", "")
            page.screenshot(com.microsoft.playwright.Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("build/sakbackup-plan-running.png")))
            // the run goes on and ends: the row and the navigation are as before
            page.evaluate("() => window.__sakResume('run_backup')")
            assertThat(row).not().hasClass(java.util.regex.Pattern.compile("\\brow-running\\b"))
            assertThat(row.locator(".row-percent")).isHidden()
            assertThat(plans).not().hasAttribute("data-running", "")
            assertThat(row.locator("button", com.microsoft.playwright.Locator.LocatorOptions().setHasText("Back up now"))).isVisible()
        }
    }

    @Test
    fun `the excludes of a plan are edited in their dialog, with presets, and saved with the plan`() {
        browser.window(ui = BACKUP_UI, fixtures = backupFixtures("excludes")).use { context ->
            val page = context.openWindow()
            page.locator("#nav button[data-page=plans]").click()
            page.locator("#plans-list .row-body").first().click()
            assertThat(page.locator("#plan-excludes-summary")).hasText("1 patterns")
            page.locator("#plan-excludes-edit").click()

            val dialog = page.locator("#excludes-dialog")
            assertThat(dialog).isVisible()
            assertThat(page.locator("#excludes-title")).hasText("What home leaves out")
            assertThat(page.locator("#excludes-text")).hasValue("node_modules")
            assertThat(page.locator("#excludes-count")).hasText("1 patterns")
            // a preset copies its lines under its name; then it is added
            val caches = page.locator("#excludes-presets button[data-preset=caches]")
            assertThat(caches).hasText("+ Caches and trash")
            caches.click()
            assertThat(page.locator("#excludes-text")).hasValue("node_modules\n\n### Caches and trash\n\$HOME/.cache\n\$HOME/.local/share/Trash\n\$HOME/.thumbnails\n\$HOME/.var/app/*/cache")
            assertThat(caches).isDisabled()
            assertThat(page.locator("#excludes-count")).hasText("5 patterns")
            page.locator("#excludes-caches").check()

            // a look at the dialog for who changes it, in build/ of sak-e2e
            page.screenshot(com.microsoft.playwright.Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("build/sakbackup-excludes-dialog.png")))
            // every button of the dialog is in the window
            for (button in listOf("#excludes-import", "#excludes-cancel", "#excludes-done")) {
                page.locator(button).scrollIntoViewIfNeeded()
                assertThat(page.locator(button)).isInViewport()
            }
            page.locator("#excludes-done").scrollIntoViewIfNeeded()
            page.screenshot(com.microsoft.playwright.Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("build/sakbackup-excludes-dialog-end.png")))

            // done: the form shows the new summary, save sends them with the plan
            page.locator("#excludes-done").click()
            assertThat(dialog).isHidden()
            assertThat(page.locator("#plan-excludes-summary")).hasText("5 patterns · cache folders left out")
            page.locator("#plan-submit").click()
            page.waitForFunction("() => window.__sakCalls.some((call) => call.command === 'update_plan')")
            val saved = page.evaluate("() => JSON.stringify(window.__sakCalls.find((call) => call.command === 'update_plan').args.plan)").toString()
            check(saved.contains("\"excludeCaches\":true") && saved.contains("### Caches and trash") && saved.contains("\"keepDaily\":7")) { saved }
        }
    }
}
