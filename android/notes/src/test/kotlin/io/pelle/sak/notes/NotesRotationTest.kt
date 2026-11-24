package io.pelle.sak.notes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import io.ktor.http.HttpStatusCode
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.forActivities
import io.pelle.sak.testing.shows
import io.pelle.sak.testing.signedIn
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

// a rotation keeps what the model holds, a running undo included, and the screens fit in landscape
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class NotesRotationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val note = note(1, "Receipt", photos = listOf(40))

    private val backend =
        FakeBackend().apply {
            answers["GET /notes"] = HttpStatusCode.OK to page(note)
            answers["GET /labels"] = HttpStatusCode.OK to page()
            answers["GET /notes/labels"] = HttpStatusCode.OK to page()
            answers["GET /notes/settings"] = HttpStatusCode.OK to notesSettings(discoverText = false)
            answers["PUT /notes/1"] = HttpStatusCode.OK to note
        }

    @Before
    fun signedIn() {
        val until = Instant.now().plusSeconds(3600)
        backend.forActivities(signedIn(until, email = "anna@example.org"))
    }

    @Test
    fun `a photo taken off can be undone over a rotation, the wall and the note show in landscape`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.shows("note-1")
            scenario.rotate()
            compose.onNodeWithTag("note-1").assertIsDisplayed()
            compose.onNodeWithTag("note-1").performClick()
            compose.shows("remove-photo-40")
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

    @Test
    fun `the menu of a note is named by its text and asks before the delete, the arrow of a note leads back`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.shows("note-1")
            compose.onNodeWithContentDescription("Menu of Receipt").assertIsDisplayed()
            compose.onNodeWithTag("menu-note-1").performClick()
            compose.onNodeWithTag("delete-note-1").performClick()
            compose.onNodeWithText("Keep").performClick()
            assertTrue(backend.requests.none { it.startsWith("DELETE") })

            compose.onNodeWithTag("note-1").performClick()
            compose.shows("back")
            compose.onNodeWithTag("note-menu").performClick()
            compose.onNodeWithTag("delete-note-1").assertIsDisplayed()
            compose.onNodeWithText("Keep").assertDoesNotExist()
            compose.onNodeWithTag("delete-note-1").performClick()
            compose.onNodeWithText("Keep").performClick()
            compose.onNodeWithTag("back").performClick()
            compose.shows("note-1")
        }
    }
}
