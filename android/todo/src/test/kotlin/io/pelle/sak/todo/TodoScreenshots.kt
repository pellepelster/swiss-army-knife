package io.pelle.sak.todo

import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import io.ktor.http.HttpStatusCode
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.api.model.TodoItemResponse.Priority.HIGH
import io.pelle.sak.api.model.TodoItemResponse.Priority.LOW
import io.pelle.sak.core.ui.SakContent
import io.pelle.sak.testing.sakSetup
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.Screenshots
import io.pelle.sak.testing.answerOn
import io.pelle.sak.testing.fakeCamera
import io.pelle.sak.testing.page
import io.pelle.sak.testing.settle
import io.pelle.sak.testing.signedIn
import io.pelle.sak.testing.signedOut
import io.pelle.sak.todo.ui.TodoApp
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

// the screenshots of the todo app (docs/screenshots/android-todo-*.png) against the fake backend; the
// scan, the home and a list also in landscape (-land). Only with mise run android:screenshots
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
            answers["GET /todo/lists"] =
                HttpStatusCode.OK to
                page(
                    list(1, "Home", shared = true, icon = "🏠", items = 5),
                    list(2, "Work", icon = "💼", items = 3),
                    list(3, "Garden", shared = true, mine = false, icon = "🌻", items = 2),
                )
            answers["GET /todo/lists/1/items"] =
                HttpStatusCode.OK to
                page(
                    item(1, 1, "Pay the taxes", "2026-05-31", HIGH),
                    item(2, 1, "Book the flights", priority = HIGH),
                    item(3, 1, "Fix the door"),
                    item(4, 1, "Buy a birthday gift for Anna", "2099-12-24"),
                    item(5, 1, "Tidy the attic", priority = LOW),
                    item(6, 1, "Water the plants", doneAt = DONE),
                    item(7, 1, "Call the plumber", priority = HIGH, doneAt = DONE),
                )
        }

    private fun TestScope.signedIn(): TodoModel {
        backend.answerOn(this)
        val stored = signedIn(now.plusSeconds(3600), email = "anna@example.org")
        return TodoModel(sakSetup(MemorySessionStore(stored), Clock.fixed(now, ZoneOffset.UTC)), backend::api).also { it.start() }
    }

    @Test
    fun lists() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
            screenshots.shoot("todo-lists") { settle(compose) }
        }

    @Test
    fun list() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
            settle(compose)
            model.open(1)
            screenshots.shoot("todo-list") { settle(compose) }
        }

    @Test
    fun signIn() =
        runTest {
            backend.answerOn(this)
            val model = TodoModel(sakSetup(MemorySessionStore(signedOut())), backend::api).also { it.start() }
            screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
            screenshots.shoot("todo-sign-in") { settle(compose) }
        }

    // the scan of the QR code of the web app, over the sign-in, the camera a fake
    private fun TestScope.scanning(): TodoModel {
        fakeCamera()
        backend.answerOn(this)
        val model = TodoModel(sakSetup(MemorySessionStore(signedOut())), backend::api).also { it.start() }
        screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
        settle(compose)
        model.qr.open()
        return model
    }

    @Test
    fun scan() =
        runTest {
            scanning()
            screenshots.shoot("todo-scan") { settle(compose) }
        }

    @Test
    @Config(qualifiers = "+land")
    fun scanLandscape() =
        runTest {
            scanning()
            screenshots.shoot("todo-scan-land") { settle(compose) }
        }

    @Test
    fun settings() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
            settle(compose)
            model.openSettings(1)
            screenshots.shoot("todo-list-settings") { settle(compose) }
        }

    // a phone in landscape
    @Test
    @Config(qualifiers = "+land")
    fun listsLandscape() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
            screenshots.shoot("todo-lists-land") { settle(compose) }
        }

    @Test
    @Config(qualifiers = "+land")
    fun listLandscape() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
            settle(compose)
            model.open(1)
            screenshots.shoot("todo-list-land") { settle(compose) }
        }

    // the date picker of a new task over the list, on a phone in landscape: its OK must be in reach
    @Test
    @Config(qualifiers = "+land")
    fun duePickerLandscape() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.todo_app_name)) { TodoApp(model) } }
            settle(compose)
            model.open(1)
            settle(compose)
            compose.onNodeWithTag("item-due").performClick()
            screenshots.shoot("todo-due-picker-land", screen = true) { settle(compose) }
        }
}
