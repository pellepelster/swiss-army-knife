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
            answers["GET /info"] = HttpStatusCode.OK to """{"name":"family","dnsMode":"domain","userMode":"multi"}"""
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
            assertEquals("Apples bought", struck.undo?.text.english())
            assertEquals(listOf("Apples", "Milk", "Candles"), model.names())
            assertTrue(backend.requests.none { it.startsWith("DELETE") })

            advanceUntilIdle()
            val lists = model.screen.value as Screen.Lists
            assertEquals(emptySet<Int>(), lists.struck)
            assertNull(lists.undo)
            assertEquals(listOf("Dairy & eggs", GroceryModel.NO_CATEGORY.english()), lists.groups.map { it.label.english() })
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
            assertEquals(ListSettings(2, "Party", shared = true), (model.screen.value as Screen.Lists).settings)
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
            backend.answers["PUT /grocery/items/10/photo"] = HttpStatusCode.OK to item(10, 1, "Apples", "FRUIT_VEGETABLES")
            backend.answers["DELETE /grocery/items/10/photo"] = HttpStatusCode.OK to item(10, 1, "Apples", "FRUIT_VEGETABLES")
            backend.answers["GET /grocery/items/10/photos/9"] = HttpStatusCode.OK to "jpeg"
            val model = signedIn()
            advanceUntilIdle()

            model.attachPhoto(model.item("Apples"), "jpeg".toByteArray())
            assertEquals(10, (model.screen.value as Screen.Lists).photoBusy)
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
    fun `a photo that can not be attached says why, and one shown in full closes`() =
        runTest {
            backend.answers["POST /files"] =
                HttpStatusCode.BadRequest to """{"errors":[{"code":"files.unsupported_type","message":"Only images."}]}"""
            val model = signedIn()
            advanceUntilIdle()
            model.attachPhoto(model.item("Apples"), "nonsense".toByteArray())
            advanceUntilIdle()
            assertEquals("Only images.", (model.screen.value as Screen.Lists).error.english())
            assertTrue(backend.requests.none { it.startsWith("PUT") })

            val apples = model.item("Apples").copy(photoId = 9)
            model.viewPhoto(apples)
            assertNull(model.photo(apples, null, full = true))
            assertNull((model.screen.value as Screen.Lists).viewing)
        }

    @Test
    fun `the categories are asked for once a session, not on every load`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()
            model.refresh()
            advanceUntilIdle()
            model.refresh()
            advanceUntilIdle()
            assertEquals(3, backend.requests.count { it == "GET /grocery/lists/1/items" })
            assertEquals(1, backend.requests.count { it == "GET /grocery/categories" })
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
