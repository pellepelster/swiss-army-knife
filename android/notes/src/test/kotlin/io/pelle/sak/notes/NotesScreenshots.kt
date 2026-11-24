package io.pelle.sak.notes

import androidx.compose.ui.res.stringResource
import io.pelle.sak.core.ui.SakContent
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import io.ktor.http.HttpStatusCode
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.notes.ui.NotesApp
import io.pelle.sak.testing.sakSetup
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.Screenshots
import io.pelle.sak.testing.answerOn
import io.pelle.sak.testing.settle
import io.pelle.sak.testing.signedIn
import io.pelle.sak.testing.signedOut
import io.pelle.sak.testing.page
import kotlinx.coroutines.test.TestScope
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

// docs/screenshots/android-notes-*.png against the fake backend, the wall and the note also in
// landscape (-land); only with mise run android:screenshots
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
            answers["GET /notes"] =
                HttpStatusCode.OK to
                page(
                    note(6, "Call the plumber about the kitchen sink, Tuesday after 4 pm", "pink", listOf("home"), createdAt = "2026-11-01T07:00:00.000Z"),
                    note(5, "Gift ideas for Mia: a sketchbook, watercolors, the new book by her favorite author", "green", listOf("family"), createdAt = "2026-11-02T07:00:00.000Z"),
                    note(4, "Pick up the parcel at the post office", "yellow", createdAt = "2026-11-01T07:00:00.000Z"),
                    note(3, "Wi-Fi for guests: garden-house, the password is on the fridge", "blue", listOf("home"), createdAt = "2026-11-02T07:00:00.000Z"),
                    note(2, "Book the campsite for the long weekend in May", "orange", listOf("travel"), createdAt = "2026-11-01T07:00:00.000Z"),
                    note(1, "Renew the passport before the summer holidays", "yellow", listOf("travel"), createdAt = "2026-11-02T07:00:00.000Z"),
                )
            answers["GET /labels"] = HttpStatusCode.OK to page(label(1, "family"), label(2, "home"), label(3, "travel"))
            answers["GET /notes/labels"] = HttpStatusCode.OK to page(label(1, "family"), label(2, "home"), label(3, "travel"))
            answers["GET /notes/settings"] = HttpStatusCode.OK to notesSettings(discoverText = false)
        }

    private fun TestScope.model(stored: Stored): NotesModel {
        backend.answerOn(this)
        return NotesModel(sakSetup(MemorySessionStore(stored), Clock.fixed(now, ZoneOffset.UTC)), backend::api)
            .also { it.start() }
    }

    private fun TestScope.signedIn() =
        model(signedIn(now.plusSeconds(3600), email = "anna@example.org"))

    @Test
    fun signIn() =
        runTest {
            val model = model(signedOut())
            screenshots.show { SakContent(model, stringResource(R.string.notes_app_name)) { NotesApp(model) } }
            screenshots.shoot("notes-sign-in") { settle(compose) }
        }

    @Test
    fun notes() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.notes_app_name)) { NotesApp(model) } }
            screenshots.shoot("notes") { settle(compose) }
        }

    @Test
    fun note() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.notes_app_name)) { NotesApp(model) } }
            settle(compose)
            model.open(5)
            screenshots.shoot("notes-note") { settle(compose) }
        }

    // a phone in landscape
    @Test
    @Config(qualifiers = "+land")
    fun notesLandscape() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.notes_app_name)) { NotesApp(model) } }
            screenshots.shoot("notes-land") { settle(compose) }
        }

    @Test
    @Config(qualifiers = "+land")
    fun noteLandscape() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.notes_app_name)) { NotesApp(model) } }
            settle(compose)
            model.open(5)
            screenshots.shoot("notes-note-land") { settle(compose) }
        }
}
