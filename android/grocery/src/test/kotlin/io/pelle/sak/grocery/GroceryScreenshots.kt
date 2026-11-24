package io.pelle.sak.grocery

import androidx.compose.ui.res.stringResource
import io.pelle.sak.core.ui.SakContent
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import io.ktor.http.HttpStatusCode
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.Stored
import io.pelle.sak.grocery.ui.GroceryApp
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

// the screenshots of the grocery app (docs/screenshots/android-grocery-*.png): the sign-in, the home
// with the lists, a list with its items by category and the settings of a list, against the fake
// backend, the home and a list also in landscape (-land); only with mise run android:screenshots
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
            answers["GET /grocery/lists"] =
                HttpStatusCode.OK to
                page(
                    list(1, "Weekly shop", shared = true, icon = "🛒", items = 9),
                    list(2, "Garden party", icon = "🎉", items = 4),
                    list(3, "Hardware store", icon = "🔧", items = 2),
                    list(4, "Camping trip", shared = true, mine = false, icon = "⛺", items = 6),
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
                    item(1, 1, "Apples", 1, 1.0, "kg"),
                    item(2, 1, "Tomatoes", 1, 6.0, null),
                    item(3, 1, "Basil", 1),
                    item(4, 1, "Sourdough bread", 2),
                    item(5, 1, "Milk", 3, 2.0, "l"),
                    item(6, 1, "Eggs", 3, 10.0, null),
                    item(7, 1, "Coffee beans", 5, 500.0, "g"),
                    item(8, 1, "Sparkling water", 7, 6.0, null),
                    item(9, 1, "Dish soap", 8),
                )
        }

    private fun TestScope.model(stored: Stored): GroceryModel {
        backend.answerOn(this)
        return GroceryModel(sakSetup(MemorySessionStore(stored), Clock.fixed(now, ZoneOffset.UTC)), backend::api)
            .also { it.start() }
    }

    private fun TestScope.signedIn() =
        model(signedIn(now.plusSeconds(3600), email = "anna@example.org"))

    @Test
    fun signIn() =
        runTest {
            val model = model(signedOut())
            screenshots.show { SakContent(model, stringResource(R.string.grocery_app_name)) { GroceryApp(model) } }
            screenshots.shoot("grocery-sign-in") { settle(compose) }
        }

    @Test
    fun lists() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.grocery_app_name)) { GroceryApp(model) } }
            screenshots.shoot("grocery-lists") { settle(compose) }
        }

    @Test
    fun list() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.grocery_app_name)) { GroceryApp(model) } }
            settle(compose)
            model.open(1)
            screenshots.shoot("grocery-list") { settle(compose) }
        }

    @Test
    fun settings() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.grocery_app_name)) { GroceryApp(model) } }
            settle(compose)
            model.openSettings(1)
            screenshots.shoot("grocery-list-settings") { settle(compose) }
        }

    // a phone in landscape
    @Test
    @Config(qualifiers = "+land")
    fun listsLandscape() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.grocery_app_name)) { GroceryApp(model) } }
            screenshots.shoot("grocery-lists-land") { settle(compose) }
        }

    @Test
    @Config(qualifiers = "+land")
    fun listLandscape() =
        runTest {
            val model = signedIn()
            screenshots.show { SakContent(model, stringResource(R.string.grocery_app_name)) { GroceryApp(model) } }
            settle(compose)
            model.open(1)
            screenshots.shoot("grocery-list-land") { settle(compose) }
        }
}
