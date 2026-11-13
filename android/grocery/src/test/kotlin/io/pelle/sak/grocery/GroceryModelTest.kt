package io.pelle.sak.grocery

import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.core.R as CoreR
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpStatusCode
import io.pelle.sak.core.PENDING_FOR
import io.pelle.sak.core.Screen
import io.pelle.sak.core.SakModel
import io.pelle.sak.core.UiText
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.PendingLogin
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.session.openList
import io.pelle.sak.core.session.withOpenList
import io.pelle.sak.core.lists.ChangeStream
import io.pelle.sak.core.uiText
import io.pelle.sak.core.update.AppUpdate
import io.pelle.sak.core.update.UpdateException
import io.pelle.sak.core.update.Updater
import io.pelle.sak.core.update.newer
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.URL
import io.pelle.sak.testing.english
import io.pelle.sak.testing.page
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class GroceryModelTest {
    private val now = Instant.parse("2026-10-31T08:00:00Z")

    private val backend =
        FakeBackend().apply {
            answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"}"""
            answers["POST /auth/login/link"] = HttpStatusCode.Accepted to ""
            answers["POST /auth/login/exchange"] = HttpStatusCode.OK to session("link-session", "pelle@pelle.io")
            answers["POST /auth/login/password"] = HttpStatusCode.OK to session("password-session", "admin")
            answers["GET /grocery/lists"] =
                HttpStatusCode.OK to page(list(1, "Default"), list(2, "Party", shared = true))
            answers["GET /grocery/lists/1/items"] =
                HttpStatusCode.OK to page(item(10, 1, "Apples", 1), item(11, 1, "Milk", 2, 1.5, "l"), item(12, 1, "Candles", null))
            answers["GET /grocery/lists/2/items"] = HttpStatusCode.OK to page()
            answers["GET /grocery/lists/1/categories"] =
                HttpStatusCode.OK to page(category(1, "Fruit & vegetables", "🥦"), category(2, "Dairy & eggs", null))
            answers["GET /grocery/lists/2/categories"] = HttpStatusCode.OK to page()
        }

    private fun session(
        token: String,
        email: String,
    ) = """{"sessionToken":"$token","email":"$email","expiresAt":"2026-11-30T08:00:00.000Z","roles":["admin"],"redirect":"/"}"""

    private fun list(
        id: Int,
        name: String,
        shared: Boolean = false,
    ) = """{"id":$id,"name":"$name","shared":$shared,"mine":true,"items":0,"createdAt":"2026-10-31T07:00:00.000Z"}"""

    private fun item(
        id: Int,
        listId: Int,
        name: String,
        categoryId: Int?,
        amount: Double? = null,
        unit: String? = null,
    ) = """{"id":$id,"listId":$listId,"name":"$name","amount":$amount,"unit":${unit?.let { "\"$it\"" }},"categoryId":$categoryId,"createdAt":"2026-10-30T08:00:00.000Z"}"""

    private fun category(
        id: Int,
        name: String,
        icon: String?,
    ) = """{"id":$id,"name":"$name","icon":${icon?.let { "\"$it\"" }}}"""

    private fun TestScope.model(
        store: MemorySessionStore,
        at: Instant = now,
        appVersion: String = "latest",
        updater: Updater? = Updater { error("no update expected") },
    ): GroceryModel {
        backend.dispatcher = StandardTestDispatcher(testScheduler)
        return GroceryModel(store, backend::api, this, allowUrl = false, clock = Clock.fixed(at, ZoneOffset.UTC), appVersion, updater)
    }

    @Test
    fun `a new app asks for the deployment, checks it and offers the sign-in it has`() =
        runTest {
            val store = MemorySessionStore()
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            assertEquals(Screen.Deployment(), model.screen.value)

            model.connect("nonsense")
            assertTrue((model.screen.value as Screen.Deployment).error.english()!!.contains("SAK address"))

            model.connect("sak.example.org")
            advanceUntilIdle()
            assertEquals(Screen.SignIn("https://sak.example.org", multiUser = true), model.screen.value)
            assertEquals(Stored(apiUrl = URL, address = "https://sak.example.org"), store.load())
        }

    @Test
    fun `an address that names no api, or one without https, is no SAK address`() =
        runTest {
            val store = MemorySessionStore()
            val model = model(store).also { it.start() }
            advanceUntilIdle()

            backend.answers["GET /.well-known/sak.json"] = HttpStatusCode.NotFound to ""
            model.connect("www.example.org")
            advanceUntilIdle()
            assertEquals("This is no SAK address.", (model.screen.value as Screen.Deployment).error.english())

            backend.answers["GET /.well-known/sak.json"] = HttpStatusCode.OK to """{"api":"http://api.example.org/v1"}"""
            model.connect("sak.example.org")
            advanceUntilIdle()
            assertEquals("This is no SAK address.", (model.screen.value as Screen.Deployment).error.english())
            assertEquals(Stored(), store.load())
        }

    @Test
    fun `the password signs in to the home with all lists, a list opens with its items by category, back leads home`() =
        runTest {
            val store = MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org"))
            val model = model(store).also { it.start() }
            advanceUntilIdle()

            model.signInWithPassword("a password of sixteen")
            advanceUntilIdle()

            assertTrue(backend.requests.contains("""POST /auth/login/password {"password":"a password of sixteen"}"""))
            val home = model.screen.value as ListsScreen
            assertEquals("admin", home.user)
            assertNull(home.open)
            assertEquals(listOf("Default", "Party"), home.lists.map { it.name })
            assertTrue(backend.requests.none { it.contains("/items") })
            assertEquals("password-session", store.load().token)

            model.open(1)
            advanceUntilIdle()
            val lists = model.screen.value as ListsScreen
            assertEquals(1, lists.open)
            assertEquals(listOf("Fruit & vegetables", "Dairy & eggs", GroceryModel.NO_CATEGORY.english()), lists.groups.map { it.label.english() })
            assertEquals(listOf("🥦", null, null), lists.groups.map { it.icon })
            assertEquals(listOf("Apples", "Milk", "Candles"), lists.groups.flatMap { group -> group.items.map { it.name } })
            assertEquals("password-session", backend.tokens["/grocery/lists/1/items"])
            assertEquals(1, store.load().openList)

            model.back()
            advanceUntilIdle()
            assertNull((model.screen.value as ListsScreen).open)
            assertEquals(emptyList<ItemGroup>(), (model.screen.value as ListsScreen).groups)
            assertNull(store.load().openList)
        }

    @Test
    fun `a login link is taken only for a login this app asked for, within 15 minutes`() =
        runTest {
            val store = MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org"))
            val model = model(store).also { it.start() }
            advanceUntilIdle()

            // a link nobody asked for in this app
            model.signInWithLink("foreign")
            advanceUntilIdle()
            assertEquals(SakModel.NOT_ASKED, (model.screen.value as Screen.SignIn).error)
            assertTrue(backend.requests.none { it.startsWith("POST /auth/login/exchange") })

            model.requestLink(" pelle@pelle.io ")
            advanceUntilIdle()
            // with a nonce of this request, kept for the exchange
            val pending = store.load().pending!!
            assertTrue(
                backend.requests.contains(
                    """POST /auth/login/link {"email":"pelle@pelle.io","client":"grocery-android","nonce":"${pending.nonce}"}""",
                ),
            )
            assertEquals("pelle@pelle.io", (model.screen.value as Screen.SignIn).linkSentTo)
            assertEquals(PendingLogin(URL, now, pending.nonce), pending)

            model.signInWithLink("login-token")
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /auth/login/exchange {"token":"login-token","nonce":"${pending.nonce}"}"""))
            assertEquals("pelle@pelle.io", (model.screen.value as ListsScreen).user)
            // used once
            assertNull(store.load().pending)
        }

    @Test
    fun `a pending login older than 15 minutes is no longer taken`() =
        runTest {
            val store =
                MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org", pending = PendingLogin(URL, now.minusSeconds(16 * 60), "a nonce of sixteen")))
            val model = model(store).also { it.start() }
            model.signInWithLink("login-token")
            advanceUntilIdle()
            assertEquals(SakModel.NOT_ASKED, (model.screen.value as Screen.SignIn).error)
        }

    @Test
    fun `a session that ended leads to the sign-in, the deployment stays`() =
        runTest {
            val store =
                MemorySessionStore(
                    Stored(apiUrl = URL, address = "https://sak.example.org", token = "old", email = "admin", expiresAt = now.plusSeconds(60)),
                )
            backend.answers["GET /grocery/lists"] = HttpStatusCode.Unauthorized to ""
            val model = model(store).also { it.start() }
            advanceUntilIdle()

            val signIn = model.screen.value as Screen.SignIn
            assertEquals("Your session ended, please sign in again.", signIn.error.english())
            assertNull(store.load().token)
            assertEquals(URL, store.load().apiUrl)
        }

    @Test
    fun `an expired session is not used, signing out keeps the deployment`() =
        runTest {
            val expired =
                MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org", token = "old", email = "admin", expiresAt = now.minusSeconds(1)))
            model(expired).also { it.start() }.let {
                advanceUntilIdle()
                assertTrue(it.screen.value is Screen.SignIn)
            }

            val store =
                MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org", token = "t", email = "admin", expiresAt = now.plusSeconds(60)))
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.signOut()
            advanceUntilIdle()
            assertTrue(model.screen.value is Screen.SignIn)
            assertEquals(Stored(apiUrl = URL, address = "https://sak.example.org"), store.load())
        }

    @Test
    fun `an error of the backend is shown with its message`() =
        runTest {
            backend.answers["POST /auth/login/password"] = HttpStatusCode.Unauthorized to ""
            val store = MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org"))
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.signInWithPassword("wrong")
            advanceUntilIdle()
            assertEquals("The password is wrong.", (model.screen.value as Screen.SignIn).error.english())

            backend.answers["POST /auth/login/password"] =
                HttpStatusCode.TooManyRequests to """{"errors":[{"code":"test.refused","message":"Slow down."}]}"""
            model.signInWithPassword("again")
            advanceUntilIdle()
            assertEquals("Slow down.", (model.screen.value as Screen.SignIn).error.english())
        }

    private fun signedInStore(openList: Int? = 1) =
        MemorySessionStore(
            Stored(apiUrl = URL, address = "https://sak.example.org", token = "t", email = "admin", expiresAt = now.plusSeconds(60)).withOpenList(openList),
        )

    // signed in, the list open last open again
    private fun TestScope.signedIn(openList: Int? = 1): GroceryModel = model(signedInStore(openList)).also { it.start() }

    @Test
    fun `the app opens the list open when it was left, the home when that list is gone, the sign-out forgets it`() =
        runTest {
            val store = signedInStore(openList = 2)
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            assertEquals(2, (model.screen.value as ListsScreen).open)
            assertTrue(backend.requests.contains("GET /grocery/lists/2/items"))

            val gone = signedInStore(openList = 7)
            val again = model(gone).also { it.start() }
            advanceUntilIdle()
            assertNull((again.screen.value as ListsScreen).open)
            assertNull(gone.load().openList)

            model.signOut()
            advanceUntilIdle()
            assertNull(store.load().openList)
        }

    @Test
    fun `an item typed at the top goes to the list shown, the field is empty again and the items are loaded again`() =
        runTest {
            backend.answers["POST /grocery/items"] = HttpStatusCode.Created to item(20, 2, "Cake", null)
            val model = signedIn(openList = 2)
            advanceUntilIdle()

            model.addItem()
            model.typed("   ")
            model.addItem()
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("POST /grocery/items") })

            backend.answers["GET /grocery/lists/2/items"] = HttpStatusCode.OK to page(item(20, 2, "Cake", null))
            model.typed(" Cake ")
            model.addItem()
            assertTrue((model.screen.value as ListsScreen).adding)
            advanceUntilIdle()

            assertTrue(backend.requests.contains("""POST /grocery/items {"listId":2,"name":"Cake"}"""))
            assertEquals("t", backend.tokens["/grocery/items"])
            val lists = model.screen.value as ListsScreen
            assertEquals("", lists.itemName)
            assertEquals(false, lists.adding)
            assertEquals(listOf("Cake"), lists.groups.flatMap { group -> group.items.map { it.name } })
        }

    @Test
    fun `an item the backend refuses keeps its name and shows why, an ended session leads to the sign-in`() =
        runTest {
            backend.answers["POST /grocery/items"] = HttpStatusCode.BadRequest to """{"errors":[{"code":"test.refused","message":"The name is too long."}]}"""
            val model = signedIn()
            advanceUntilIdle()

            model.typed("Bread")
            model.addItem()
            advanceUntilIdle()
            val lists = model.screen.value as ListsScreen
            assertEquals("The name is too long.", lists.error.english())
            assertEquals("Bread", lists.itemName)
            assertEquals(false, lists.adding)

            backend.answers["POST /grocery/items"] = HttpStatusCode.Unauthorized to ""
            model.addItem()
            advanceUntilIdle()
            assertEquals("Your session ended, please sign in again.", (model.screen.value as Screen.SignIn).error.english())
        }

    private fun GroceryModel.item(name: String) =
        (screen.value as ListsScreen).groups.flatMap { it.items }.first { it.name == name }

    private fun GroceryModel.names() = (screen.value as ListsScreen).groups.flatMap { group -> group.items.map { it.name } }

    private fun GroceryModel.lists() = screen.value as ListsScreen

    // the Rename of the menu of the item, the name typed, then saved
    private fun GroceryModel.rename(
        item: GroceryItemResponse,
        name: String,
    ) {
        askRename(item)
        editRename(name)
        renameItem()
    }

    @Test
    fun `a checked item is struck through and goes when its undo ran out, the backend keeps it as bought`() =
        runTest {
            backend.answers["POST /grocery/items/10/check"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()

            model.check(model.item("Apples"))
            advanceTimeBy(UNDO_MS - 1)
            runCurrent()
            val struck = model.screen.value as ListsScreen
            assertEquals(setOf(10), struck.struck)
            assertEquals("Apples bought", struck.undo?.text.english())
            assertEquals(listOf("Apples", "Milk", "Candles"), model.names())
            assertTrue(backend.requests.none { it.endsWith("/check") })

            advanceUntilIdle()
            val lists = model.screen.value as ListsScreen
            assertEquals(emptySet<Int>(), lists.struck)
            assertNull(lists.undo)
            assertEquals(listOf("Dairy & eggs", GroceryModel.NO_CATEGORY.english()), lists.groups.map { it.label.english() })
            assertEquals(listOf("POST /grocery/items/10/check"), backend.requests.filter { it.endsWith("/check") })
            assertEquals("t", backend.tokens["/grocery/items/10/check"])
        }

    @Test
    fun `the eye shows the bought items too, struck through and not checked again, the eye-slash hides them (GROCERY-14)`() =
        runTest {
            backend.answers["POST /grocery/items/10/check"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()
            assertFalse((model.screen.value as ListsScreen).showBought)
            assertTrue(backend.queries.filter { it.startsWith("GET /grocery/lists/1/items") }.none { "checked=true" in it })

            val bought = """{"id":13,"listId":1,"name":"Bread","categoryId":null,"checkedAt":"2026-10-30T08:00:00.000Z","createdAt":"2026-10-30T08:00:00.000Z"}"""
            backend.answers["GET /grocery/lists/1/items"] =
                HttpStatusCode.OK to page(item(10, 1, "Apples", 1), item(11, 1, "Milk", 2, 1.5, "l"), item(12, 1, "Candles", null), bought)
            model.toggleBought()
            advanceUntilIdle()
            assertTrue((model.screen.value as ListsScreen).showBought)
            assertTrue("checked=true" in backend.queries.last { it.startsWith("GET /grocery/lists/1/items") })
            assertEquals(listOf("Apples", "Milk", "Candles", "Bread"), model.names())

            // a bought item is checked already, a tap does nothing
            model.check(model.item("Bread"))
            assertEquals(emptySet<Int>(), (model.screen.value as ListsScreen).struck)
            // an item checked while they show stays, as bought, after the ones to buy of its group
            model.check(model.item("Apples"))
            advanceTimeBy(UNDO_MS + 1)
            runCurrent()
            assertNotNull(model.item("Apples").checkedAt)
            assertEquals(listOf("POST /grocery/items/10/check"), backend.requests.filter { it.endsWith("/check") })

            // hidden again
            model.toggleBought()
            advanceUntilIdle()
            assertFalse((model.screen.value as ListsScreen).showBought)
            assertFalse("checked=true" in backend.queries.last { it.startsWith("GET /grocery/lists/1/items") })
        }

    @Test
    fun `an item is renamed with its quantity and category kept, an invalid or unchanged name is not sent, a refused one shows why`() =
        runTest {
            backend.answers["PUT /grocery/items/11"] = HttpStatusCode.OK to item(11, 1, "Oat milk", 2, 1.5, "l")
            backend.answers["PUT /grocery/items/12"] = HttpStatusCode.BadRequest to """{"errors":[{"code":"test.refused","message":"no"}]}"""
            val model = signedIn()
            advanceUntilIdle()

            model.rename(model.item("Milk"), "  Oat milk ")
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/items/11 {"name":"Oat milk","amount":1.5,"unit":"l","categoryId":2}"""))
            assertNull((model.screen.value as ListsScreen).error)

            // the category sent as null: missing would take it away all the same, null says so
            model.rename(model.item("Candles"), "Candle")
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/items/12 {"name":"Candle"}"""))
            assertEquals("no", (model.screen.value as ListsScreen).error.english())

            val sent = backend.requests.count { it.startsWith("PUT /grocery/items/") }
            // an invalid name stays in the dialog, its error shows (UX-6)
            model.askRename(model.item("Apples"))
            model.editRename(" ")
            assertFalse(model.lists().renaming!!.left)
            model.renameItem()
            assertTrue(model.lists().renaming!!.left)
            model.editRename("x".repeat(129))
            model.renameItem()
            assertNotNull(model.lists().renaming)
            model.dismissRename()
            assertNull(model.lists().renaming)
            // the field left shows the error too
            model.askRename(model.item("Apples"))
            model.leaveRename()
            assertTrue(model.lists().renaming!!.left)
            // unchanged, the dialog closes and nothing is sent
            model.editRename(" Apples ")
            model.renameItem()
            assertNull(model.lists().renaming)
            advanceUntilIdle()
            assertEquals(sent, backend.requests.count { it.startsWith("PUT /grocery/items/") })
            assertTrue(GroceryModel.validItemName("x".repeat(128)))
            assertFalse(GroceryModel.validItemName("x".repeat(129)))
        }

    @Test
    fun `the quantity of an item is set with a comma, cancel and an unchanged one send nothing, no amount takes the unit away (GROCERY-15)`() =
        runTest {
            backend.answers["PUT /grocery/items/10"] = HttpStatusCode.OK to item(10, 1, "Apples", 1, 0.5, "kg")
            backend.answers["PUT /grocery/items/11"] = HttpStatusCode.OK to item(11, 1, "Milk", 2)
            val model = signedIn()
            advanceUntilIdle()

            model.askQuantity(model.item("Apples"))
            assertEquals(Quantity(model.item("Apples"), "", null), model.lists().quantity)
            model.editQuantityAmount(" 0,5 ")
            model.chooseQuantityUnit("kg")
            model.saveQuantity()
            assertNull(model.lists().quantity)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/items/10 {"name":"Apples","amount":0.5,"unit":"kg","categoryId":1}"""))
            assertNull(model.lists().error)

            // the amount as it shows, without trailing zeros; cancel sends nothing, back too
            val sent = backend.requests.count { it.startsWith("PUT /grocery/items/") }
            model.askQuantity(model.item("Milk"))
            assertEquals("1.5", model.lists().quantity?.amount)
            assertEquals("l", model.lists().quantity?.unit)
            model.editQuantityAmount("2")
            model.dismissQuantity()
            assertNull(model.lists().quantity)
            model.askQuantity(model.item("Milk"))
            model.editQuantityAmount("2")
            model.back()
            assertNull(model.lists().quantity)
            assertEquals(1, model.lists().open)
            // unchanged, with a point for the comma, the dialog closes and nothing is sent
            model.askQuantity(model.item("Milk"))
            model.editQuantityAmount("1,500")
            model.saveQuantity()
            assertNull(model.lists().quantity)
            advanceUntilIdle()
            assertEquals(sent, backend.requests.count { it.startsWith("PUT /grocery/items/") })

            // no amount: the unit goes too, the backend takes none without one
            model.askQuantity(model.item("Milk"))
            model.editQuantityAmount(" ")
            model.saveQuantity()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/items/11 {"name":"Milk","categoryId":2}"""))
        }

    @Test
    fun `an invalid amount is not saved and shows its error, a unit that is none of the list is kept, a refused one shows why (GROCERY-15)`() =
        runTest {
            backend.answers["GET /grocery/lists/1/items"] = HttpStatusCode.OK to page(item(10, 1, "Garlic", 1, 2.0, "Zehe"))
            backend.answers["PUT /grocery/items/10"] = HttpStatusCode.OK to item(10, 1, "Garlic", 1, 3.0, "Zehe")
            val model = signedIn()
            advanceUntilIdle()

            model.askQuantity(model.item("Garlic"))
            assertFalse(model.lists().quantity!!.left)
            listOf("0", "-1", "1.2345", "10000000", "1,2,3", "two", "1e3").forEach { amount ->
                model.editQuantityAmount(amount)
                model.saveQuantity()
                assertTrue(amount, model.lists().quantity!!.left)
            }
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("PUT /grocery/items/") })
            // the field left shows the error too
            model.dismissQuantity()
            model.askQuantity(model.item("Garlic"))
            model.leaveQuantity()
            assertTrue(model.lists().quantity!!.left)
            assertEquals(Amount.Of(9_999_999.999), amountOf("9999999,999"))
            assertEquals(Amount.Of(0.5), amountOf(".5"))

            // the unit of the item stays when only the amount changes
            model.editQuantityAmount("3")
            model.saveQuantity()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/items/10 {"name":"Garlic","amount":3.0,"unit":"Zehe","categoryId":1}"""))

            backend.answers["PUT /grocery/items/10"] = HttpStatusCode.InternalServerError to ""
            model.askQuantity(model.item("Garlic"))
            model.editQuantityAmount("4")
            model.saveQuantity()
            advanceUntilIdle()
            assertEquals("The quantity could not be saved.", model.lists().error.english())
        }

    @Test
    fun `the robot shows when the backend discovers the items of a photo, not when it says no or can not say (GROCERY-16)`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()
            // no answer
            assertFalse(model.lists().discoverItems)

            backend.answers["GET /grocery/settings"] = HttpStatusCode.OK to """{"discoverItems":true}"""
            model.refresh()
            advanceUntilIdle()
            assertTrue(model.lists().discoverItems)

            backend.answers["GET /grocery/settings"] = HttpStatusCode.OK to """{"discoverItems":false}"""
            model.refresh()
            advanceUntilIdle()
            assertFalse(model.lists().discoverItems)

            backend.answers["GET /grocery/settings"] = HttpStatusCode.OK to """{"discoverItems":true}"""
            model.refresh()
            advanceUntilIdle()
            backend.answers["GET /grocery/settings"] = HttpStatusCode.InternalServerError to ""
            model.refresh()
            advanceUntilIdle()
            assertFalse(model.lists().discoverItems)
            // the list loads all the same
            assertNull(model.lists().error)
            assertEquals(listOf("Apples", "Milk", "Candles"), model.names())

            // asked when the list opens and on a pull down, not on the loads a change starts
            val asked = backend.requests.count { it == "GET /grocery/settings" }
            backend.answers["GET /grocery/settings"] = HttpStatusCode.OK to """{"discoverItems":true}"""
            model.toggleBought()
            advanceUntilIdle()
            assertEquals(asked, backend.requests.count { it == "GET /grocery/settings" })
        }

    private fun discoverAnswers(items: String) {
        backend.answers["GET /grocery/settings"] = HttpStatusCode.OK to """{"discoverItems":true}"""
        backend.answers["POST /files"] = HttpStatusCode.Created to """{"id":9,"contentType":"image/jpeg","size":1,"url":"/api/v1/files/9","thumbnailUrl":"/api/v1/files/9/thumbnail","createdAt":"2026-10-30T08:00:00.000Z"}"""
        backend.answers["POST /grocery/discover/9"] = HttpStatusCode.OK to """{"items":[$items]}"""
    }

    @Test
    fun `the items of a photo are read, corrected and the ones checked added without a category (GROCERY-16)`() =
        runTest {
            discoverAnswers("""{"name":"Flour","amount":500,"unit":"g"},{"name":"Garlik","amount":2,"unit":"Zehe"},{"name":"Salt","unit":"pinch"},{"name":"Water","amount":null,"unit":null}""")
            backend.answers["POST /grocery/items"] = HttpStatusCode.Created to item(20, 1, "Flour", null)
            val model = signedIn()
            advanceUntilIdle()

            // the dialog says the photo is read while it is
            model.discover {
                assertTrue(model.lists().discovery!!.reading)
                "jpeg".toByteArray()
            }
            advanceUntilIdle()
            assertTrue(backend.requests.contains("POST /files jpeg"))
            assertTrue(backend.requests.contains("POST /grocery/discover/9"))
            // nothing is added yet
            assertTrue(backend.requests.none { it.startsWith("POST /grocery/items") })
            val discovery = model.lists().discovery!!
            assertFalse(discovery.reading)
            assertEquals(1, discovery.listId)
            assertEquals(
                listOf(
                    DiscoveredRow(0, "Flour", "500", "g"),
                    DiscoveredRow(1, "Garlik", "2", "Zehe"),
                    DiscoveredRow(2, "Salt", "", "pinch"),
                    DiscoveredRow(3, "Water", "", null),
                ),
                discovery.rows,
            )

            // a name corrected, a quantity changed, the water left out
            model.editDiscoveredName(1, " Garlic ")
            model.editDiscoveredAmount(0, "0,75")
            model.chooseDiscoveredUnit(0, "kg")
            model.includeDiscovered(3, false)
            model.addDiscovered()
            assertTrue(model.lists().discovery!!.adding)
            advanceUntilIdle()
            assertEquals(
                listOf(
                    """POST /grocery/items {"listId":1,"name":"Flour","amount":0.75,"unit":"kg"}""",
                    """POST /grocery/items {"listId":1,"name":"Garlic","amount":2.0,"unit":"Zehe"}""",
                    // the unit goes without an amount
                    """POST /grocery/items {"listId":1,"name":"Salt"}""",
                ),
                backend.requests.filter { it.startsWith("POST /grocery/items") },
            )
            assertNull(model.lists().discovery)
            assertNull(model.lists().error)
            // the list loaded again
            assertEquals(2, backend.requests.count { it == "GET /grocery/lists/1/items" })
        }

    @Test
    fun `the items of a photo are not added with an invalid row checked, or after cancel or back (GROCERY-16)`() =
        runTest {
            discoverAnswers("""{"name":"Flour","amount":500,"unit":"g"},{"name":"Sugar"}""")
            val model = signedIn()
            advanceUntilIdle()

            model.discover { "jpeg".toByteArray() }
            advanceUntilIdle()
            model.editDiscoveredName(0, " ")
            model.editDiscoveredAmount(1, "0")
            model.leaveDiscoveredName(0)
            assertTrue(model.lists().discovery!!.rows[0].nameLeft)
            assertFalse(model.lists().discovery!!.rows[0].valid)
            model.addDiscovered()
            assertTrue(model.lists().discovery!!.submitted)
            assertFalse(model.lists().discovery!!.adding)
            // a row left out is no error
            model.includeDiscovered(0, false)
            model.includeDiscovered(1, false)
            model.addDiscovered()
            model.dismissDiscovery()
            assertNull(model.lists().discovery)

            model.discover { "jpeg".toByteArray() }
            advanceUntilIdle()
            model.back()
            assertNull(model.lists().discovery)
            assertEquals(1, model.lists().open)
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("POST /grocery/items") })

            // cancelled while the photo is read: it is not sent, nothing shows
            val uploads = backend.requests.count { it.startsWith("POST /files") }
            model.discover {
                model.dismissDiscovery()
                "jpeg".toByteArray()
            }
            advanceUntilIdle()
            assertNull(model.lists().discovery)
            assertEquals(uploads, backend.requests.count { it.startsWith("POST /files") })
        }

    @Test
    fun `a photo without items says so, a refused or unreadable one says why (GROCERY-16)`() =
        runTest {
            discoverAnswers("")
            val model = signedIn()
            advanceUntilIdle()

            model.discover { "jpeg".toByteArray() }
            advanceUntilIdle()
            val none = model.lists().discovery!!
            assertFalse(none.reading)
            assertTrue(none.rows.isEmpty())
            model.dismissDiscovery()

            backend.answers["POST /grocery/discover/9"] =
                HttpStatusCode.Conflict to """{"errors":[{"code":"grocery.discover_off","message":"The AI features are off."}]}"""
            model.discover { "jpeg".toByteArray() }
            advanceUntilIdle()
            assertNull(model.lists().discovery)
            assertEquals("The AI features are off.", model.lists().error.english())

            backend.answers["POST /grocery/discover/9"] = HttpStatusCode.InternalServerError to ""
            model.discover { "jpeg".toByteArray() }
            advanceUntilIdle()
            assertEquals("The photo could not be read.", model.lists().error.english())

            // what the phone can not read is not sent
            val uploads = backend.requests.count { it.startsWith("POST /files") }
            model.discover { null }
            advanceUntilIdle()
            assertNull(model.lists().discovery)
            assertEquals("The photo could not be read.", model.lists().error.english())
            assertEquals(uploads, backend.requests.count { it.startsWith("POST /files") })
        }

    @Test
    fun `the items of a photo the list does not take stay with why, the others are added (GROCERY-16)`() =
        runTest {
            discoverAnswers("""{"name":"Flour"},{"name":"Sugar"},{"name":"Eggs","amount":6}""")
            backend.answers["POST /grocery/items"] = HttpStatusCode.Created to item(20, 1, "Flour", null)
            val model = signedIn()
            advanceUntilIdle()

            model.discover { "jpeg".toByteArray() }
            advanceUntilIdle()
            // the second one is lost on the way, the others are added
            var adds = 0
            backend.client.plugin(HttpSend).intercept { request ->
                if (request.url.buildString().endsWith("/grocery/items") && ++adds == 2) throw IOException("lost")
                execute(request)
            }
            model.addDiscovered()
            advanceUntilIdle()
            assertEquals(3, adds)
            assertEquals(2, backend.requests.count { it.startsWith("POST /grocery/items") })
            val discovery = model.lists().discovery!!
            assertEquals(listOf("Sugar"), discovery.rows.map { it.name })
            assertEquals("Not every item could be added.", discovery.error.english())
            assertFalse(discovery.adding)
            // the list shows the ones added
            assertEquals(2, backend.requests.count { it == "GET /grocery/lists/1/items" })

            // added again, the dialog closes
            model.addDiscovered()
            advanceUntilIdle()
            assertNull(model.lists().discovery)
            assertEquals("""POST /grocery/items {"listId":1,"name":"Sugar"}""", backend.requests.last { it.startsWith("POST /grocery/items") })
        }

    @Test
    fun `a bought item is renamed, gets a category, loses its photo and is deleted from its menu as one to buy (GROCERY-14)`() =
        runTest {
            val bought = """{"id":13,"listId":1,"name":"Bred","categoryId":null,"photoId":7,"checkedAt":"2026-10-30T08:00:00.000Z","createdAt":"2026-10-30T08:00:00.000Z"}"""
            backend.answers["GET /grocery/lists/1/items"] =
                HttpStatusCode.OK to page(item(10, 1, "Apples", 1), item(11, 1, "Milk", 2, 1.5, "l"), item(12, 1, "Candles", null), bought)
            backend.answers["PUT /grocery/items/13"] = HttpStatusCode.OK to bought.replace("Bred", "Bread")
            backend.answers["PUT /grocery/items/13/category"] = HttpStatusCode.OK to bought.replace("\"categoryId\":null", "\"categoryId\":1")
            backend.answers["DELETE /grocery/items/13/photo"] = HttpStatusCode.OK to bought.replace("\"photoId\":7", "\"photoId\":null")
            backend.answers["DELETE /grocery/items/13"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()
            model.toggleBought()
            advanceUntilIdle()

            model.rename(model.item("Bred"), "Bread")
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/items/13 {"name":"Bread"}"""))

            model.chooseCategory(model.item("Bred"))
            model.setCategory(1)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/items/13/category {"categoryId":1}"""))

            model.removePhoto(model.item("Bred"))
            advanceUntilIdle()
            assertTrue(backend.requests.contains("DELETE /grocery/items/13/photo"))

            model.deleteItem(model.item("Bred"))
            advanceUntilIdle()
            assertTrue(backend.requests.contains("DELETE /grocery/items/13"))
            assertNull((model.screen.value as ListsScreen).error)
            // it stays in its category group, after the ones to buy: the one without a category, last
            assertEquals(listOf("Candles", "Bred"), (model.screen.value as ListsScreen).groups.last().items.map { it.name })
            // and a tap still does not check it
            model.check(model.item("Bred"))
            assertEquals(emptySet<Int>(), (model.screen.value as ListsScreen).struck)
        }

    @Test
    fun `an undo or a second tap keeps the item, a check before goes to the backend at once`() =
        runTest {
            backend.answers["POST /grocery/items/10/check"] = HttpStatusCode.NoContent to ""
            backend.answers["POST /grocery/items/11/check"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()

            model.check(model.item("Apples"))
            model.undo()
            model.check(model.item("Milk"))
            model.check(model.item("Milk"))
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.endsWith("/check") })
            assertEquals(emptySet<Int>(), (model.screen.value as ListsScreen).struck)

            model.check(model.item("Apples"))
            model.check(model.item("Milk"))
            runCurrent()
            assertEquals(listOf("POST /grocery/items/10/check"), backend.requests.filter { it.endsWith("/check") })
            assertEquals(setOf(11), (model.screen.value as ListsScreen).struck)

            model.signOut()
            advanceUntilIdle()
            assertEquals(2, backend.requests.count { it.endsWith("/check") })
            assertEquals("t", backend.tokens["/grocery/items/11/check"])
        }

    @Test
    fun `an item deleted from the menu of its row goes for good, a refused delete shows why`() =
        runTest {
            backend.answers["DELETE /grocery/items/10"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()

            model.deleteItem(model.item("Apples"))
            advanceUntilIdle()
            assertTrue(backend.requests.contains("DELETE /grocery/items/10"))
            assertTrue(backend.requests.none { it.endsWith("/check") })
            assertNull((model.screen.value as ListsScreen).error)
            // the list loads again as the backend has it
            assertEquals(2, backend.requests.count { it.startsWith("GET /grocery/lists/1/items") })

            model.deleteItem(model.item("Milk"))
            advanceUntilIdle()
            assertEquals("The item could not be deleted.", (model.screen.value as ListsScreen).error.english())
        }

    @Test
    fun `a check of an item bought or deleted meanwhile, e g by another device, is done without an error`() =
        runTest {
            // no answer: 404, the item is no longer to buy
            val model = signedIn()
            advanceUntilIdle()

            model.check(model.item("Apples"))
            advanceUntilIdle()
            assertEquals(listOf("POST /grocery/items/10/check"), backend.requests.filter { it.endsWith("/check") })
            assertNull(model.lists().error)
        }

    @Test
    fun `a Delete in the undo window drops the check, a Rename in it puts the item back under its new name`() =
        runTest {
            backend.answers["DELETE /grocery/items/10"] = HttpStatusCode.NoContent to ""
            backend.answers["POST /grocery/items/11/check"] = HttpStatusCode.NoContent to ""
            backend.answers["PUT /grocery/items/11"] = HttpStatusCode.OK to item(11, 1, "Oat milk", 2, 1.5, "l")
            val model = signedIn()
            advanceUntilIdle()

            model.check(model.item("Apples"))
            model.askDelete(model.item("Apples"))
            assertEquals(10, model.lists().deleting?.id)
            model.deleteItem(model.item("Apples"))
            assertNull(model.lists().deleting)
            assertEquals(emptySet<Int>(), model.lists().struck)
            assertNull(model.lists().undo)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("DELETE /grocery/items/10"))
            assertTrue(backend.requests.none { it.endsWith("/check") })
            assertNull(model.lists().error)

            // with the bought items shown, the item checked stays under the name it got in the undo window
            model.toggleBought()
            advanceUntilIdle()
            model.check(model.item("Milk"))
            model.rename(model.item("Milk"), "Oat milk")
            runCurrent()
            advanceTimeBy(UNDO_MS + 1)
            runCurrent()
            assertNotNull(model.item("Oat milk").checkedAt)
            assertEquals(listOf("POST /grocery/items/11/check"), backend.requests.filter { it.endsWith("/check") })
        }

    @Test
    fun `the eye toggled in the undo window loads after the check, another list opens with the eye-slash, a failed load shows why`() =
        runTest {
            backend.answers["POST /grocery/items/10/check"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()

            model.check(model.item("Apples"))
            model.toggleBought()
            advanceUntilIdle()
            val check = backend.requests.indexOf("POST /grocery/items/10/check")
            val load = backend.queries.indexOfLast { it.startsWith("GET /grocery/lists/1/items") && "checked=true" in it }
            assertTrue(check >= 0 && load >= 0)
            assertTrue(check < backend.requests.lastIndexOf("GET /grocery/lists/1/items"))
            assertTrue(model.lists().showBought)

            // the eye is the one of the list open: another list opens with the bought items hidden
            model.back()
            model.open(2)
            advanceUntilIdle()
            assertFalse(model.lists().showBought)
            assertFalse("checked=true" in backend.queries.last { it.startsWith("GET /grocery/lists/2/items") })

            backend.answers["GET /grocery/lists/2/items"] = HttpStatusCode.InternalServerError to ""
            model.toggleBought()
            advanceUntilIdle()
            assertFalse(model.lists().loading)
            assertNotNull(model.lists().error)
        }

    @Test
    fun `the dialogs of the menu of an item stay when its row goes, back closes them`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()

            model.askRename(model.item("Milk"))
            model.editRename("Oat mi")
            // another user checked it: the list no longer shows it, the dialog keeps the name typed
            backend.answers["GET /grocery/lists/1/items"] = HttpStatusCode.OK to page(item(10, 1, "Apples", 1), item(12, 1, "Candles", null))
            model.refresh()
            advanceUntilIdle()
            assertEquals(listOf("Apples", "Candles"), model.names())
            assertEquals("Oat mi", model.lists().renaming?.name)
            model.back()
            assertNull(model.lists().renaming)
            assertEquals(1, model.lists().open)

            model.askDelete(model.item("Apples"))
            model.back()
            assertNull(model.lists().deleting)
            model.askDelete(model.item("Apples"))
            model.dismissDelete()
            assertNull(model.lists().deleting)
            assertEquals(1, model.lists().open)
        }

    @Test
    fun `a check the backend refuses shows why and the list as the backend has it`() =
        runTest {
            backend.answers["POST /grocery/items/10/check"] = HttpStatusCode.InternalServerError to ""
            val model = signedIn()
            advanceUntilIdle()

            model.check(model.item("Apples"))
            advanceUntilIdle()
            assertEquals("The item could not be checked.", (model.screen.value as ListsScreen).error.english())
            assertEquals(listOf("Apples", "Milk", "Candles"), model.names())
        }

    @Test
    fun `the plus of the home creates a list that opens, a refused name stays with the reason, back leads home`() =
        runTest {
            backend.answers["POST /grocery/lists"] = HttpStatusCode.Conflict to """{"errors":[{"code":"test.refused","message":"A list of the name exists."}]}"""
            val store = signedInStore(openList = null)
            val model = model(store).also { it.start() }
            advanceUntilIdle()

            model.newList()
            model.editSettings(" Party ", shared = true, icon = "🎉")
            model.saveSettings()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /grocery/lists {"name":"Party","shared":true,"icon":"🎉"}"""))
            assertEquals(ListSettings(name = " Party ", shared = true, icon = "🎉", error = UiText.Plain("A list of the name exists.")), (model.screen.value as ListsScreen).settings)

            model.back()
            assertNull((model.screen.value as ListsScreen).settings)

            backend.answers["POST /grocery/lists"] = HttpStatusCode.Created to list(3, "Hardware")
            backend.answers["GET /grocery/lists/3/items"] = HttpStatusCode.OK to page()
            backend.answers["GET /grocery/lists"] =
                HttpStatusCode.OK to page(list(1, "Default"), list(2, "Party", shared = true), list(3, "Hardware"))
            model.newList()
            model.editSettings("Hardware", shared = false, icon = null)
            model.saveSettings()
            advanceUntilIdle()
            val lists = model.screen.value as ListsScreen
            assertNull(lists.settings)
            assertEquals(3, lists.open)
            assertEquals(3, store.load().openList)
        }

    @Test
    fun `the sign-in offers its three ways, a way chosen shows its form, back the three again, no e-mail in the single user mode`() =
        runTest {
            val store = MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org"))
            val multi = model(store).also { it.start() }
            advanceUntilIdle()
            assertEquals(Screen.SignIn("https://sak.example.org", multiUser = true), multi.screen.value)
            multi.chooseMethod(Screen.SignInMethod.PASSWORD)
            assertEquals(Screen.SignInMethod.PASSWORD, (multi.screen.value as Screen.SignIn).method)
            multi.chooseMethod(null)
            assertEquals(null, (multi.screen.value as Screen.SignIn).method)
            multi.chooseMethod(Screen.SignInMethod.EMAIL)
            assertEquals(Screen.SignInMethod.EMAIL, (multi.screen.value as Screen.SignIn).method)

            backend.answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"single"}"""
            val single = model(MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org"))).also { it.start() }
            advanceUntilIdle()
            assertEquals(Screen.SignIn("https://sak.example.org", multiUser = false), single.screen.value)
            // no e-mail login to go to
            single.chooseMethod(Screen.SignInMethod.EMAIL)
            assertEquals(null, (single.screen.value as Screen.SignIn).method)
            single.chooseMethod(Screen.SignInMethod.PASSWORD)
            assertEquals(Screen.SignInMethod.PASSWORD, (single.screen.value as Screen.SignIn).method)
        }

    private fun changed(listId: Int) = "event: changed\ndata: {\"listId\":$listId}\n\n"

    @Test
    fun `a change of the open list loads it again, of another list not, a new stream loads what was missed`() =
        runTest {
            backend.answers["GET /grocery/changes"] = HttpStatusCode.OK to ": listening\n\n${changed(2)}${changed(1)}"
            val model = signedIn(openList = 1)
            advanceUntilIdle()
            val loads = { backend.requests.count { it == "GET /grocery/lists/1/items" } }
            assertEquals(1, loads())

            model.startListening()
            advanceTimeBy(ChangeStream.RETRY_DELAY_MS / 2)
            runCurrent()
            // list 2 is not open, list 1 is
            assertEquals(2, loads())
            assertEquals("t", backend.tokens["/grocery/changes"])

            // the stream ended; the next one loads once for what it may have missed, once for its change
            advanceTimeBy(ChangeStream.RETRY_DELAY_MS)
            runCurrent()
            assertTrue(loads() > 2)
            assertEquals(2, backend.requests.count { it == "GET /grocery/changes" })

            model.stopListening()
            advanceUntilIdle()
            assertEquals(2, backend.requests.count { it == "GET /grocery/changes" })
        }

    @Test
    fun `the home loads again on a change of any list, an ended session leads to the sign-in`() =
        runTest {
            backend.answers["GET /grocery/changes"] = HttpStatusCode.OK to changed(7)
            val model = signedIn(openList = null)
            advanceUntilIdle()
            model.startListening()
            advanceTimeBy(ChangeStream.RETRY_DELAY_MS / 2)
            runCurrent()
            assertEquals(2, backend.requests.count { it == "GET /grocery/lists" })

            backend.answers["GET /grocery/changes"] = HttpStatusCode.Unauthorized to ""
            advanceTimeBy(ChangeStream.RETRY_DELAY_MS)
            runCurrent()
            assertEquals("Your session ended, please sign in again.", (model.screen.value as Screen.SignIn).error.english())
            model.stopListening()
        }

    @Test
    fun `the menu of an own list opens its settings, they change its icon and delete it, the last own list too`() =
        runTest {
            backend.answers["GET /grocery/lists"] =
                HttpStatusCode.OK to page(list(1, "Default"), list(2, "Party", shared = true), """{"id":3,"name":"Theirs","shared":true,"mine":false,"items":0,"createdAt":"2026-10-30T08:00:00.000Z"}""")
            backend.answers["PUT /grocery/lists/2"] = HttpStatusCode.OK to list(2, "Party", shared = true)
            backend.answers["DELETE /grocery/lists/2"] = HttpStatusCode.NoContent to ""
            val model = signedIn(openList = null)
            advanceUntilIdle()

            model.openSettings(2)
            // its categories loading
            assertEquals(ListSettings(2, "Party", shared = true, categoriesLoading = true), (model.screen.value as ListsScreen).settings)
            model.editSettings("Party", shared = true, icon = "🎉")
            model.saveSettings()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/lists/2 {"name":"Party","shared":true,"icon":"🎉"}"""))
            assertNull((model.screen.value as ListsScreen).settings)

            model.openSettings(2)
            model.deleteList()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("DELETE /grocery/lists/2"))
            assertNull((model.screen.value as ListsScreen).settings)

            // the only own list goes too (LIST-1)
            backend.answers["GET /grocery/lists"] = HttpStatusCode.OK to page(list(1, "Default"))
            backend.answers["DELETE /grocery/lists/1"] = HttpStatusCode.NoContent to ""
            model.refresh()
            advanceUntilIdle()
            model.openSettings(1)
            model.deleteList()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("DELETE /grocery/lists/1"))
            assertNull((model.screen.value as ListsScreen).settings)
        }

    @Test
    fun `every user who sees a shared list changes its icon and categories, only its owner renames, shares and deletes it`() =
        runTest {
            backend.answers["GET /grocery/lists"] =
                HttpStatusCode.OK to page(list(1, "Default"), """{"id":3,"name":"Theirs","shared":true,"mine":false,"items":0,"createdAt":"2026-10-30T08:00:00.000Z"}""")
            backend.answers["GET /grocery/lists/3/categories"] = HttpStatusCode.OK to page(category(5, "Snacks", null))
            backend.answers["PUT /grocery/lists/3"] =
                HttpStatusCode.OK to """{"id":3,"name":"Theirs","shared":true,"mine":false,"items":0,"icon":"🎉","createdAt":"2026-10-30T08:00:00.000Z"}"""
            backend.answers["POST /grocery/lists/3/categories"] = HttpStatusCode.Created to category(6, "Drinks", null)
            backend.answers["DELETE /grocery/lists/3"] = HttpStatusCode.NoContent to ""
            val model = signedIn(openList = null)
            advanceUntilIdle()
            fun settings() = (model.screen.value as ListsScreen).settings!!

            model.openSettings(3)
            advanceUntilIdle()
            assertEquals(false, settings().mine)
            assertEquals(listOf("Snacks"), settings().categories.map { it.name })

            // the categories as for the owner
            model.editNewCategory("Drinks")
            model.addCategory()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /grocery/lists/3/categories {"name":"Drinks"}"""))

            // its name and that it is shared stay: the field and the switch change nothing, the unchanged
            // name and flag are sent
            model.editSettings("Ours", shared = false, icon = "🎉")
            assertEquals(true, settings().shared)
            assertEquals("Theirs", settings().name)
            model.saveSettings()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/lists/3 {"name":"Theirs","shared":true,"icon":"🎉"}"""))

            // only the owner deletes it
            model.openSettings(3)
            advanceUntilIdle()
            model.deleteList()
            advanceUntilIdle()
            assertTrue(backend.requests.none { it == "DELETE /grocery/lists/3" })
        }

    @Test
    fun `a save of a list its owner unshared or deleted meanwhile closes the settings and says so`() =
        runTest {
            backend.answers["GET /grocery/lists"] =
                HttpStatusCode.OK to page(list(1, "Default"), """{"id":3,"name":"Theirs","shared":true,"mine":false,"items":0,"createdAt":"2026-10-30T08:00:00.000Z"}""")
            backend.answers["GET /grocery/lists/3/categories"] = HttpStatusCode.OK to page()
            backend.answers["PUT /grocery/lists/3"] = HttpStatusCode.NotFound to ""
            val model = signedIn(openList = null)
            advanceUntilIdle()

            model.openSettings(3)
            advanceUntilIdle()
            model.editSettings("Ours", shared = true, icon = null)
            model.saveSettings()
            advanceUntilIdle()
            val screen = model.screen.value as ListsScreen
            assertEquals(null, screen.settings)
            assertEquals("The list is gone, or its owner no longer shares it.", screen.error.english())
        }

    @Test
    fun `the menu of a row on the home deletes an own list, the last one too, not one of another user`() =
        runTest {
            backend.answers["GET /grocery/lists"] =
                HttpStatusCode.OK to page(list(1, "Default"), list(2, "Party"), """{"id":3,"name":"Theirs","shared":true,"mine":false,"items":0,"createdAt":"2026-10-30T08:00:00.000Z"}""")
            backend.answers["DELETE /grocery/lists/2"] = HttpStatusCode.NoContent to ""
            val model = signedIn(openList = null)
            advanceUntilIdle()

            model.deleteList(3)
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("DELETE") })

            backend.answers["GET /grocery/lists"] = HttpStatusCode.OK to page(list(1, "Default"))
            model.deleteList(2)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("DELETE /grocery/lists/2"))
            assertEquals(listOf("Default"), (model.screen.value as ListsScreen).lists.map { it.name })

            // the only own list goes too (LIST-1), the home is empty then
            backend.answers["DELETE /grocery/lists/1"] = HttpStatusCode.NoContent to ""
            backend.answers["GET /grocery/lists"] = HttpStatusCode.OK to page()
            model.deleteList(1)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("DELETE /grocery/lists/1"))
            assertTrue((model.screen.value as ListsScreen).lists.isEmpty())
        }

    @Test
    fun `a photo is fetched in the scaled size its place needs, once`() {
        assertEquals(128, scaledSize(48))
        assertEquals(256, scaledSize(132))
        assertEquals(1024, scaledSize(1024))
        assertNull(scaledSize(1025))
    }

    @Test
    fun `the menu of an item attaches a photo, stored first, and removes it, a photo is fetched once a size`() =
        runTest {
            backend.answers["POST /files"] = HttpStatusCode.Created to """{"id":9,"contentType":"image/jpeg","size":1,"url":"/api/v1/files/9","thumbnailUrl":"/api/v1/files/9/thumbnail","createdAt":"2026-10-30T08:00:00.000Z"}"""
            backend.answers["PUT /grocery/items/10/photo"] = HttpStatusCode.OK to item(10, 1, "Apples", 1)
            backend.answers["DELETE /grocery/items/10/photo"] = HttpStatusCode.OK to item(10, 1, "Apples", 1)
            backend.answers["GET /grocery/items/10/photos/9"] = HttpStatusCode.OK to "jpeg"
            val model = signedIn()
            advanceUntilIdle()

            model.attachPhoto(10) { "jpeg".toByteArray() }
            advanceUntilIdle()
            assertTrue(backend.requests.contains("POST /files jpeg"))
            assertTrue(backend.requests.contains("""PUT /grocery/items/10/photo {"fileId":9}"""))
            assertNull((model.screen.value as ListsScreen).photoBusy)
            // the list loaded again
            assertEquals(2, backend.requests.count { it == "GET /grocery/lists/1/items" })

            val apples = model.item("Apples").copy(photoId = 9)
            assertEquals("jpeg", model.photo(apples, 128)?.decodeToString())
            model.photo(apples, 128)
            assertEquals(1, backend.requests.count { it.startsWith("GET /grocery/items/10/photos/9") })

            model.removePhoto(apples)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("DELETE /grocery/items/10/photo"))
        }

    @Test
    fun `a photo the camera took while android ended the app is attached once the lists are back`() =
        runTest {
            backend.answers["POST /files"] = HttpStatusCode.Created to """{"id":9,"contentType":"image/jpeg","size":1,"url":"/api/v1/files/9","thumbnailUrl":"/api/v1/files/9/thumbnail","createdAt":"2026-10-30T08:00:00.000Z"}"""
            backend.answers["PUT /grocery/items/10/photo"] = HttpStatusCode.OK to item(10, 1, "Apples", 1)
            val store = signedInStore(openList = 1)
            backend.dispatcher = StandardTestDispatcher(testScheduler)
            val model = GroceryModel(store, backend::api, this, allowUrl = false, clock = Clock.fixed(now, ZoneOffset.UTC))
            // the answer of the camera comes before the app has started
            model.attachPhoto(10) { "jpeg".toByteArray() }
            runCurrent()
            assertTrue(backend.requests.none { it.startsWith("POST /files") })

            model.start()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/items/10/photo {"fileId":9}"""))
        }

    @Test
    fun `a photo that can not be attached says why, and one shown in full closes`() =
        runTest {
            backend.answers["POST /files"] =
                HttpStatusCode.BadRequest to """{"errors":[{"code":"files.unsupported_type","message":"Only images."}]}"""
            val model = signedIn()
            advanceUntilIdle()
            model.attachPhoto(10) { "nonsense".toByteArray() }
            advanceUntilIdle()
            assertEquals("Only images.", (model.screen.value as ListsScreen).error.english())
            assertTrue(backend.requests.none { it.startsWith("PUT") })

            // what the phone can not read is not sent
            model.attachPhoto(10) { null }
            advanceUntilIdle()
            assertEquals("The photo could not be read.", (model.screen.value as ListsScreen).error.english())
            assertEquals(1, backend.requests.count { it.startsWith("POST /files") })

            val apples = model.item("Apples").copy(photoId = 9)
            model.viewPhoto(apples)
            assertNull(model.photo(apples, null, full = true))
            assertNull((model.screen.value as ListsScreen).viewing)
        }

    @Test
    fun `the categories of a list are loaded with its items, on every load, an unknown one is none`() =
        runTest {
            backend.answers["GET /grocery/lists/1/items"] = HttpStatusCode.OK to page(item(10, 1, "Apples", 7), item(11, 1, "Milk", 2))
            val model = signedIn()
            advanceUntilIdle()
            model.refresh()
            advanceUntilIdle()
            assertEquals(2, backend.requests.count { it == "GET /grocery/lists/1/categories" })
            assertEquals(listOf("Dairy & eggs", GroceryModel.NO_CATEGORY.english()), (model.screen.value as ListsScreen).groups.map { it.label.english() })
            assertTrue(backend.requests.none { it == "GET /grocery/categories" })
        }

    @Test
    fun `the category of an item is chosen on a screen of its own, which leads straight back to the list`() =
        runTest {
            backend.answers["PUT /grocery/items/12/category"] = HttpStatusCode.OK to item(12, 1, "Candles", 2)
            backend.answers["PUT /grocery/items/11/category"] = HttpStatusCode.BadRequest to """{"errors":[{"code":"test.refused","message":"The list has no such category."}]}"""
            val model = signedIn()
            advanceUntilIdle()

            model.chooseCategory(model.item("Candles"))
            assertEquals("Candles", (model.screen.value as ListsScreen).choosing?.name)
            assertEquals(listOf(1, 2), (model.screen.value as ListsScreen).categories.map { it.id })
            // back without a change
            model.back()
            assertNull((model.screen.value as ListsScreen).choosing)
            assertEquals(1, (model.screen.value as ListsScreen).open)

            model.chooseCategory(model.item("Candles"))
            val loads = backend.requests.count { it == "GET /grocery/lists/1/items" }
            model.setCategory(2)
            assertNull((model.screen.value as ListsScreen).choosing)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/items/12/category {"categoryId":2}"""))
            assertEquals(loads + 1, backend.requests.count { it == "GET /grocery/lists/1/items" })

            // the one it has already: nothing is sent
            model.chooseCategory(model.item("Milk"))
            model.setCategory(2)
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("PUT /grocery/items/11/category") })

            // none, refused: the list says why
            model.chooseCategory(model.item("Milk"))
            model.setCategory(null)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/items/11/category {}"""))
            assertEquals("The list has no such category.", (model.screen.value as ListsScreen).error.english())
        }

    @Test
    fun `the settings of a list add, rename, move and delete its categories, each at once`() =
        runTest {
            backend.answers["POST /grocery/lists/1/categories"] = HttpStatusCode.Created to category(3, "Drinks", null)
            backend.answers["PUT /grocery/lists/1/categories/2"] = HttpStatusCode.OK to category(2, "Dairy", "🧀")
            backend.answers["PUT /grocery/lists/1/categories/order"] =
                HttpStatusCode.OK to page(category(2, "Dairy", "🧀"), category(1, "Fruit & vegetables", "🥦"), category(3, "Drinks", null))
            backend.answers["DELETE /grocery/lists/1/categories/1"] = HttpStatusCode.NoContent to ""
            backend.answers["POST /grocery/lists/2/categories"] = HttpStatusCode.Conflict to """{"errors":[{"code":"test.refused","message":"The list has a category of the name."}]}"""
            val model = signedIn()
            advanceUntilIdle()
            fun settings() = (model.screen.value as ListsScreen).settings!!

            model.openSettings(1)
            advanceUntilIdle()
            assertEquals(listOf("Fruit & vegetables", "Dairy & eggs"), settings().categories.map { it.name })

            model.editNewCategory(" Drinks ")
            model.addCategory()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /grocery/lists/1/categories {"name":"Drinks"}"""))
            assertEquals(listOf(1, 2, 3), settings().categories.map { it.id })
            assertEquals("", settings().newCategory)

            model.editCategoryName(2, "Dairy")
            model.saveCategoryName(2)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/lists/1/categories/2 {"name":"Dairy"}"""))
            assertEquals("Dairy", settings().categories[1].name)
            assertEquals(emptyMap<Int, String>(), settings().drafts)
            // unchanged, empty or too long: nothing is sent, the field says why
            val sent = backend.requests.size
            model.editCategoryName(2, "Dairy ")
            model.saveCategoryName(2)
            // the icon it has
            model.setCategoryIcon(2, null)
            model.editCategoryName(2, " ")
            model.saveCategoryName(2)
            advanceUntilIdle()
            assertTrue(backend.requests.drop(sent).none { it.startsWith("PUT") })
            assertEquals(setOf(2), settings().invalidNames)
            model.editCategoryName(2, "Dairy")
            assertEquals(emptySet<Int>(), settings().invalidNames)
            model.editNewCategory("x".repeat(33))
            model.addCategory()
            assertTrue(settings().newCategoryInvalid)
            model.editNewCategory("")
            assertTrue(!settings().newCategoryInvalid)

            // the first one up stays, the second one goes up
            model.moveCategory(1, up = true)
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("PUT /grocery/lists/1/categories/order") })
            model.moveCategory(2, up = true)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/lists/1/categories/order {"ids":[2,1,3]}"""))
            assertEquals(listOf(2, 1, 3), settings().categories.map { it.id })

            model.deleteCategory(1)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("DELETE /grocery/lists/1/categories/1"))
            assertEquals(listOf(2, 3), settings().categories.map { it.id })
            assertNull(settings().error)

            // a refused name stays on the settings with the reason, in the section of the categories,
            // which load again as the backend has them
            model.back()
            model.openSettings(2)
            advanceUntilIdle()
            val loads = backend.requests.count { it == "GET /grocery/lists/2/categories" }
            model.editNewCategory("Drinks")
            model.addCategory()
            advanceUntilIdle()
            assertEquals("The list has a category of the name.", settings().categoriesError.english())
            assertNull(settings().error)
            assertEquals("Drinks", settings().newCategory)
            assertEquals(0, settings().categoriesPending)
            assertEquals(loads + 1, backend.requests.count { it == "GET /grocery/lists/2/categories" })
        }

    @Test
    fun `a category dragged to another place shows there at once and the order of all of them is sent once (GROCERY-13)`() =
        runTest {
            backend.answers["GET /grocery/lists/1/categories"] =
                HttpStatusCode.OK to page(category(1, "Fruit", null), category(2, "Bakery", null), category(3, "Dairy", null), category(4, "Drinks", null))
            backend.answers["PUT /grocery/lists/1/categories/order"] =
                HttpStatusCode.OK to page(category(2, "Bakery", null), category(3, "Dairy", null), category(1, "Fruit", null), category(4, "Drinks", null))
            val model = signedIn()
            advanceUntilIdle()
            fun settings() = (model.screen.value as ListsScreen).settings!!
            model.openSettings(1)
            advanceUntilIdle()

            // dropped two places further down: shown before the answer, one request with every id
            model.moveCategory(1, to = 2)
            assertEquals(listOf(2, 3, 1, 4), settings().categories.map { it.id })
            assertEquals(1, settings().categoriesPending)
            advanceUntilIdle()
            assertEquals(
                listOf("""PUT /grocery/lists/1/categories/order {"ids":[2,3,1,4]}"""),
                backend.requests.filter { it.startsWith("PUT") },
            )
            assertEquals(listOf(2, 3, 1, 4), settings().categories.map { it.id })

            // dropped where it is, or past the ends: nothing; up and down from the menu stop at the ends
            model.moveCategory(1, to = 2)
            model.moveCategory(1, to = 7)
            model.moveCategory(2, up = true)
            model.moveCategory(4, up = false)
            advanceUntilIdle()
            assertEquals(1, backend.requests.count { it.startsWith("PUT") })

            // two drops before the first answer: the second request has both
            model.moveCategory(4, to = 0)
            model.moveCategory(3, up = false)
            assertEquals(listOf(4, 2, 1, 3), settings().categories.map { it.id })
            advanceUntilIdle()
            assertEquals(
                """PUT /grocery/lists/1/categories/order {"ids":[4,2,1,3]}""",
                backend.requests.filter { it.startsWith("PUT") }.last(),
            )
        }

    @Test
    fun `changes of the categories made while one is sent wait for it and are all sent, in their order`() =
        runTest {
            backend.answers["PUT /grocery/lists/1/categories/order"] =
                HttpStatusCode.OK to page(category(2, "Dairy & eggs", null), category(1, "Fruit & vegetables", "🥦"))
            backend.answers["PUT /grocery/lists/1/categories/1"] = HttpStatusCode.OK to category(1, "Fruit & vegetables", "🍎")
            backend.answers["PUT /grocery/lists/1/categories/2"] = HttpStatusCode.OK to category(2, "Milk", null)
            val model = signedIn()
            advanceUntilIdle()
            fun settings() = (model.screen.value as ListsScreen).settings!!
            model.openSettings(1)
            advanceUntilIdle()

            // all at once, before the first answer
            model.moveCategory(2, up = true)
            model.setCategoryIcon(1, "🍎")
            model.editCategoryName(2, "Milk")
            model.saveCategoryName(2)
            assertEquals(3, settings().categoriesPending)
            advanceUntilIdle()
            assertEquals(
                listOf(
                    """PUT /grocery/lists/1/categories/order {"ids":[2,1]}""",
                    """PUT /grocery/lists/1/categories/1 {"name":"Fruit & vegetables","icon":"🍎"}""",
                    """PUT /grocery/lists/1/categories/2 {"name":"Milk"}""",
                ),
                backend.requests.filter { it.startsWith("PUT") },
            )
            assertEquals(listOf("Milk", "Fruit & vegetables"), settings().categories.map { it.name })
            assertEquals(0, settings().categoriesPending)
        }

    @Test
    fun `a name typed and not yet saved is saved when the settings close, also by save`() =
        runTest {
            backend.answers["PUT /grocery/lists/1/categories/2"] = HttpStatusCode.OK to category(2, "Milk", null)
            backend.answers["PUT /grocery/lists/1/categories/1"] = HttpStatusCode.OK to category(1, "Fruit", "🥦")
            backend.answers["PUT /grocery/lists/1"] = HttpStatusCode.OK to list(1, "Default")
            val model = signedIn()
            advanceUntilIdle()

            model.openSettings(1)
            advanceUntilIdle()
            model.editCategoryName(2, "Milk")
            // a name that is not valid is not saved
            model.editCategoryName(1, " ")
            model.back()
            assertNull((model.screen.value as ListsScreen).settings)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/lists/1/categories/2 {"name":"Milk"}"""))
            assertTrue(backend.requests.none { it.startsWith("PUT /grocery/lists/1/categories/1") })

            model.openSettings(1)
            advanceUntilIdle()
            model.editCategoryName(1, "Fruit")
            model.saveSettings()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/lists/1/categories/1 {"name":"Fruit","icon":"🥦"}"""))
        }

    @Test
    fun `categories that can not be loaded show none but why, until they are loaded again`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()
            fun settings() = (model.screen.value as ListsScreen).settings!!
            backend.answers["GET /grocery/lists/1/categories"] =
                HttpStatusCode.InternalServerError to """{"errors":[{"code":"api.unexpected_error","message":"Something went wrong."}]}"""

            model.openSettings(1)
            advanceUntilIdle()
            assertTrue(!settings().categoriesLoaded)
            assertTrue(!settings().categoriesLoading)
            assertEquals("Something went wrong.", settings().categoriesError.english())

            backend.answers["GET /grocery/lists/1/categories"] = HttpStatusCode.OK to page(category(1, "Fruit & vegetables", "🥦"))
            model.refreshCategories()
            advanceUntilIdle()
            assertTrue(settings().categoriesLoaded)
            assertNull(settings().categoriesError)
            assertEquals(listOf(1), settings().categories.map { it.id })
        }

    @Test
    fun `a session that ended during a change of a category leads to the sign-in`() =
        runTest {
            backend.answers["POST /grocery/lists/1/categories"] = HttpStatusCode.Unauthorized to ""
            val model = signedIn()
            advanceUntilIdle()
            model.openSettings(1)
            advanceUntilIdle()
            model.editNewCategory("Drinks")
            model.addCategory()
            advanceUntilIdle()
            assertEquals("Your session ended, please sign in again.", (model.screen.value as Screen.SignIn).error.english())
        }

    @Test
    fun `the settings load the categories again on a change of their list, the names typed stay`() =
        runTest {
            backend.answers["GET /grocery/changes"] = HttpStatusCode.OK to ": listening\n\n${changed(2)}${changed(1)}"
            val model = signedIn()
            advanceUntilIdle()
            fun settings() = (model.screen.value as ListsScreen).settings!!
            model.openSettings(1)
            advanceUntilIdle()
            model.editCategoryName(2, "Mil")
            val loads = backend.requests.count { it == "GET /grocery/lists/1/categories" }
            backend.answers["GET /grocery/lists/1/categories"] =
                HttpStatusCode.OK to page(category(2, "Dairy & eggs", null), category(1, "Fruit & vegetables", "🥦"), category(3, "Drinks", null))

            model.startListening()
            advanceTimeBy(ChangeStream.RETRY_DELAY_MS / 2)
            runCurrent()
            model.stopListening()
            // the change of list 1, not the one of list 2; the list open loads too
            assertTrue(backend.requests.count { it == "GET /grocery/lists/1/categories" } >= loads + 1)
            assertEquals(listOf(2, 1, 3), settings().categories.map { it.id })
            assertEquals(mapOf(2 to "Mil"), settings().drafts)
        }

    @Test
    fun `the category screen follows the item as it is loaded, an item that is gone leads back to the list`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()
            model.chooseCategory(model.item("Candles"))

            // set to a category meanwhile by another user: choosing that one is no change
            backend.answers["GET /grocery/lists/1/items"] =
                HttpStatusCode.OK to page(item(10, 1, "Apples", 1), item(11, 1, "Milk", 2, 1.5, "l"), item(12, 1, "Candles", 2))
            model.refresh()
            advanceUntilIdle()
            assertEquals(2, (model.screen.value as ListsScreen).choosing?.categoryId)
            model.setCategory(2)
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("PUT /grocery/items/12/category") })

            // bought by another user: the list shows
            model.chooseCategory(model.item("Candles"))
            backend.answers["GET /grocery/lists/1/items"] = HttpStatusCode.OK to page(item(10, 1, "Apples", 1))
            model.refresh()
            advanceUntilIdle()
            assertNull((model.screen.value as ListsScreen).choosing)
            assertEquals(1, (model.screen.value as ListsScreen).open)
        }

    @Test
    fun `a check is not sent once the session ended, the sign-in is shown once`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()
            val infos = backend.requests.count { it == "GET /info" }
            model.check(model.item("Apples"))
            backend.answers["GET /grocery/lists"] = HttpStatusCode.Unauthorized to ""
            model.refresh()
            advanceUntilIdle()

            assertTrue(model.screen.value is Screen.SignIn)
            assertTrue(backend.requests.none { it.startsWith("DELETE") })
            // the sign-in asked the deployment once, the dropped check did not end the session again
            assertEquals(infos + 1, backend.requests.count { it == "GET /info" })
        }

    @Test
    fun `the settings opened from a list return to it, a deleted list leads to the home`() =
        runTest {
            backend.answers["PUT /grocery/lists/1"] = HttpStatusCode.OK to list(1, "Weekly")
            backend.answers["DELETE /grocery/lists/1"] = HttpStatusCode.NoContent to ""
            val model = signedIn(openList = 1)
            advanceUntilIdle()

            model.openSettings(1)
            model.back()
            assertEquals(1, (model.screen.value as ListsScreen).open)
            assertNull((model.screen.value as ListsScreen).settings)

            model.openSettings(1)
            model.editSettings("Weekly", shared = false, icon = null)
            model.saveSettings()
            advanceUntilIdle()
            assertEquals(1, (model.screen.value as ListsScreen).open)
            assertNull((model.screen.value as ListsScreen).settings)

            model.openSettings(1)
            backend.answers["GET /grocery/lists"] = HttpStatusCode.OK to page(list(2, "Party", shared = true))
            model.deleteList()
            advanceUntilIdle()
            assertNull((model.screen.value as ListsScreen).open)
        }

    @Test
    fun `the menu shows the name of the user, else their address`() =
        runTest {
            backend.answers["GET /auth/who-am-i"] = HttpStatusCode.OK to """{"username":"anna@example.org","name":"Anna","roles":["user"]}"""
            val named = model(signedInStore(openList = null).also { it.save(it.load().copy(email = "anna@example.org")) }).also { it.start() }
            advanceUntilIdle()
            assertEquals("Anna", (named.screen.value as ListsScreen).user)

            backend.answers["GET /auth/who-am-i"] = HttpStatusCode.OK to """{"username":"anna@example.org","name":null,"roles":["user"]}"""
            val unnamed = model(signedInStore(openList = null).also { it.save(it.load().copy(email = "anna@example.org")) }).also { it.start() }
            advanceUntilIdle()
            assertEquals("anna@example.org", (unnamed.screen.value as ListsScreen).user)
        }

    @Test
    fun `only a newer released version of the deployment is an update`() {
        assertEquals("1.4.3", newer("1.4.2", "1.4.3"))
        assertEquals("1.10.0", newer("1.9.9", "1.10.0"))
        assertEquals("2.0.0", newer("1.4.2", "2.0.0"))
        assertNull(newer("1.4.2", "1.4.2"))
        // an older deployment: android does not install an older version over a newer one
        assertNull(newer("1.4.2", "1.4.1"))
        // a build of no release, a deployment of no release or of no version
        assertNull(newer("latest", "1.4.3"))
        assertNull(newer("1.4.2", "latest"))
        assertNull(newer("1.4.2", "1.4.3-rc"))
        assertNull(newer("1.4.2", null))
    }

    @Test
    fun `the home offers the version of the deployment when it is newer, the update hands it to the installer`() =
        runTest {
            backend.answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi","version":"0.1.15"}"""
            val installed = mutableListOf<String>()
            val model = model(signedInStore(openList = null), appVersion = "0.1.14", updater = { installed += it }).also { it.start() }
            advanceUntilIdle()
            assertEquals(AppUpdate("0.1.15"), (model.screen.value as ListsScreen).update)

            model.update()
            assertEquals(AppUpdate("0.1.15", busy = true), (model.screen.value as ListsScreen).update)
            advanceUntilIdle()
            assertEquals(listOf("0.1.15"), installed)
            assertEquals(AppUpdate("0.1.15"), (model.screen.value as ListsScreen).update)

            // the same version offers nothing
            val current = model(signedInStore(openList = null), appVersion = "0.1.15").also { it.start() }
            advanceUntilIdle()
            assertNull((current.screen.value as ListsScreen).update)
        }

    @Test
    fun `an update that fails says why and can be tried again`() =
        runTest {
            backend.answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi","version":"0.1.15"}"""
            var failure: Exception = java.io.IOException("offline")
            val model = model(signedInStore(openList = null), appVersion = "0.1.14", updater = { throw failure }).also { it.start() }
            advanceUntilIdle()

            model.update()
            advanceUntilIdle()
            assertEquals(
                AppUpdate("0.1.15", error = uiText(CoreR.string.error_update_not_downloaded)),
                (model.screen.value as ListsScreen).update,
            )

            failure = UpdateException(uiText(CoreR.string.error_update_damaged, "0.1.15"))
            model.update()
            advanceUntilIdle()
            assertEquals("The download of version 0.1.15 is damaged, please try again.", (model.screen.value as ListsScreen).update?.error.english())
        }

    private fun info(version: String?) =
        HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"${version?.let { ",\"version\":\"$it\"" } ?: ""}}"""

    private fun notice(model: GroceryModel) = (model.screen.value as ListsScreen).updateNotice.english()

    @Test
    fun `the check for updates offers a newer version, else says why there is none`() =
        runTest {
            backend.answers["GET /info"] = info("0.1.15")
            val model = model(signedInStore(openList = null), appVersion = "0.1.15").also { it.start() }
            advanceUntilIdle()

            model.checkUpdate()
            assertEquals("Checking for updates…", notice(model))
            advanceUntilIdle()
            assertEquals("The app is up-to-date, version 0.1.15.", notice(model))
            model.dismissUpdateNotice()
            assertNull(notice(model))

            // the deployment was updated since the start
            backend.answers["GET /info"] = info("0.1.16")
            model.checkUpdate()
            advanceUntilIdle()
            assertEquals(AppUpdate("0.1.16"), (model.screen.value as ListsScreen).update)
            assertNull(notice(model))

            backend.answers["GET /info"] = info("latest")
            val current = model(signedInStore(openList = null), appVersion = "0.1.15").also { it.start() }
            advanceUntilIdle()
            current.checkUpdate()
            advanceUntilIdle()
            assertEquals("Your SAK instance runs no release, there is no update for the app.", notice(current))

            val debug = model(signedInStore(openList = null), appVersion = "latest").also { it.start() }
            advanceUntilIdle()
            debug.checkUpdate()
            advanceUntilIdle()
            assertEquals("This build of the app is no release, it is not updated.", notice(debug))
        }

    @Test
    fun `the build of google play has no updater and offers no update, google play updates it`() =
        runTest {
            backend.answers["GET /info"] = info("0.1.15")
            val model = model(signedInStore(openList = null), appVersion = "0.1.14", updater = null).also { it.start() }
            advanceUntilIdle()
            assertFalse(model.updates)
            assertNull((model.screen.value as ListsScreen).update)

            model.checkUpdate()
            model.update()
            advanceUntilIdle()
            assertNull((model.screen.value as ListsScreen).update)
            assertNull(notice(model))
        }

    @Test
    fun `a check for updates that fails says why`() =
        runTest {
            backend.answers["GET /info"] = info("0.1.15")
            val model = model(signedInStore(openList = null), appVersion = "0.1.15").also { it.start() }
            advanceUntilIdle()
            backend.answers["GET /info"] =
                HttpStatusCode.InternalServerError to """{"errors":[{"code":"api.unexpected_error","message":"Something went wrong."}]}"""
            model.checkUpdate()
            advanceUntilIdle()
            assertEquals("Something went wrong.", notice(model))
        }

    @Test
    fun `the api asks the backend for its texts in the languages of the phone`() =
        runTest {
            backend.dispatcher = StandardTestDispatcher(testScheduler)
            backend.api(URL, null, "de-DE,en-US").info()
            backend.api(URL, null).info()
            assertEquals(listOf("de-DE,en-US", null), backend.languages)
        }

    @Test
    fun `a change of a category queued when the settings close keeps what the category has, its icon too`() =
        runTest {
            backend.answers["PUT /grocery/lists/1/categories/1"] = HttpStatusCode.OK to category(1, "Fruit", "🥦")
            backend.answers["PUT /grocery/lists/1/categories/order"] = HttpStatusCode.OK to page()
            val model = signedIn()
            advanceUntilIdle()

            // a name typed, back at once: the icon goes with it
            model.openSettings(1)
            advanceUntilIdle()
            model.editCategoryName(1, "Fruit")
            model.back()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/lists/1/categories/1 {"name":"Fruit","icon":"🥦"}"""))

            // a rename, then an icon, then back: the icon keeps the new name
            model.openSettings(1)
            advanceUntilIdle()
            model.editCategoryName(1, "Obst")
            model.saveCategoryName(1)
            model.setCategoryIcon(1, "🍎")
            model.moveCategory(2, 0)
            model.back()
            assertNull(model.lists().settings)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/lists/1/categories/1 {"name":"Obst","icon":"🥦"}"""))
            assertTrue(backend.requests.contains("""PUT /grocery/lists/1/categories/1 {"name":"Obst","icon":"🍎"}"""))
            // a move queued at the close is sent too, with the order of then
            assertTrue(backend.requests.contains("""PUT /grocery/lists/1/categories/order {"ids":[2,1]}"""))
        }

    @Test
    fun `two moves in a row each take the order of then, the second one the order the first one made`() =
        runTest {
            backend.answers["GET /grocery/lists/1/categories"] =
                HttpStatusCode.OK to page(category(1, "A", null), category(2, "B", null), category(3, "C", null), category(4, "D", null))
            backend.answers["PUT /grocery/lists/1/categories/order"] = HttpStatusCode.OK to page()
            val model = signedIn()
            advanceUntilIdle()
            model.openSettings(1)
            advanceUntilIdle()

            model.moveCategory(4, 0)
            assertEquals(listOf(4, 1, 2, 3), model.lists().settings!!.categories.map { it.id })
            // C one row up: from where it is now
            model.moveCategory(3, 1)
            assertEquals(listOf(4, 3, 1, 2), model.lists().settings!!.categories.map { it.id })
            advanceUntilIdle()
            val orders = backend.requests.filter { it.startsWith("PUT /grocery/lists/1/categories/order") }
            assertEquals(
                listOf("""PUT /grocery/lists/1/categories/order {"ids":[4,1,2,3]}""", """PUT /grocery/lists/1/categories/order {"ids":[4,3,1,2]}"""),
                orders,
            )
        }

    @Test
    fun `a photo while another one is sent waits for it, none is dropped`() =
        runTest {
            backend.answers["POST /files"] = HttpStatusCode.Created to """{"id":9,"contentType":"image/jpeg","size":1,"url":"/api/v1/files/9","thumbnailUrl":"/api/v1/files/9/thumbnail","createdAt":"2026-10-30T08:00:00.000Z"}"""
            backend.answers["PUT /grocery/items/10/photo"] = HttpStatusCode.OK to item(10, 1, "Apples", 1)
            backend.answers["PUT /grocery/items/11/photo"] = HttpStatusCode.OK to item(11, 1, "Milk", 2)
            val model = signedIn()
            advanceUntilIdle()

            model.attachPhoto(10) { "a".toByteArray() }
            model.attachPhoto(11) { "b".toByteArray() }
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/items/10/photo {"fileId":9}"""))
            assertTrue(backend.requests.contains("""PUT /grocery/items/11/photo {"fileId":9}"""))
            assertNull(model.lists().photoBusy)
        }

    @Test
    fun `a photo the camera took after the session ended is dropped, not kept for a later sign-in`() =
        runTest {
            backend.dispatcher = StandardTestDispatcher(testScheduler)
            val model = GroceryModel(MemorySessionStore(Stored(apiUrl = URL, address = "https://sak.example.org")), backend::api, this, allowUrl = false)
            var discarded = false
            model.attachPhoto(10, discard = { discarded = true }) { "jpeg".toByteArray() }
            model.start()
            advanceUntilIdle()
            assertTrue(model.screen.value is Screen.SignIn)
            assertTrue(discarded)
            assertTrue(backend.requests.none { it.startsWith("POST /files") })
        }

    @Test
    fun `the dialogs and the photo of a list that went go with it`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()
            val apples = model.item("Apples")
            model.askRename(apples)
            model.askDelete(apples)
            model.viewPhoto(apples)

            // the owner deleted the list meanwhile
            backend.answers["GET /grocery/lists"] = HttpStatusCode.OK to page(list(2, "Party", shared = true))
            model.refresh()
            advanceUntilIdle()
            val lists = model.lists()
            assertNull(lists.open)
            assertNull(lists.renaming)
            assertNull(lists.deleting)
            assertNull(lists.viewing)
        }

    @Test
    fun `the settings load their categories again when the app comes back`() =
        runTest {
            backend.answers["GET /grocery/changes"] = HttpStatusCode.OK to ": listening\n\n"
            val model = signedIn()
            advanceUntilIdle()
            model.openSettings(1)
            advanceUntilIdle()
            val loads = { backend.requests.count { it == "GET /grocery/lists/1/categories" } }
            val before = loads()

            model.startListening()
            runCurrent()
            // the first stream ended at once; the next one loads what it may have missed
            advanceTimeBy(ChangeStream.RETRY_DELAY_MS + 1)
            runCurrent()
            assertTrue(loads() > before)
            model.stopListening()
        }

    @Test
    fun `the list suggests names for what is typed, one chosen brings its quantity and category (GROCERY-11)`() =
        runTest {
            backend.answers["GET /grocery/lists/1/suggestions"] =
                HttpStatusCode.OK to page("""{"id":20,"name":"Milk","amount":1.5,"unit":"l","categoryId":2}""")
            backend.answers["POST /grocery/items"] = HttpStatusCode.Created to item(13, 1, "Milk", 2, 1.5, "l")
            val model = signedIn()
            advanceUntilIdle()

            model.typed("mi")
            runCurrent()
            // after a short pause of typing only
            assertTrue(backend.queries.none { it.contains("/suggestions") })
            advanceTimeBy(GroceryModel.SUGGEST_DELAY_MS + 1)
            advanceUntilIdle()
            assertTrue(backend.queries.any { it.startsWith("GET /grocery/lists/1/suggestions?text=mi") })
            assertEquals(listOf("Milk"), model.lists().suggestions.map { it.name })

            model.chooseSuggestion(model.lists().suggestions.single())
            assertEquals("Milk", model.lists().itemName)
            assertEquals(emptyList<Any>(), model.lists().suggestions)
            model.addItem()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /grocery/items {"listId":1,"name":"Milk","amount":1.5,"unit":"l","categoryId":2}"""))
            assertNull(model.lists().chosen)
            assertEquals("", model.lists().itemName)
        }

    @Test
    fun `a name changed after a suggestion was chosen goes without its quantity and category`() =
        runTest {
            backend.answers["GET /grocery/lists/1/suggestions"] =
                HttpStatusCode.OK to page("""{"id":20,"name":"Milk","amount":1.5,"unit":"l","categoryId":2}""")
            backend.answers["POST /grocery/items"] = HttpStatusCode.Created to item(13, 1, "Milch", null)
            val model = signedIn()
            advanceUntilIdle()
            model.typed("mi")
            advanceUntilIdle()
            model.chooseSuggestion(model.lists().suggestions.single())
            model.typed("Milch")
            assertNull(model.lists().chosen)
            model.addItem()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /grocery/items {"listId":1,"name":"Milch"}"""))

            val asked = backend.queries.count { it.contains("/suggestions") }
            model.typed("")
            advanceUntilIdle()
            assertEquals(asked, backend.queries.count { it.contains("/suggestions") })
        }

    @Test
    fun `a check that waits is sent when the app goes to the background`() =
        runTest {
            backend.answers["POST /grocery/items/10/check"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()
            model.check(model.item("Apples"))
            model.stopped()
            runCurrent()
            assertTrue(backend.requests.contains("POST /grocery/items/10/check"))
            assertNull(model.lists().undo)
        }

    @Test
    fun `the photos kept fetched have at most their bytes, the ones used last stay`() {
        val cache = PhotoCache(10)
        cache.put("a", ByteArray(4))
        cache.put("b", ByteArray(4))
        cache.get("a")
        cache.put("c", ByteArray(4))
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertEquals(8L, cache.size())
        // larger than all of it: not kept
        cache.put("d", ByteArray(11))
        assertNull(cache.get("d"))
    }
}
