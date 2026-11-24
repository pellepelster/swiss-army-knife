package io.pelle.sak.e2e.window

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.pelle.sak.e2e.util.WINDOW_FIXTURES
import io.pelle.sak.e2e.util.openWindow
import io.pelle.sak.e2e.util.window
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files

// the window of sakadmin applies the plan it showed, by its number, and asks first when the plan
// deletes or replaces something (SAKADMIN-10, SAKADMIN-15; DA-26, DA-27). `mise run backup:test:window`
@Tag("window")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SakadminPlanTest {
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
    fun `a plan that deletes or replaces asks first, and the apply names the plan it showed`() {
        // the fixtures of the tour, the plan of the create one that replaces the server
        val fixtures = Json.parseToJsonElement(Files.readString(WINDOW_FIXTURES)).jsonObject
        val answers = JsonObject(fixtures.getValue("answers").jsonObject + ("plan_deployment" to JsonArray(listOf(fixtures.getValue("replacingPlan")))))
        val file = Files.createTempFile("sakadmin-replacing", ".json")
        Files.writeString(file, JsonObject(fixtures + ("answers" to answers)).toString())
        browser.window(fixtures = file).use { context ->
            val page = context.openWindow()
            page.locator("#providers-add").click()
            page.locator("#provider-types button").first().click()
            page.locator("#token-input").fill("kGq7XnL2cVb9WmR4tYp8sZ1aD6fH3jK5eQ0uI2oP7lN9xC4vB8mT1rE6yU3wA5sD")
            page.locator("#token-add").click()
            page.locator("#providers-next").click()
            page.locator("#deployments-create").click()
            page.locator("#create-name").fill("family")
            page.locator("#create-password").fill("correct horse battery staple")
            page.locator("#create-password-repeat").fill("correct horse battery staple")
            page.locator("#create-root-domain").selectOption("krawallbu.de")
            page.locator("#create-dns-mode-subdomain").check()
            page.locator("#create-user-mode-single").check()
            page.locator("#create-plan").click()
            assertThat(page.locator("#view-plan")).isVisible()

            // asked, with what goes; no is no apply
            page.locator("#plan-deploy").click()
            val dialog = page.locator("#confirm-dialog")
            assertThat(dialog).isVisible()
            assertThat(page.locator("#confirm-dialog-title")).hasText("Deploy family?")
            assertThat(page.locator("#confirm-dialog-message")).hasText("This plan deletes or replaces: server sak-family.")
            page.locator("#confirm-dialog-cancel").click()
            assertThat(dialog).isHidden()
            assertThat(page.locator("#view-plan")).isVisible()
            check(page.evaluate("() => window.__sakCalls.some((call) => call.command === 'apply_checked')") == false)

            // yes: the apply of the plan the page showed, by its number
            page.locator("#plan-deploy").click()
            page.locator("#confirm-dialog-submit").click()
            assertThat(page.locator("#view-run")).isVisible()
            page.waitForFunction("() => window.__sakCalls.some((call) => call.command === 'apply_checked')")
            val deploy = page.evaluate("() => JSON.stringify(window.__sakCalls.find((call) => call.command === 'apply_checked').args.deploy)").toString()
            check(deploy == """{"check":3,"force":false}""") { deploy }
        }
        Files.deleteIfExists(file)
    }

    @Test
    fun `closing the window while a SAK instance changes asks first, and only a yes quits`() {
        browser.window().use { context ->
            // the events of tauri: the listeners of the page, which the test calls as rust would
            context.addInitScript(
                "window.__sakListeners = {}; window.__TAURI__.event = { listen: async (name, listener) => { window.__sakListeners[name] = listener; } };",
            )
            val page = context.openWindow()
            // the window is up with its texts, as when a create runs
            assertThat(page.locator("#view-providers")).isVisible()
            // every view has the icon of the app before its title, and the footer is below them (DESKTOP-4)
            assertThat(page.locator(".view .page-title > img.app-icon")).hasCount(10)
            assertThat(page.locator("#view-providers .page-title > img.app-icon")).isVisible()
            assertThat(page.locator("footer.app-footer #update-button")).hasText("Check for updates")
            page.waitForFunction("() => window.__sakListeners['close-requested'] !== undefined")
            val quits = "() => window.__sakCalls.some((call) => call.command === 'quit_working')"
            page.evaluate("() => { window.__sakListeners['close-requested'](); }")
            assertThat(page.locator("#confirm-dialog-title")).hasText("Quit sakadmin?")
            assertThat(page.locator("#confirm-dialog-message")).containsText("Quitting stops it where it is")
            assertThat(page.locator("#confirm-dialog-cancel")).hasText("Keep running")
            page.locator("#confirm-dialog-cancel").click()
            assertThat(page.locator("#confirm-dialog")).isHidden()
            check(page.evaluate(quits) == false)
            page.evaluate("() => { window.__sakListeners['close-requested'](); }")
            page.locator("#confirm-dialog-submit").click()
            page.waitForFunction(quits)
        }
    }
}
