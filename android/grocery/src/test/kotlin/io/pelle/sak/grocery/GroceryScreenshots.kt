package io.pelle.sak.grocery

import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import io.ktor.http.HttpStatusCode
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.grocery.ui.GroceryApp
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

// the screenshots of the grocery app (docs/screenshots/android-grocery-*.png): the sign-in, the home
// with the lists, a list with its items by category and the settings of a list, against the fake
// backend; only with mise run android:screenshots
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class GroceryScreenshots {
    @get:Rule
    val compose = createComposeRule()

    private val screenshots = Screenshots(compose)

    private val now = Instant.parse("2026-11-02T08:00:00Z")

    private val backend =
        FakeBackend().apply {
            answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"}"""
            answers["GET /grocery/lists"] =
                HttpStatusCode.OK to
                page(
                    list(1, "Weekly shop", "🛒", items = 9, shared = true),
                    list(2, "Garden party", "🎉", items = 4),
                    list(3, "Hardware store", "🔧", items = 2),
                    """{"id":4,"name":"Camping trip","icon":"⛺","shared":true,"mine":false,"items":6,"createdAt":"2026-10-30T08:00:00.000Z"}""",
                )
            answers["GET /grocery/lists/1/categories"] =
                HttpStatusCode.OK to
                page(
                    category(1, "Fruit & vegetables", "🥦"),
                    category(2, "Bakery", "🥖"),
                    category(3, "Dairy & eggs", "🧀"),
                    category(4, "Meat & fish", "🥩"),
                    category(5, "Pantry", "🥫"),
                    category(6, "Frozen", "🧊"),
                    category(7, "Drinks", "🥤"),
                    category(8, "Household", "🧻"),
                    category(9, "Other", "🛒"),
                )
            answers["GET /grocery/lists/1/items"] =
                HttpStatusCode.OK to
                page(
                    item(1, "Apples", 1, 1.0, "kg"),
                    item(2, "Tomatoes", 1, 6.0, null),
                    item(3, "Basil", 1),
                    item(4, "Sourdough bread", 2),
                    item(5, "Milk", 3, 2.0, "l"),
                    item(6, "Eggs", 3, 10.0, null),
                    item(7, "Coffee beans", 5, 500.0, "g"),
                    item(8, "Sparkling water", 7, 6.0, null),
                    item(9, "Dish soap", 8),
                )
        }

    private fun list(
        id: Int,
        name: String,
        icon: String,
        items: Int,
        shared: Boolean = false,
    ) = """{"id":$id,"name":"$name","icon":"$icon","shared":$shared,"mine":true,"items":$items,"createdAt":"2026-10-30T08:00:00.000Z"}"""

    private fun category(
        id: Int,
        name: String,
        icon: String,
    ) = """{"id":$id,"name":"$name","icon":"$icon"}"""

    private fun item(
        id: Int,
        name: String,
        categoryId: Int,
        amount: Double? = null,
        unit: String? = null,
    ) = """{"id":$id,"listId":1,"name":"$name","amount":$amount,"unit":${unit?.let { "\"$it\"" }},"categoryId":$categoryId,"createdAt":"2026-10-30T08:00:00.000Z"}"""

    private fun TestScope.model(stored: Stored): GroceryModel {
        backend.dispatcher = StandardTestDispatcher(testScheduler)
        return GroceryModel(MemorySessionStore(stored), backend::api, this, allowUrl = false, clock = Clock.fixed(now, ZoneOffset.UTC))
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
            screenshots.show { GroceryApp(model) }
            screenshots.shoot("grocery-sign-in") { settle() }
        }

    @Test
    fun lists() =
        runTest {
            val model = signedIn()
            screenshots.show { GroceryApp(model) }
            screenshots.shoot("grocery-lists") { settle() }
        }

    @Test
    fun list() =
        runTest {
            val model = signedIn()
            screenshots.show { GroceryApp(model) }
            settle()
            model.open(1)
            screenshots.shoot("grocery-list") { settle() }
        }

    @Test
    fun settings() =
        runTest {
            val model = signedIn()
            screenshots.show { GroceryApp(model) }
            settle()
            model.openSettings(1)
            screenshots.shoot("grocery-list-settings") { settle() }
        }
}
