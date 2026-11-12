package io.pelle.sak.todo

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import io.ktor.http.HttpStatusCode
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.ui.SakContent
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.Screenshots
import io.pelle.sak.testing.URL
import io.pelle.sak.testing.fakeCamera
import io.pelle.sak.testing.page
import io.pelle.sak.todo.ui.TodoApp
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

// the screenshots of the todo app (docs/screenshots/android-todo-*.png): the sign-in, the scan of the
// QR code that connects the app, the home with the lists, a list with its tasks of every priority,
// with and without a due date, open and done, and the settings of a list, against the fake backend;
// the scan, the home and a list also in landscape (-land).
// Only with mise run android:screenshots
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class TodoScreenshots {
    @get:Rule
    val compose = createComposeRule()

    private val screenshots = Screenshots(compose)

    private val now = Instant.parse("2026-11-12T08:00:00Z")

    private val backend =
        FakeBackend().apply {
            answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"}"""
            answers["GET /todo/lists"] =
                HttpStatusCode.OK to
                page(
                    list(1, "Home", "🏠", items = 5, shared = true),
                    list(2, "Work", "💼", items = 3),
                    """{"id":3,"name":"Garden","icon":"🌻","shared":true,"mine":false,"items":2,"createdAt":"2026-11-10T08:00:00.000Z"}""",
                )
            answers["GET /todo/lists/1/items"] =
                HttpStatusCode.OK to
                page(
                    item(1, "Pay the taxes", "high", "2026-05-31"),
                    item(2, "Book the flights", "high"),
                    item(3, "Fix the door", "normal"),
                    item(4, "Buy a birthday gift for Anna", "normal", "2099-12-24"),
                    item(5, "Tidy the attic", "low"),
                    item(6, "Water the plants", "normal", done = true),
                    item(7, "Call the plumber", "high", done = true),
                )
        }

    private fun list(
        id: Int,
        name: String,
        icon: String,
        items: Int,
        shared: Boolean = false,
    ) = """{"id":$id,"name":"$name","icon":"$icon","shared":$shared,"mine":true,"items":$items,"createdAt":"2026-11-10T08:00:00.000Z"}"""

    private fun item(
        id: Int,
        title: String,
        priority: String,
        dueDate: String? = null,
        done: Boolean = false,
    ) = """{"id":$id,"listId":1,"title":"$title","priority":"$priority","createdAt":"2026-11-10T08:00:00.000Z"${dueDate?.let { ",\"dueDate\":\"$it\"" } ?: ""}${if (done) ",\"doneAt\":\"2026-11-11T08:00:00Z\"" else ""}}"""

    private fun TestScope.signedIn(): TodoModel {
        backend.dispatcher = StandardTestDispatcher(testScheduler)
        val stored = Stored(apiUrl = URL, address = "https://sak.example.org", token = "t", email = "anna@example.org", expiresAt = now.plusSeconds(3600))
        return TodoModel(MemorySessionStore(stored), backend::api, this, allowUrl = false, clock = Clock.fixed(now, ZoneOffset.UTC)).also { it.start() }
    }

    // what the app and compose started is done
    private fun TestScope.settle() {
        repeat(3) {
            advanceUntilIdle()
            compose.waitForIdle()
        }
    }

    @Test
    fun lists() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
            screenshots.shoot("todo-lists") { settle() }
        }

    @Test
    fun list() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
            settle()
            model.open(1)
            screenshots.shoot("todo-list") { settle() }
        }

    @Test
    fun signIn() =
        runTest {
            backend.dispatcher = StandardTestDispatcher(testScheduler)
            val model = TodoModel(MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org")), backend::api, this, allowUrl = false).also { it.start() }
            screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
            screenshots.shoot("todo-sign-in") { settle() }
        }

    // the scan of the QR code of the web app, over the sign-in, the camera a fake
    private fun TestScope.scanning(): TodoModel {
        fakeCamera()
        backend.dispatcher = StandardTestDispatcher(testScheduler)
        val model = TodoModel(MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org")), backend::api, this, allowUrl = false).also { it.start() }
        screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
        settle()
        model.scanQr()
        return model
    }

    @Test
    fun scan() =
        runTest {
            scanning()
            screenshots.shoot("todo-scan") { settle() }
        }

    @Test
    @Config(qualifiers = "+land")
    fun scanLandscape() =
        runTest {
            scanning()
            screenshots.shoot("todo-scan-land") { settle() }
        }

    @Test
    fun settings() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
            settle()
            model.openSettings(1)
            screenshots.shoot("todo-list-settings") { settle() }
        }

    // a phone in landscape
    @Test
    @Config(qualifiers = "+land")
    fun listsLandscape() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
            screenshots.shoot("todo-lists-land") { settle() }
        }

    @Test
    @Config(qualifiers = "+land")
    fun listLandscape() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
            settle()
            model.open(1)
            screenshots.shoot("todo-list-land") { settle() }
        }
}
