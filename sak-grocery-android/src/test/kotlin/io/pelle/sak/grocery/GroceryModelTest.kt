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
            answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain"}"""
            answers["GET /auth/login/methods"] = HttpStatusCode.OK to """{"email":true,"password":true}"""
            answers["POST /auth/login/link"] = HttpStatusCode.Accepted to ""
            answers["POST /auth/login/exchange"] = HttpStatusCode.OK to session("link-session", "pelle@pelle.io")
            answers["POST /auth/login/password"] = HttpStatusCode.OK to session("password-session", "admin")
            answers["GET /grocery/lists"] =
                HttpStatusCode.OK to page(list(1, "Default"), list(2, "Party", shared = true))
            answers["GET /grocery/lists/1/items"] =
                HttpStatusCode.OK to page(item(10, 1, "Apples", "FRUIT_VEGETABLES"), item(11, 1, "Milk", "DAIRY", 1.5, "l"), item(12, 1, "Candles", null))
            answers["GET /grocery/lists/2/items"] = HttpStatusCode.OK to page()
            answers["GET /grocery/categories"] =
                HttpStatusCode.OK to
                page("""{"name":"FRUIT_VEGETABLES","label":"Fruit & vegetables"}""", """{"name":"DAIRY","label":"Dairy & eggs"}""")
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
        category: String?,
        amount: Double? = null,
        unit: String? = null,
    ) = """{"id":$id,"listId":$listId,"name":"$name","amount":$amount,"unit":${unit?.let { "\"$it\"" }},"category":${category?.let { "\"$it\"" }}}"""

    private fun TestScope.model(
        store: MemorySessionStore,
        at: Instant = now,
    ): GroceryModel {
        backend.dispatcher = StandardTestDispatcher(testScheduler)
        return GroceryModel(store, backend::api, this, allowUrl = false, clock = Clock.fixed(at, ZoneOffset.UTC))
    }

    @Test
    fun `a new app asks for the deployment, checks it and offers the sign-in it has`() =
        runTest {
            val store = MemorySessionStore()
            val model = model(store).also { it.start() }
            advanceUntilIdle()
            assertEquals(Screen.Deployment(), model.screen.value)

            model.connect("nonsense")
            assertTrue((model.screen.value as Screen.Deployment).error!!.contains("base domain"))

            model.connect("example.org")
            advanceUntilIdle()
            assertEquals(Screen.SignIn("family", email = true, password = true), model.screen.value)
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
            assertEquals(listOf("Fruit & vegetables", "Dairy & eggs", GroceryModel.NO_CATEGORY), lists.groups.map { it.label })
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
            assertEquals("Your session ended, please sign in again.", signIn.error)
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
            assertEquals("The password is wrong.", (model.screen.value as Screen.SignIn).error)

            backend.answers["POST /auth/login/password"] =
                HttpStatusCode.TooManyRequests to """{"errors":[{"message":"Slow down."}]}"""
            model.signInWithPassword("again")
            advanceUntilIdle()
            assertEquals("Slow down.", (model.screen.value as Screen.SignIn).error)
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
            assertEquals("The name is too long.", lists.error)
            assertEquals("Bread", lists.itemName)
            assertEquals(false, lists.adding)

            backend.answers["POST /grocery/items"] = HttpStatusCode.Unauthorized to ""
            model.addItem()
            advanceUntilIdle()
            assertEquals("Your session ended, please sign in again.", (model.screen.value as Screen.SignIn).error)
        }

    private fun GroceryModel.item(name: String) =
        (screen.value as Screen.Lists).groups.flatMap { it.items }.first { it.name == name }

    private fun GroceryModel.names() = (screen.value as Screen.Lists).groups.flatMap { group -> group.items.map { it.name } }

    @Test
    fun `a checked item is struck through and goes when its undo ran out, the backend deletes it`() =
        runTest {
            backend.answers["DELETE /grocery/items/10"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()

            model.check(model.item("Apples"))
            advanceTimeBy(UNDO_MS - 1)
            runCurrent()
            val struck = model.screen.value as Screen.Lists
            assertEquals(setOf(10), struck.struck)
            assertEquals("Apples bought", struck.undo?.text)
            assertEquals(listOf("Apples", "Milk", "Candles"), model.names())
            assertTrue(backend.requests.none { it.startsWith("DELETE") })

            advanceUntilIdle()
            val lists = model.screen.value as Screen.Lists
            assertEquals(emptySet<Int>(), lists.struck)
            assertNull(lists.undo)
            assertEquals(listOf("Dairy & eggs", GroceryModel.NO_CATEGORY), lists.groups.map { it.label })
            assertEquals(listOf("DELETE /grocery/items/10"), backend.requests.filter { it.startsWith("DELETE") })
            assertEquals("t", backend.tokens["/grocery/items/10"])
        }

    @Test
    fun `an undo or a second tap keeps the item, a check before goes to the backend at once`() =
        runTest {
            backend.answers["DELETE /grocery/items/10"] = HttpStatusCode.NoContent to ""
            backend.answers["DELETE /grocery/items/11"] = HttpStatusCode.NoContent to ""
            val model = signedIn()
            advanceUntilIdle()

            model.check(model.item("Apples"))
            model.undo()
            model.check(model.item("Milk"))
            model.check(model.item("Milk"))
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("DELETE") })
            assertEquals(emptySet<Int>(), (model.screen.value as Screen.Lists).struck)

            model.check(model.item("Apples"))
            model.check(model.item("Milk"))
            runCurrent()
            assertEquals(listOf("DELETE /grocery/items/10"), backend.requests.filter { it.startsWith("DELETE") })
            assertEquals(setOf(11), (model.screen.value as Screen.Lists).struck)

            model.signOut()
            advanceUntilIdle()
            assertEquals(2, backend.requests.count { it.startsWith("DELETE") })
            assertEquals("t", backend.tokens["/grocery/items/11"])
        }

    @Test
    fun `a check the backend refuses shows why and the list as the backend has it`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()

            model.check(model.item("Apples"))
            advanceUntilIdle()
            assertEquals("The item could not be checked.", (model.screen.value as Screen.Lists).error)
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
            model.editNewList(" Party ", shared = true)
            model.createList()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /grocery/lists {"name":"Party","shared":true}"""))
            assertEquals(NewList(" Party ", shared = true, error = "A list of the name exists."), (model.screen.value as Screen.Lists).newList)

            model.back()
            assertNull((model.screen.value as Screen.Lists).newList)

            backend.answers["POST /grocery/lists"] = HttpStatusCode.Created to list(3, "Hardware")
            backend.answers["GET /grocery/lists/3/items"] = HttpStatusCode.OK to page()
            backend.answers["GET /grocery/lists"] =
                HttpStatusCode.OK to page(list(1, "Default"), list(2, "Party", shared = true), list(3, "Hardware"))
            model.newList()
            model.editNewList("Hardware", shared = false)
            model.createList()
            advanceUntilIdle()
            val lists = model.screen.value as Screen.Lists
            assertNull(lists.newList)
            assertEquals(3, lists.open)
            assertEquals(3, store.load().openList)
        }
}
