package io.pelle.sak.notes

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

// the app in every orientation: the activity made anew by a rotation keeps what the model holds, an
// undo that runs included, and the screens fit a phone in landscape
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class NotesRotationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val note =
        """{"id":1,"text":"Receipt","color":"yellow","labels":[],""" +
            """"photos":[{"id":40,"url":"/api/v1/files/40","thumbnailUrl":"/api/v1/files/40/thumbnail"}],"recordings":[],""" +
            """"createdAt":"2026-11-02T07:00:00.000Z","updatedAt":null}"""

    private val backend =
        FakeBackend().apply {
            answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"}"""
            answers["GET /notes"] = HttpStatusCode.OK to page(note)
            answers["GET /labels"] = HttpStatusCode.OK to page()
            answers["GET /notes/labels"] = HttpStatusCode.OK to page()
            answers["GET /notes/settings"] = HttpStatusCode.OK to """{"discoverText":false}"""
            answers["PUT /notes/1"] = HttpStatusCode.OK to note
        }

    @Before
    fun signedIn() {
        val until = Instant.now().plusSeconds(3600)
        backend.forActivities(Stored(apiUrl = URL, address = "https://sak.example.org", token = "t", email = "anna@example.org", expiresAt = until))
    }

    private fun shows(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `a photo taken off can be undone over a rotation, the wall and the note show in landscape`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shows("note-1")
            scenario.rotate()
            compose.onNodeWithTag("note-1").assertIsDisplayed()
            compose.onNodeWithTag("note-1").performClick()
            shows("remove-photo-40")
            // the photos are below the text, the note scrolls to them in landscape
            compose.onNodeWithTag("remove-photo-40").performScrollTo().performClick()
            compose.onNodeWithTag("undo").assertIsDisplayed()

            scenario.rotate(landscape = false)
            compose.onNodeWithTag("undo").assertIsDisplayed()
            assertTrue(backend.requests.none { it.startsWith("PUT /notes/1") })
            compose.onNodeWithTag("undo-action").performClick()
            compose.onNodeWithTag("remove-photo-40").performScrollTo().assertIsDisplayed()

            scenario.rotate()
            compose.onNodeWithTag("text").assertIsDisplayed()
            assertTrue(backend.requests.none { it.startsWith("PUT /notes/1") })
        }
    }
}
