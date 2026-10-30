package io.pelle.sak.e2e.util

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole

// the users of a deployment: one admin who signs in with the password, or the admin and users who
// sign in by e-mail (the admin also with the password)
enum class UserMode { SINGLE, MULTI }

// a deployment the tests share (TestDeployments): what it is, how sakctl manages it, where its
// frontend is. Its server answers under its names at `ipv4`, the browser of the tests asks it there
// directly (IntegrationBrowser)
class Deployment(
    val name: String,
    val userMode: UserMode,
    val dnsMode: String,
    val rootDomain: String,
    val password: String,
    val adminEmail: String,
    val sakctl: Sakctl,
) {
    /** the exit code of `sakctl create`; the tests of a deployment that was not created fail */
    var created: Int = -1

    /** the public ipv4 of its server, once it is created */
    var ipv4: String = ""

    /** the base domain of its names: the root domain, or `<name>.<root domain>` */
    val baseDomain: String = if (dnsMode == "domain") rootDomain else "$name.$rootDomain"

    val frontendUrl: String = "https://sak.$baseDomain"

    override fun toString(): String = userMode.name.lowercase()

    fun requireCreated() {
        check(created == 0) { "the $this deployment $name was not created (sakctl create ended with $created)" }
    }

    /** signs the admin in with the password and waits for the apps */
    fun signIn(page: Page) {
        step("signing in with the password at $frontendUrl", this)
        page.navigate("/login")
        val usePassword = page.getByTestId("use-password")
        val password = page.getByLabel("Admin password")
        // the e-mail login comes first on a multi user deployment
        page.waitForSelector("#password, [data-testid='use-password']")
        if (usePassword.isVisible) usePassword.click()
        password.fill(this.password)
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign in")).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Apps"))).isVisible()
        step("signed in", this)
    }
}

// the notes app of a signed in page: a note is its text, the value of its textarea
object NotesPage {
    // a note is saved a moment after the last key, a delete goes to the backend after its undo
    private const val SAVED_AFTER_MS = 1_500.0
    private const val UNDO_MS = 7_000.0

    /** the notes page once its notes are loaded: a note, or the text of a page without notes */
    fun open(page: Page) {
        step("opening the notes, waiting until they are loaded")
        page.navigate("/notes")
        assertThat(page.locator("button[aria-label='New note']:visible").first()).isVisible()
        page.waitForFunction(
            "() => document.querySelector(\"[data-testid^='note-']\") !== null || document.body.innerText.includes('No notes yet')",
        )
    }

    /** waits until the note with `text` is on the page */
    private fun await(
        page: Page,
        text: String,
    ) {
        page.waitForFunction(
            "text => [...document.querySelectorAll(\"[data-testid^='note-'] textarea\")].some(area => area.value === text)",
            text,
            Page.WaitForFunctionOptions().setTimeout(15_000.0),
        )
    }

    /** the texts of the notes on the page */
    @Suppress("UNCHECKED_CAST")
    fun texts(page: Page): List<String> =
        page.locator("[data-testid^='note-'] textarea").evaluateAll("areas => areas.map(area => area.value)") as List<String>

    /** a new text note, saved: the notes opened again have it */
    fun create(
        page: Page,
        text: String,
    ) {
        step("creating the note '$text', waiting until it is saved")
        val newest = page.locator("[data-testid^='note-'] textarea").first()
        page.locator("button[aria-label='New note']:visible").first().click()
        newest.fill(text)
        page.waitForTimeout(SAVED_AFTER_MS)
        open(page)
        await(page, text)
    }

    /** deletes the note with `text` and waits until its undo is over, so the backend deleted it */
    fun delete(
        page: Page,
        text: String,
    ) {
        await(page, text)
        val card =
            page.evaluate(
                "text => [...document.querySelectorAll(\"[data-testid^='note-']\")]" +
                    ".find(card => card.querySelector('textarea')?.value === text)?.dataset.testid",
                text,
            ) as String? ?: error("no note '$text' on the page")
        step("deleting the note '$text', waiting ${(UNDO_MS / 1000).toInt()} s until its undo is over")
        page.locator("[data-testid='$card'] button[title='Delete']").click()
        page.waitForTimeout(UNDO_MS + 1_000.0)
    }
}
