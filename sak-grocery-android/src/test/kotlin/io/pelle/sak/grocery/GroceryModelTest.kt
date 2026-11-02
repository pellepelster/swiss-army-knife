package io.pelle.sak.grocery

import io.ktor.http.HttpStatusCode
import io.pelle.sak.grocery.session.MemorySessionStore
import io.pelle.sak.grocery.session.PendingLogin
import io.pelle.sak.grocery.session.Stored
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
    ) = """{"id":$id,"listId":$listId,"name":"$name","amount":$amount,"unit":${unit?.let { "\"$it\"" }},"categoryId":$categoryId}"""

    private fun category(
        id: Int,
        name: String,
        icon: String?,
    ) = """{"id":$id,"name":"$name","icon":${icon?.let { "\"$it\"" }}}"""

    private fun TestScope.model(
        store: MemorySessionStore,
        at: Instant = now,
        appVersion: String = "latest",
        updater: Updater = Updater { error("no update expected") },
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
            assertTrue((model.screen.value as Screen.Deployment).error.english()!!.contains("base domain"))

            model.connect("example.org")
            advanceUntilIdle()
            assertEquals(Screen.SignIn("family", multiUser = true), model.screen.value)
            assertEquals(Stored(apiUrl = URL, deployment = "family"), store.load())
        }

    @Test
    fun `the password signs in to the home with all lists, a list opens with its items by category, back leads home`() =
        runTest {
            val store = MemorySessionStore(Stored(apiUrl = URL, deployment = "family"))
            val model = model(store).also { it.start() }
            advanceUntilIdle()

            model.signInWithPassword("a password of sixteen")
            advanceUntilIdle()

            assertTrue(backend.requests.contains("""POST /auth/login/password {"password":"a password of sixteen"}"""))
            val home = model.screen.value as Screen.Lists
            assertEquals("admin", home.user)
            assertNull(home.open)
            assertEquals(listOf("Default", "Party"), home.lists.map { it.name })
            assertTrue(backend.requests.none { it.contains("/items") })
            assertEquals("password-session", store.load().token)

            model.open(1)
            advanceUntilIdle()
            val lists = model.screen.value as Screen.Lists
            assertEquals(1, lists.open)
            assertEquals(listOf("Fruit & vegetables", "Dairy & eggs", GroceryModel.NO_CATEGORY.english()), lists.groups.map { it.label.english() })
            assertEquals(listOf("🥦", null, null), lists.groups.map { it.icon })
            assertEquals(listOf("Apples", "Milk", "Candles"), lists.groups.flatMap { group -> group.items.map { it.name } })
            assertEquals("password-session", backend.tokens["/grocery/lists/1/items"])
            assertEquals(1, store.load().openList)

            model.back()
            advanceUntilIdle()
            assertNull((model.screen.value as Screen.Lists).open)
            assertEquals(emptyList<ItemGroup>(), (model.screen.value as Screen.Lists).groups)
            assertNull(store.load().openList)
        }

    @Test
    fun `a login link is taken only for a login this app asked for, within 15 minutes`() =
        runTest {
            val store = MemorySessionStore(Stored(apiUrl = URL, deployment = "family"))
            val model = model(store).also { it.start() }
            advanceUntilIdle()

            // a link nobody asked for in this app
            model.signInWithLink("foreign")
            advanceUntilIdle()
            assertEquals(GroceryModel.NOT_ASKED, (model.screen.value as Screen.SignIn).error)
            assertTrue(backend.requests.none { it.startsWith("POST /auth/login/exchange") })

            model.requestLink(" pelle@pelle.io ")
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /auth/login/link {"email":"pelle@pelle.io","client":"android"}"""))
            assertEquals("pelle@pelle.io", (model.screen.value as Screen.SignIn).linkSentTo)
            assertEquals(PendingLogin(URL, now), store.load().pending)

            model.signInWithLink("login-token")
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /auth/login/exchange {"token":"login-token"}"""))
            assertEquals("pelle@pelle.io", (model.screen.value as Screen.Lists).user)
            // used once
            assertNull(store.load().pending)
        }

    @Test
    fun `a pending login older than 15 minutes is no longer taken`() =
        runTest {
            val store =
                MemorySessionStore(Stored(apiUrl = URL, deployment = "family", pending = PendingLogin(URL, now.minusSeconds(16 * 60))))
            val model = model(store).also { it.start() }
            model.signInWithLink("login-token")
            advanceUntilIdle()
            assertEquals(GroceryModel.NOT_ASKED, (model.screen.value as Screen.SignIn).error)
        }

    @Test
    fun `a session that ended leads to the sign-in, the deployment stays`() =
        runTest {
            val store =
                MemorySessionStore(
                    Stored(apiUrl = URL, deployment = "family", token = "old", email = "admin", expiresAt = now.plusSeconds(60)),
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
                MemorySessionStore(Stored(apiUrl = URL, deployment = "family", token = "old", email = "admin", expiresAt = now.minusSeconds(1)))
            model(expired).also { it.start() }.let {
                advanceUntilIdle()
                assertTrue(it.screen.value is Screen.SignIn)
            }

            val store =
                MemorySessionStore(Stored(apiUrl = URL, deployment = "family", token = "t", email = "admin", expiresAt = now.plusSeconds(60)))
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.signOut()
            advanceUntilIdle()
            assertTrue(model.screen.value is Screen.SignIn)
            assertEquals(Stored(apiUrl = URL, deployment = "family"), store.load())
        }

    @Test
    fun `an error of the backend is shown with its message`() =
        runTest {
            backend.answers["POST /auth/login/password"] = HttpStatusCode.Unauthorized to ""
            val store = MemorySessionStore(Stored(apiUrl = URL, deployment = "family"))
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            model.signInWithPassword("wrong")
            advanceUntilIdle()
            assertEquals("The password is wrong.", (model.screen.value as Screen.SignIn).error.english())

            backend.answers["POST /auth/login/password"] =
                HttpStatusCode.TooManyRequests to """{"errors":[{"message":"Slow down."}]}"""
            model.signInWithPassword("again")
            advanceUntilIdle()
            assertEquals("Slow down.", (model.screen.value as Screen.SignIn).error.english())
        }

    private fun signedInStore(openList: Int? = 1) =
        MemorySessionStore(
            Stored(apiUrl = URL, deployment = "family", token = "t", email = "admin", expiresAt = now.plusSeconds(60), openList = openList),
        )

    // signed in, the list open last open again
    private fun TestScope.signedIn(openList: Int? = 1): GroceryModel = model(signedInStore(openList)).also { it.start() }

    @Test
    fun `the app opens the list open when it was left, the home when that list is gone, the sign-out forgets it`() =
        runTest {
            val store = signedInStore(openList = 2)
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            assertEquals(2, (model.screen.value as Screen.Lists).open)
            assertTrue(backend.requests.contains("GET /grocery/lists/2/items"))

            val gone = signedInStore(openList = 7)
            val again = model(gone).also { it.start() }
            advanceUntilIdle()
            assertNull((again.screen.value as Screen.Lists).open)
            assertNull(gone.load().openList)

            model.signOut()
            advanceUntilIdle()
            assertNull(store.load().openList)
        }

    @Test
    fun `an item typed at the top goes to the list shown, the field is empty again and the items are loaded anew`() =
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
            assertTrue((model.screen.value as Screen.Lists).adding)
            advanceUntilIdle()

            assertTrue(backend.requests.contains("""POST /grocery/items {"listId":2,"name":"Cake"}"""))
            assertEquals("t", backend.tokens["/grocery/items"])
            val lists = model.screen.value as Screen.Lists
            assertEquals("", lists.itemName)
            assertEquals(false, lists.adding)
            assertEquals(listOf("Cake"), lists.groups.flatMap { group -> group.items.map { it.name } })
        }

    @Test
    fun `an item the backend refuses keeps its name and shows why, an ended session leads to the sign-in`() =
        runTest {
            backend.answers["POST /grocery/items"] = HttpStatusCode.BadRequest to """{"errors":[{"message":"The name is too long."}]}"""
            val model = signedIn()
            advanceUntilIdle()

            model.typed("Bread")
            model.addItem()
            advanceUntilIdle()
            val lists = model.screen.value as Screen.Lists
            assertEquals("The name is too long.", lists.error.english())
            assertEquals("Bread", lists.itemName)
            assertEquals(false, lists.adding)

            backend.answers["POST /grocery/items"] = HttpStatusCode.Unauthorized to ""
            model.addItem()
            advanceUntilIdle()
            assertEquals("Your session ended, please sign in again.", (model.screen.value as Screen.SignIn).error.english())
        }

    private fun GroceryModel.item(name: String) =
        (screen.value as Screen.Lists).groups.flatMap { it.items }.first { it.name == name }

    private fun GroceryModel.names() = (screen.value as Screen.Lists).groups.flatMap { group -> group.items.map { it.name } }

    @Test
    fun `a checked item is struck through and goes when its undo ran out, the backend keeps it as bought`() =
        runTest {
            backend.answers["POST /grocery/items/10/check"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()

            model.check(model.item("Apples"))
            advanceTimeBy(UNDO_MS - 1)
            runCurrent()
            val struck = model.screen.value as Screen.Lists
            assertEquals(setOf(10), struck.struck)
            assertEquals("Apples bought", struck.undo?.text.english())
            assertEquals(listOf("Apples", "Milk", "Candles"), model.names())
            assertTrue(backend.requests.none { it.endsWith("/check") })

            advanceUntilIdle()
            val lists = model.screen.value as Screen.Lists
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
            assertFalse((model.screen.value as Screen.Lists).showBought)
            assertTrue(backend.queries.filter { it.startsWith("GET /grocery/lists/1/items") }.none { "checked=true" in it })

            val bought = """{"id":13,"listId":1,"name":"Bread","categoryId":null,"checkedAt":"2026-10-30T08:00:00.000Z"}"""
            backend.answers["GET /grocery/lists/1/items"] =
                HttpStatusCode.OK to page(item(10, 1, "Apples", 1), item(11, 1, "Milk", 2, 1.5, "l"), item(12, 1, "Candles", null), bought)
            model.toggleBought()
            advanceUntilIdle()
            assertTrue((model.screen.value as Screen.Lists).showBought)
            assertTrue("checked=true" in backend.queries.last { it.startsWith("GET /grocery/lists/1/items") })
            assertEquals(listOf("Apples", "Milk", "Candles", "Bread"), model.names())

            // a bought item is checked already, a tap does nothing
            model.check(model.item("Bread"))
            assertEquals(emptySet<Int>(), (model.screen.value as Screen.Lists).struck)
            // an item checked while they show stays, as bought, after the ones to buy of its group
            model.check(model.item("Apples"))
            advanceTimeBy(UNDO_MS + 1)
            runCurrent()
            assertNotNull(model.item("Apples").checkedAt)
            assertEquals(listOf("POST /grocery/items/10/check"), backend.requests.filter { it.endsWith("/check") })

            // hidden again
            model.toggleBought()
            advanceUntilIdle()
            assertFalse((model.screen.value as Screen.Lists).showBought)
            assertFalse("checked=true" in backend.queries.last { it.startsWith("GET /grocery/lists/1/items") })
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
            assertEquals(emptySet<Int>(), (model.screen.value as Screen.Lists).struck)

            model.check(model.item("Apples"))
            model.check(model.item("Milk"))
            runCurrent()
            assertEquals(listOf("POST /grocery/items/10/check"), backend.requests.filter { it.endsWith("/check") })
            assertEquals(setOf(11), (model.screen.value as Screen.Lists).struck)

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
            assertNull((model.screen.value as Screen.Lists).error)
            // the list loads again as the backend has it
            assertEquals(2, backend.requests.count { it.startsWith("GET /grocery/lists/1/items") })

            model.deleteItem(model.item("Milk"))
            advanceUntilIdle()
            assertEquals("The item could not be deleted.", (model.screen.value as Screen.Lists).error.english())
        }

    @Test
    fun `a check the backend refuses shows why and the list as the backend has it`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()

            model.check(model.item("Apples"))
            advanceUntilIdle()
            assertEquals("The item could not be checked.", (model.screen.value as Screen.Lists).error.english())
            assertEquals(listOf("Apples", "Milk", "Candles"), model.names())
        }

    @Test
    fun `the plus of the home creates a list that opens, a refused name stays with the reason, back leads home`() =
        runTest {
            backend.answers["POST /grocery/lists"] = HttpStatusCode.Conflict to """{"errors":[{"message":"A list of the name exists."}]}"""
            val store = signedInStore(openList = null)
            val model = model(store).also { it.start() }
            advanceUntilIdle()

            model.newList()
            model.editSettings(" Party ", shared = true, icon = "🎉")
            model.saveSettings()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /grocery/lists {"name":"Party","shared":true,"icon":"🎉"}"""))
            assertEquals(ListSettings(name = " Party ", shared = true, icon = "🎉", error = UiText.Plain("A list of the name exists.")), (model.screen.value as Screen.Lists).settings)

            model.back()
            assertNull((model.screen.value as Screen.Lists).settings)

            backend.answers["POST /grocery/lists"] = HttpStatusCode.Created to list(3, "Hardware")
            backend.answers["GET /grocery/lists/3/items"] = HttpStatusCode.OK to page()
            backend.answers["GET /grocery/lists"] =
                HttpStatusCode.OK to page(list(1, "Default"), list(2, "Party", shared = true), list(3, "Hardware"))
            model.newList()
            model.editSettings("Hardware", shared = false, icon = null)
            model.saveSettings()
            advanceUntilIdle()
            val lists = model.screen.value as Screen.Lists
            assertNull(lists.settings)
            assertEquals(3, lists.open)
            assertEquals(3, store.load().openList)
        }

    @Test
    fun `the sign-in follows the user mode, the multi user mode offers the password login after the e-mail login`() =
        runTest {
            val store = MemorySessionStore(Stored(apiUrl = URL, deployment = "family"))
            val multi = model(store).also { it.start() }
            advanceUntilIdle()
            val signIn = multi.screen.value as Screen.SignIn
            assertEquals(true, signIn.multiUser)
            assertEquals(false, signIn.withPassword)
            multi.usePassword(true)
            assertEquals(true, (multi.screen.value as Screen.SignIn).withPassword)
            multi.usePassword(false)
            assertEquals(false, (multi.screen.value as Screen.SignIn).withPassword)

            backend.answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"single"}"""
            val single = model(MemorySessionStore(Stored(apiUrl = URL, deployment = "family"))).also { it.start() }
            advanceUntilIdle()
            assertEquals(Screen.SignIn("family", multiUser = false, withPassword = true), single.screen.value)
            // no e-mail login to go to
            single.usePassword(false)
            assertEquals(true, (single.screen.value as Screen.SignIn).withPassword)
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
            advanceTimeBy(GroceryModel.RETRY_DELAY_MS / 2)
            runCurrent()
            // list 2 is not open, list 1 is
            assertEquals(2, loads())
            assertEquals("t", backend.tokens["/grocery/changes"])

            // the stream ended; the next one loads once for what it may have missed, once for its change
            advanceTimeBy(GroceryModel.RETRY_DELAY_MS)
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
            advanceTimeBy(GroceryModel.RETRY_DELAY_MS / 2)
            runCurrent()
            assertEquals(2, backend.requests.count { it == "GET /grocery/lists" })

            backend.answers["GET /grocery/changes"] = HttpStatusCode.Unauthorized to ""
            advanceTimeBy(GroceryModel.RETRY_DELAY_MS)
            runCurrent()
            assertEquals("Your session ended, please sign in again.", (model.screen.value as Screen.SignIn).error.english())
            model.stopListening()
        }

    @Test
    fun `the menu of an own list opens its settings, they change its icon and delete it, the last own list stays`() =
        runTest {
            backend.answers["GET /grocery/lists"] =
                HttpStatusCode.OK to page(list(1, "Default"), list(2, "Party", shared = true), """{"id":3,"name":"Theirs","shared":true,"mine":false,"items":0}""")
            backend.answers["PUT /grocery/lists/2"] = HttpStatusCode.OK to list(2, "Party", shared = true)
            backend.answers["DELETE /grocery/lists/2"] = HttpStatusCode.NoContent to ""
            val model = signedIn(openList = null)
            advanceUntilIdle()

            // not the user's own: no settings
            model.openSettings(3)
            assertNull((model.screen.value as Screen.Lists).settings)

            model.openSettings(2)
            // its categories loading
            assertEquals(ListSettings(2, "Party", shared = true, categoriesLoading = true), (model.screen.value as Screen.Lists).settings)
            model.editSettings("Party", shared = true, icon = "🎉")
            model.saveSettings()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/lists/2 {"name":"Party","shared":true,"icon":"🎉"}"""))
            assertNull((model.screen.value as Screen.Lists).settings)

            model.openSettings(2)
            model.deleteList()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("DELETE /grocery/lists/2"))
            assertNull((model.screen.value as Screen.Lists).settings)

            // the only own list
            backend.answers["GET /grocery/lists"] = HttpStatusCode.OK to page(list(1, "Default"))
            model.refresh()
            advanceUntilIdle()
            model.openSettings(1)
            assertEquals(true, (model.screen.value as Screen.Lists).settings?.last)
            model.deleteList()
            advanceUntilIdle()
            assertTrue(backend.requests.none { it == "DELETE /grocery/lists/1" })
        }

    @Test
    fun `the menu of a row on the home deletes an own list, not one of another user and not the last own one`() =
        runTest {
            backend.answers["GET /grocery/lists"] =
                HttpStatusCode.OK to page(list(1, "Default"), list(2, "Party"), """{"id":3,"name":"Theirs","shared":true,"mine":false,"items":0}""")
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
            assertEquals(listOf("Default"), (model.screen.value as Screen.Lists).lists.map { it.name })

            // the only own list stays
            model.deleteList(1)
            advanceUntilIdle()
            assertTrue(backend.requests.none { it == "DELETE /grocery/lists/1" })
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
            backend.answers["POST /files"] = HttpStatusCode.Created to """{"id":9}"""
            backend.answers["PUT /grocery/items/10/photo"] = HttpStatusCode.OK to item(10, 1, "Apples", 1)
            backend.answers["DELETE /grocery/items/10/photo"] = HttpStatusCode.OK to item(10, 1, "Apples", 1)
            backend.answers["GET /grocery/items/10/photos/9"] = HttpStatusCode.OK to "jpeg"
            val model = signedIn()
            advanceUntilIdle()

            model.attachPhoto(10) { "jpeg".toByteArray() }
            advanceUntilIdle()
            assertTrue(backend.requests.contains("POST /files jpeg"))
            assertTrue(backend.requests.contains("""PUT /grocery/items/10/photo {"fileId":9}"""))
            assertNull((model.screen.value as Screen.Lists).photoBusy)
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
            backend.answers["POST /files"] = HttpStatusCode.Created to """{"id":9}"""
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
            assertEquals("Only images.", (model.screen.value as Screen.Lists).error.english())
            assertTrue(backend.requests.none { it.startsWith("PUT") })

            // what the phone can not read is not sent
            model.attachPhoto(10) { null }
            advanceUntilIdle()
            assertEquals("The photo could not be read.", (model.screen.value as Screen.Lists).error.english())
            assertEquals(1, backend.requests.count { it.startsWith("POST /files") })

            val apples = model.item("Apples").copy(photoId = 9)
            model.viewPhoto(apples)
            assertNull(model.photo(apples, null, full = true))
            assertNull((model.screen.value as Screen.Lists).viewing)
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
            assertEquals(listOf("Dairy & eggs", GroceryModel.NO_CATEGORY.english()), (model.screen.value as Screen.Lists).groups.map { it.label.english() })
            assertTrue(backend.requests.none { it == "GET /grocery/categories" })
        }

    @Test
    fun `the category of an item is chosen on a screen of its own, which leads straight back to the list`() =
        runTest {
            backend.answers["PUT /grocery/items/12/category"] = HttpStatusCode.OK to item(12, 1, "Candles", 2)
            backend.answers["PUT /grocery/items/11/category"] = HttpStatusCode.BadRequest to """{"errors":[{"message":"The list has no such category."}]}"""
            val model = signedIn()
            advanceUntilIdle()

            model.chooseCategory(model.item("Candles"))
            assertEquals("Candles", (model.screen.value as Screen.Lists).choosing?.name)
            assertEquals(listOf(1, 2), (model.screen.value as Screen.Lists).categories.map { it.id })
            // back without a change
            model.back()
            assertNull((model.screen.value as Screen.Lists).choosing)
            assertEquals(1, (model.screen.value as Screen.Lists).open)

            model.chooseCategory(model.item("Candles"))
            val loads = backend.requests.count { it == "GET /grocery/lists/1/items" }
            model.setCategory(2)
            assertNull((model.screen.value as Screen.Lists).choosing)
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
            assertTrue(backend.requests.contains("""PUT /grocery/items/11/category {"categoryId":null}"""))
            assertEquals("The list has no such category.", (model.screen.value as Screen.Lists).error.english())
        }

    @Test
    fun `the settings of a list add, rename, move and delete its categories, each at once`() =
        runTest {
            backend.answers["POST /grocery/lists/1/categories"] = HttpStatusCode.Created to category(3, "Drinks", null)
            backend.answers["PUT /grocery/lists/1/categories/2"] = HttpStatusCode.OK to category(2, "Dairy", "🧀")
            backend.answers["PUT /grocery/lists/1/categories/order"] =
                HttpStatusCode.OK to page(category(2, "Dairy", "🧀"), category(1, "Fruit & vegetables", "🥦"), category(3, "Drinks", null))
            backend.answers["DELETE /grocery/lists/1/categories/1"] = HttpStatusCode.NoContent to ""
            backend.answers["POST /grocery/lists/2/categories"] = HttpStatusCode.Conflict to """{"errors":[{"message":"The list has a category of the name."}]}"""
            val model = signedIn()
            advanceUntilIdle()
            fun settings() = (model.screen.value as Screen.Lists).settings!!

            model.openSettings(1)
            advanceUntilIdle()
            assertEquals(listOf("Fruit & vegetables", "Dairy & eggs"), settings().categories.map { it.name })

            model.editNewCategory(" Drinks ")
            model.addCategory()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /grocery/lists/1/categories {"name":"Drinks","icon":null}"""))
            assertEquals(listOf(1, 2, 3), settings().categories.map { it.id })
            assertEquals("", settings().newCategory)

            model.editCategoryName(2, "Dairy")
            model.saveCategoryName(2)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/lists/1/categories/2 {"name":"Dairy","icon":null}"""))
            assertEquals("Dairy", settings().categories[1].name)
            assertEquals(emptyMap<Int, String>(), settings().drafts)
            // unchanged, empty or too long: nothing is sent, the field says why
            val sent = backend.requests.size
            model.editCategoryName(2, "Dairy ")
            model.saveCategoryName(2)
            model.setCategoryIcon(2, "🧀")
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
    fun `changes of the categories made while one is sent wait for it and are all sent, in their order`() =
        runTest {
            backend.answers["PUT /grocery/lists/1/categories/order"] =
                HttpStatusCode.OK to page(category(2, "Dairy & eggs", null), category(1, "Fruit & vegetables", "🥦"))
            backend.answers["PUT /grocery/lists/1/categories/1"] = HttpStatusCode.OK to category(1, "Fruit & vegetables", "🍎")
            backend.answers["PUT /grocery/lists/1/categories/2"] = HttpStatusCode.OK to category(2, "Milk", null)
            val model = signedIn()
            advanceUntilIdle()
            fun settings() = (model.screen.value as Screen.Lists).settings!!
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
                    """PUT /grocery/lists/1/categories/2 {"name":"Milk","icon":null}""",
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
            assertNull((model.screen.value as Screen.Lists).settings)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""PUT /grocery/lists/1/categories/2 {"name":"Milk","icon":null}"""))
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
            fun settings() = (model.screen.value as Screen.Lists).settings!!
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
            fun settings() = (model.screen.value as Screen.Lists).settings!!
            model.openSettings(1)
            advanceUntilIdle()
            model.editCategoryName(2, "Mil")
            val loads = backend.requests.count { it == "GET /grocery/lists/1/categories" }
            backend.answers["GET /grocery/lists/1/categories"] =
                HttpStatusCode.OK to page(category(2, "Dairy & eggs", null), category(1, "Fruit & vegetables", "🥦"), category(3, "Drinks", null))

            model.startListening()
            advanceTimeBy(GroceryModel.RETRY_DELAY_MS / 2)
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
            assertEquals(2, (model.screen.value as Screen.Lists).choosing?.categoryId)
            model.setCategory(2)
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("PUT /grocery/items/12/category") })

            // bought by another user: the list shows
            model.chooseCategory(model.item("Candles"))
            backend.answers["GET /grocery/lists/1/items"] = HttpStatusCode.OK to page(item(10, 1, "Apples", 1))
            model.refresh()
            advanceUntilIdle()
            assertNull((model.screen.value as Screen.Lists).choosing)
            assertEquals(1, (model.screen.value as Screen.Lists).open)
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
            assertEquals(1, (model.screen.value as Screen.Lists).open)
            assertNull((model.screen.value as Screen.Lists).settings)

            model.openSettings(1)
            model.editSettings("Weekly", shared = false, icon = null)
            model.saveSettings()
            advanceUntilIdle()
            assertEquals(1, (model.screen.value as Screen.Lists).open)
            assertNull((model.screen.value as Screen.Lists).settings)

            model.openSettings(1)
            backend.answers["GET /grocery/lists"] = HttpStatusCode.OK to page(list(2, "Party", shared = true))
            model.deleteList()
            advanceUntilIdle()
            assertNull((model.screen.value as Screen.Lists).open)
        }

    @Test
    fun `the menu shows the name of the user, else their address`() =
        runTest {
            backend.answers["GET /auth/who-am-i"] = HttpStatusCode.OK to """{"username":"anna@example.org","name":"Anna","roles":["user"]}"""
            val named = model(signedInStore(openList = null).also { it.save(it.load().copy(email = "anna@example.org")) }).also { it.start() }
            advanceUntilIdle()
            assertEquals("Anna", (named.screen.value as Screen.Lists).user)

            backend.answers["GET /auth/who-am-i"] = HttpStatusCode.OK to """{"username":"anna@example.org","name":null,"roles":["user"]}"""
            val unnamed = model(signedInStore(openList = null).also { it.save(it.load().copy(email = "anna@example.org")) }).also { it.start() }
            advanceUntilIdle()
            assertEquals("anna@example.org", (unnamed.screen.value as Screen.Lists).user)
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
            assertEquals(AppUpdate("0.1.15"), (model.screen.value as Screen.Lists).update)

            model.update()
            assertEquals(AppUpdate("0.1.15", busy = true), (model.screen.value as Screen.Lists).update)
            advanceUntilIdle()
            assertEquals(listOf("0.1.15"), installed)
            assertEquals(AppUpdate("0.1.15"), (model.screen.value as Screen.Lists).update)

            // the same version offers nothing
            val current = model(signedInStore(openList = null), appVersion = "0.1.15").also { it.start() }
            advanceUntilIdle()
            assertNull((current.screen.value as Screen.Lists).update)
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
                AppUpdate("0.1.15", error = uiText(R.string.error_update_not_downloaded)),
                (model.screen.value as Screen.Lists).update,
            )

            failure = UpdateException(uiText(R.string.error_update_damaged, "0.1.15"))
            model.update()
            advanceUntilIdle()
            assertEquals("The download of version 0.1.15 is damaged, please try again.", (model.screen.value as Screen.Lists).update?.error.english())
        }

    private fun info(version: String?) =
        HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"${version?.let { ",\"version\":\"$it\"" } ?: ""}}"""

    private fun notice(model: GroceryModel) = (model.screen.value as Screen.Lists).updateNotice.english()

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
            assertEquals(AppUpdate("0.1.16"), (model.screen.value as Screen.Lists).update)
            assertNull(notice(model))

            backend.answers["GET /info"] = info("latest")
            val current = model(signedInStore(openList = null), appVersion = "0.1.15").also { it.start() }
            advanceUntilIdle()
            current.checkUpdate()
            advanceUntilIdle()
            assertEquals("Your deployment runs no release, there is no update for the app.", notice(current))

            val debug = model(signedInStore(openList = null), appVersion = "latest").also { it.start() }
            advanceUntilIdle()
            debug.checkUpdate()
            advanceUntilIdle()
            assertEquals("This build of the app is no release, it is not updated.", notice(debug))
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
}
