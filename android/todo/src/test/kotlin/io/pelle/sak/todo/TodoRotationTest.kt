package io.pelle.sak.todo

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import io.ktor.http.HttpStatusCode
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.session.withOpenList
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.fakeCamera
import io.pelle.sak.testing.URL
import io.pelle.sak.testing.forActivities
import io.pelle.sak.testing.page
import io.pelle.sak.testing.rotate
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
            answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"}"""
            answers["GET /todo/lists"] =
                HttpStatusCode.OK to page("""{"id":1,"name":"Home","icon":"🏠","shared":false,"mine":true,"items":1,"createdAt":"2026-11-10T08:00:00.000Z"}""")
            answers["GET /todo/lists/1/items"] =
                HttpStatusCode.OK to
                page(
                    """{"id":10,"listId":1,"title":"Fix the door","priority":"normal","createdAt":"2026-11-10T08:00:00.000Z","doneAt":"2026-11-11T08:00:00Z"}""",
                    """{"id":11,"listId":1,"title":"Paint the fence","priority":"normal","createdAt":"2026-11-10T08:00:00.000Z"}""",
                )
            answers["POST /todo/lists/1/purge"] = HttpStatusCode.NoContent to ""
        }

    @Before
    fun signedIn() {
        val until = Instant.now().plusSeconds(3600)
        backend.forActivities(Stored(apiUrl = URL, address = "https://sak.example.org", token = "t", email = "anna@example.org", expiresAt = until).withOpenList(1))
    }

    private fun shows(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `a purge that can be undone stays over a rotation, undo brings the task back in landscape`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shows("item-10")
            compose.onNodeWithTag("purge").performClick()
            compose.onNodeWithTag("undo").assertIsDisplayed()

            scenario.rotate()
            compose.onNodeWithTag("undo").assertIsDisplayed()
            compose.onNodeWithTag("item-title").assertIsDisplayed()
            assertTrue(backend.requests.none { it.contains("/purge") })
            compose.onNodeWithTag("undo-action").performClick()
            shows("item-10")

            scenario.rotate(landscape = false)
            compose.onNodeWithTag("item-10").assertIsDisplayed()
            assertTrue(backend.requests.none { it.contains("/purge") })
        }
    }

    @Test
    fun `the date picker, the home and the settings with the emoji picker fit a phone in landscape`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shows("item-10")
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
            shows("list-1")
            compose.onNodeWithTag("list-1").assertIsDisplayed()
            compose.onNodeWithTag("new-list").assertIsDisplayed()
        }
    }

    @Test
    fun `the scan of a QR code stays over a rotation, fits a phone in landscape and signs in`() {
        backend.answers["POST /auth/login/connect"] =
            HttpStatusCode.OK to """{"sessionToken":"qr","email":"anna@example.org","expiresAt":"2099-01-01T00:00:00.000Z","roles":["user"],"redirect":"/"}"""
        backend.forActivities(Stored(apiUrl = URL, address = "https://sak.example.org"))
        val camera = fakeCamera()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shows("scan-qr")
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
            camera.code = "https://sak.example.org/connect#token=the.token"
            shows("list-1")
            assertTrue(backend.requests.contains("""POST /auth/login/connect {"token":"the.token"}"""))
            compose.onAllNodesWithTag("qr-scanner").assertCountEquals(0)
        }
    }

    @Test
    fun `back closes the scan, the sign-in below it is as it was`() {
        backend.forActivities(Stored(apiUrl = URL, address = "https://sak.example.org"))
        fakeCamera()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shows("scan-qr")
            compose.onNodeWithTag("scan-qr").performClick()
            scenario.rotate()
            compose.onNodeWithTag("qr-back").performClick()
            compose.onAllNodesWithTag("qr-scanner").assertCountEquals(0)
            compose.onNodeWithTag("use-password").performScrollTo().assertIsDisplayed()
        }
    }
}
