package io.pelle.sak.todo

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import io.ktor.http.HttpStatusCode
import io.pelle.sak.core.session.withOpenList
import io.pelle.sak.testing.connectToken
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.fakeCamera
import io.pelle.sak.testing.ADDRESS
import io.pelle.sak.testing.forActivities
import io.pelle.sak.testing.page
import io.pelle.sak.testing.rotate
import io.pelle.sak.testing.session
import io.pelle.sak.testing.shows
import io.pelle.sak.testing.signedIn
import io.pelle.sak.testing.signedOut
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

// the app in every orientation: the activity recreated by a rotation keeps what the model holds, an
// undo that runs included, and the screens fit a phone in landscape
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class TodoRotationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val backend =
        FakeBackend().apply {
            answers["GET /todo/lists"] = HttpStatusCode.OK to page(list(1, "Home", icon = "🏠", items = 1))
            answers["GET /todo/lists/1/items"] = HttpStatusCode.OK to page(item(10, 1, "Fix the door", doneAt = DONE), item(11, 1, "Paint the fence"))
            answers["POST /todo/lists/1/purge"] = HttpStatusCode.NoContent to ""
        }

    @Before
    fun signedIn() {
        val until = Instant.now().plusSeconds(3600)
        backend.forActivities(signedIn(until, email = "anna@example.org").withOpenList(1))
    }

    @Test
    fun `a purge that can be undone stays over a rotation, undo brings the task back in landscape`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.shows("item-10")
            compose.onNodeWithTag("purge").performClick()
            compose.onNodeWithTag("undo").assertIsDisplayed()

            scenario.rotate()
            compose.onNodeWithTag("undo").assertIsDisplayed()
            compose.onNodeWithTag("item-title").assertIsDisplayed()
            assertTrue(backend.requests.none { it.contains("/purge") })
            compose.onNodeWithTag("undo-action").performClick()
            compose.shows("item-10")

            scenario.rotate(landscape = false)
            compose.onNodeWithTag("item-10").assertIsDisplayed()
            assertTrue(backend.requests.none { it.contains("/purge") })
        }
    }

    @Test
    fun `the date picker, the home and the settings with the emoji picker fit a phone in landscape`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.shows("item-10")
            scenario.rotate()
            compose.onNodeWithTag("item-due").performClick()
            compose.onNodeWithTag("due-picker-ok").assertIsDisplayed()
            compose.onNodeWithTag("due-picker-ok").performClick()

            compose.onNodeWithTag("list-settings").performClick()
            compose.onNodeWithTag("delete-list").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("save-list").assertIsDisplayed()
            compose.onNodeWithTag("choose-icon").performScrollTo().performClick()
            compose.onNodeWithTag("emoji-picker").assertIsDisplayed()
            scenario.rotate(landscape = false)
            compose.onNodeWithTag("list-name").assertIsDisplayed()

            scenario.rotate()
            compose.onNodeWithTag("back").performClick()
            compose.onNodeWithTag("back").performClick()
            compose.shows("list-1")
            compose.onNodeWithTag("list-1").assertIsDisplayed()
            compose.onNodeWithTag("new-list").assertIsDisplayed()
        }
    }

    @Test
    fun `the scan of a QR code stays over a rotation, fits a phone in landscape and signs in`() {
        backend.answers["POST /auth/login/connect"] =
            HttpStatusCode.OK to session("qr", "anna@example.org", "2099-01-01T00:00:00.000Z")
        backend.forActivities(signedOut())
        val camera = fakeCamera()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.shows("scan-qr")
            compose.onNodeWithTag("scan-qr").performClick()
            compose.onNodeWithTag("qr-scanner").assertIsDisplayed()

            scenario.rotate()
            compose.onNodeWithTag("qr-scanner").assertIsDisplayed()
            compose.onNodeWithTag("qr-back").assertIsDisplayed()
            compose.onNodeWithTag("qr-point").assertIsDisplayed()
            compose.onNodeWithTag("qr-torch").assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitForIdle()
            assertTrue(camera.torch)

            scenario.rotate(landscape = false)
            compose.onNodeWithTag("qr-scanner").assertIsDisplayed()
            camera.code = "$ADDRESS/connect#token=${connectToken()}"
            // asked first, with the address of the code (SECURITY-3)
            compose.shows("qr-confirm")
            assertTrue(backend.requests.none { it.startsWith("POST /auth/login/connect") })
            compose.onNodeWithTag("qr-confirm").performClick()
            compose.shows("list-1")
            assertTrue(backend.requests.contains("""POST /auth/login/connect {"token":"${connectToken()}"}"""))
            compose.onAllNodesWithTag("qr-scanner").assertCountEquals(0)
        }
    }

    @Test
    fun `back closes the scan, the sign-in below it is as it was`() {
        backend.forActivities(signedOut())
        fakeCamera()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.shows("scan-qr")
            compose.onNodeWithTag("scan-qr").performClick()
            scenario.rotate()
            compose.onNodeWithTag("qr-back").performClick()
            compose.onAllNodesWithTag("qr-scanner").assertCountEquals(0)
            compose.onNodeWithTag("use-password").performScrollTo().assertIsDisplayed()
        }
    }
}
