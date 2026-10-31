package io.pelle.sak.grocery

import io.ktor.http.HttpStatusCode
import io.pelle.sak.grocery.session.MemorySessionStore
import io.pelle.sak.grocery.session.PendingLogin
import io.pelle.sak.grocery.session.Stored
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
    ) = """{"id":$id,"name":"$name","shared":$shared,"mine":true,"createdAt":"2026-10-31T07:00:00.000Z"}"""

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
    fun `the password signs in and the lists show the items to buy of the first list by category`() =
        runTest {
            val store = MemorySessionStore(Stored(apiUrl = URL, deployment = "family"))
            val model = model(store).also { it.start() }
            advanceUntilIdle()

            model.signInWithPassword("a password of sixteen")
            advanceUntilIdle()

            assertTrue(backend.requests.contains("""POST /auth/login/password {"password":"a password of sixteen"}"""))
            val lists = model.screen.value as Screen.Lists
            assertEquals("admin", lists.user)
            assertEquals(listOf("Default", "Party"), lists.lists.map { it.name })
            assertEquals(listOf("Fruit & vegetables", "Dairy & eggs", GroceryModel.NO_CATEGORY), lists.groups.map { it.label })
            assertEquals(listOf("Apples", "Milk", "Candles"), lists.groups.flatMap { group -> group.items.map { it.name } })
            assertEquals("password-session", store.load().token)
            assertEquals("password-session", backend.tokens["/grocery/lists/1/items"])

            model.select(1)
            advanceUntilIdle()
            assertEquals(emptyList<ItemGroup>(), (model.screen.value as Screen.Lists).groups)
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
}
