package io.pelle.sak.grocery

import androidx.compose.ui.test.assertIsDisplayed
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
class GroceryRotationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val backend =
        FakeBackend().apply {
            answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"}"""
            answers["GET /grocery/lists"] =
                HttpStatusCode.OK to page("""{"id":1,"name":"Weekly shop","icon":"🛒","shared":false,"mine":true,"items":2,"createdAt":"2026-10-30T08:00:00.000Z"}""")
            answers["GET /grocery/lists/1/categories"] = HttpStatusCode.OK to page("""{"id":1,"name":"Bakery","icon":"🥖"}""")
            answers["GET /grocery/lists/1/items"] =
                HttpStatusCode.OK to
                page(
                    """{"id":1,"listId":1,"name":"Apples","amount":1.0,"unit":"kg","categoryId":null,"createdAt":"2026-10-30T08:00:00.000Z"}""",
                    """{"id":2,"listId":1,"name":"Bread","categoryId":1,"createdAt":"2026-10-30T08:00:00.000Z"}""",
                )
            answers["GET /grocery/settings"] = HttpStatusCode.OK to """{"discoverItems":false}"""
            answers["POST /grocery/items/1/check"] = HttpStatusCode.NoContent to ""
        }

    @Before
    fun signedIn() {
        val until = Instant.now().plusSeconds(3600)
        backend.forActivities(Stored(apiUrl = URL, address = "https://sak.example.org", token = "t", email = "anna@example.org", expiresAt = until).withOpenList(1))
    }

    private fun shows(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `a check that can be undone stays over a rotation, undo takes it back in landscape`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shows("item-1")
            compose.onNodeWithTag("item-1").performClick()
            compose.onNodeWithTag("undo").assertIsDisplayed()

            scenario.rotate()
            compose.onNodeWithTag("undo").assertIsDisplayed()
            compose.onNodeWithTag("item-name").assertIsDisplayed()
            assertTrue(backend.requests.none { it.contains("/check") })
            compose.onNodeWithTag("undo-action").performClick()
            compose.onNodeWithTag("item-1").assertIsDisplayed()

            scenario.rotate(landscape = false)
            compose.onNodeWithTag("item-1").assertIsDisplayed()
            assertTrue(backend.requests.none { it.contains("/check") })
        }
    }

    @Test
    fun `the settings with their categories and the emoji picker, and the home fit a phone in landscape`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shows("item-1")
            scenario.rotate()
            compose.onNodeWithTag("list-settings").performClick()
            compose.onNodeWithTag("save-list").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("delete-list").assertIsDisplayed()
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
}
