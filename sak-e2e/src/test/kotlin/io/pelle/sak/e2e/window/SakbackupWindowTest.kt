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
}
