package io.pelle.sak.notes

import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import io.ktor.http.HttpStatusCode
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.notes.ui.NotesApp
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.Screenshots
import io.pelle.sak.testing.URL
import io.pelle.sak.testing.page
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// the screenshots of the notes app (docs/screenshots/android-notes-*.png): the sign-in, the wall of
// the notes and a note open, against the fake backend; only with mise run android:screenshots
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class NotesScreenshots {
    @get:Rule
    val compose = createComposeRule()

    private val screenshots = Screenshots(compose)

    private val now = Instant.parse("2026-11-02T08:00:00Z")

    private val backend =
        FakeBackend().apply {
            answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"}"""
            answers["GET /notes"] =
                HttpStatusCode.OK to
                page(
                    note(6, "Call the plumber about the kitchen sink, Tuesday after 4 pm", "pink", "home"),
                    note(5, "Gift ideas for Mia: a sketchbook, watercolors, the new book by her favorite author", "green", "family"),
                    note(4, "Pick up the parcel at the post office", "yellow"),
                    note(3, "Wi-Fi for guests: garden-house, the password is on the fridge", "blue", "home"),
                    note(2, "Book the campsite for the long weekend in May", "orange", "travel"),
                    note(1, "Renew the passport before the summer holidays", "yellow", "travel"),
                )
            answers["GET /labels"] = HttpStatusCode.OK to page(label(1, "family"), label(2, "home"), label(3, "travel"))
            answers["GET /notes/labels"] = HttpStatusCode.OK to page(label(1, "family"), label(2, "home"), label(3, "travel"))
            answers["GET /notes/settings"] = HttpStatusCode.OK to """{"discoverText":false}"""
        }

    private fun label(
        id: Int,
        name: String,
    ) = """{"id":$id,"name":"$name","createdAt":"2026-10-30T08:00:00.000Z"}"""

    private fun note(
        id: Int,
        text: String,
        color: String,
        vararg labels: String,
    ) = """{"id":$id,"text":"$text","color":"$color","labels":[${labels.mapIndexed { i, l -> label(i + 1, l) }.joinToString(",")}],""" +
        """"photos":[],"recordings":[],"createdAt":"2026-11-0${id % 2 + 1}T07:00:00.000Z","updatedAt":null}"""

    private fun TestScope.model(stored: Stored): NotesModel {
        backend.dispatcher = StandardTestDispatcher(testScheduler)
        return NotesModel(MemorySessionStore(stored), backend::api, this, allowUrl = false, clock = Clock.fixed(now, ZoneOffset.UTC))
            .also { it.start() }
    }

    private fun TestScope.signedIn() =
        model(Stored(apiUrl = URL, deployment = "family", token = "t", email = "anna@example.org", expiresAt = now.plusSeconds(3600)))

    // what the app and compose started is done
    private fun TestScope.settle() {
        repeat(3) {
            advanceUntilIdle()
            compose.waitForIdle()
        }
    }

    @Test
    fun signIn() =
        runTest {
            val model = model(Stored(apiUrl = URL, deployment = "family"))
            screenshots.show { NotesApp(model) }
            screenshots.shoot("notes-sign-in") { settle() }
        }

    @Test
    fun notes() =
        runTest {
            val model = signedIn()
            screenshots.show { NotesApp(model) }
            screenshots.shoot("notes") { settle() }
        }

    @Test
    fun note() =
        runTest {
            val model = signedIn()
            screenshots.show { NotesApp(model) }
            settle()
            model.open(5)
            screenshots.shoot("notes-note") { settle() }
        }
}
