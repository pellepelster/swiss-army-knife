package io.pelle.sak.grocery

import io.pelle.sak.testing.aiSetting
import io.pelle.sak.testing.userScope
import io.pelle.sak.testing.userSettings
import io.pelle.sak.testing.json
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpStatusCode
import io.pelle.sak.api.model.GroceryDiscoveredItemResponse
import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.core.Screen
import io.pelle.sak.core.lists.ChangeStream
import io.pelle.sak.core.session.MemorySessionStore
import io.pelle.sak.core.session.openList
import io.pelle.sak.core.session.withOpenList
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.answerOn
import io.pelle.sak.testing.changed
import io.pelle.sak.testing.english
import io.pelle.sak.testing.file
import io.pelle.sak.testing.page
import io.pelle.sak.testing.refused
import io.pelle.sak.testing.sakSetup
import io.pelle.sak.testing.signedIn
import io.pelle.sak.testing.signedOut
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

// the logic of the grocery app; what all apps share is tested in testing (SakModelTest, ListsModelTest)
@OptIn(ExperimentalCoroutinesApi::class)
class GroceryModelTest {
    private val now = Instant.parse("2026-10-31T08:00:00Z")

    private val backend =
        FakeBackend().apply {
            answers["GET /grocery/lists"] =
                HttpStatusCode.OK to page(list(1, "Default"), list(2, "Party", shared = true))
            answers["GET /grocery/lists/1/items"] =
                HttpStatusCode.OK to page(item(10, 1, "Apples", 1), item(11, 1, "Milk", 2, 1.5, "l"), item(12, 1, "Candles"))
            answers["GET /grocery/lists/2/items"] = HttpStatusCode.OK to page()
            answers["GET /grocery/lists/1/categories"] =
                HttpStatusCode.OK to page(category(1, "Fruit & vegetables", "🥦"), category(2, "Dairy & eggs"))
            answers["GET /grocery/lists/2/categories"] = HttpStatusCode.OK to page()
        }

    // a list of another user, shared
    private val theirs = list(3, "Theirs", shared = true, mine = false)

    private fun TestScope.model(store: MemorySessionStore): GroceryModel {
        backend.answerOn(this)
        return GroceryModel(sakSetup(store, Clock.fixed(now, ZoneOffset.UTC)), backend::api)
    }

    @Test
    fun `the home shows all lists, a list opens with its items by category, back leads home`() =
        runTest {
            val store = signedInStore(openList = null)
            val model = model(store).also { it.start() }
            advanceUntilIdle()

            val home = model.screen.value as ListsScreen
            assertNull(home.open)
            assertEquals(listOf("Default", "Party"), home.lists.map { it.name })
            assertTrue(backend.requests.none { it.contains("/items") })

            model.open(1)
            advanceUntilIdle()
            val lists = model.screen.value as ListsScreen
            assertEquals(1, lists.open)
            assertEquals(listOf("Fruit & vegetables", "Dairy & eggs", GroceryModel.NO_CATEGORY.english()), lists.groups.map { it.label.english() })
            assertEquals(listOf("🥦", null, null), lists.groups.map { it.icon })
            assertEquals(listOf("Apples", "Milk", "Candles"), lists.groups.flatMap { group -> group.items.map { it.name } })
            assertEquals("t", backend.tokens["/grocery/lists/1/items"])
            assertEquals(1, store.load().openList)

            model.back()
            advanceUntilIdle()
            assertNull((model.screen.value as ListsScreen).open)
            assertEquals(emptyList<ItemGroup>(), (model.screen.value as ListsScreen).groups)
            assertNull(store.load().openList)
        }

    private fun signedInStore(openList: Int? = 1) = MemorySessionStore(signedIn(now.plusSeconds(60)).withOpenList(openList))

    // signed in, the list open last open again
    private fun TestScope.signedIn(openList: Int? = 1): GroceryModel = model(signedInStore(openList)).also { it.start() }

    @Test
    fun `an item typed at the top goes to the list shown, the field is empty again and the items are loaded again`() =
        runTest {
            backend.answers["POST /grocery/items"] = HttpStatusCode.Created to item(20, 2, "Cake")
            val model = signedIn(openList = 2)
            advanceUntilIdle()

            model.addItem()
            model.typed("   ")
            model.addItem()
            advanceUntilIdle()
            assertTrue(backend.requests.none { it.startsWith("POST /grocery/items") })

            backend.answers["GET /grocery/lists/2/items"] = HttpStatusCode.OK to page(item(20, 2, "Cake"))
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
            backend.answers["POST /grocery/items"] = HttpStatusCode.BadRequest to refused("The name is too long.")
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

            val bought = item(13, 1, "Bread", checkedAt = CREATED)
            backend.answers["GET /grocery/lists/1/items"] =
                HttpStatusCode.OK to page(item(10, 1, "Apples", 1), item(11, 1, "Milk", 2, 1.5, "l"), item(12, 1, "Candles"), bought)
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
            backend.answers["PUT /grocery/items/12"] = HttpStatusCode.BadRequest to refused("no")
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
    fun `set up but not turned on, the user turns the AI on in their settings and the robot follows (PRIVACY-2)`() =
        runTest {
            backend.answers["GET /grocery/settings"] = HttpStatusCode.OK to grocerySettings(discoverItems = false, aiConfigured = true)
            val model = signedIn()
            advanceUntilIdle()
            assertFalse(model.lists().discoverItems)
            assertTrue(model.lists().aiConfigured)

            backend.answers["GET /user/settings"] = HttpStatusCode.OK to userSettings(userScope("grocery", "Grocery", aiSetting(false)))
            backend.answers["PUT /user/settings/grocery"] = HttpStatusCode.OK to json(userScope("grocery", "Grocery", aiSetting(true)))
            backend.answers["GET /grocery/settings"] = HttpStatusCode.OK to grocerySettings(discoverItems = true)
            model.userSettings.open()
            advanceUntilIdle()
            model.userSettings.set("grocery", "ai", true)
            advanceUntilIdle()
            assertTrue(model.lists().discoverItems)
        }

    @Test
    fun `the robot shows when the backend discovers the items of a photo, not when it says no or can not say (GROCERY-16)`() =
        runTest {
            val model = signedIn()
            advanceUntilIdle()
            // no answer
            assertFalse(model.lists().discoverItems)

            backend.answers["GET /grocery/settings"] = HttpStatusCode.OK to grocerySettings(discoverItems = true)
            model.refresh()
            advanceUntilIdle()
            assertTrue(model.lists().discoverItems)

            backend.answers["GET /grocery/settings"] = HttpStatusCode.OK to grocerySettings(discoverItems = false)
            model.refresh()
            advanceUntilIdle()
            assertFalse(model.lists().discoverItems)

            backend.answers["GET /grocery/settings"] = HttpStatusCode.OK to grocerySettings(discoverItems = true)
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
            backend.answers["GET /grocery/settings"] = HttpStatusCode.OK to grocerySettings(discoverItems = true)
            model.toggleBought()
            advanceUntilIdle()
            assertEquals(asked, backend.requests.count { it == "GET /grocery/settings" })
        }

    private fun discoverAnswers(vararg items: GroceryDiscoveredItemResponse) {
        backend.answers["GET /grocery/settings"] = HttpStatusCode.OK to grocerySettings(discoverItems = true)
        backend.answers["POST /files"] = HttpStatusCode.Created to file(9)
        backend.answers["POST /grocery/files/9/discover/items"] = HttpStatusCode.OK to discovered(*items)
    }

    @Test
    fun `the items of a photo are read, corrected and the ones checked added without a category (GROCERY-16)`() =
        runTest {
            discoverAnswers(found("Flour", 500.0, "g"), found("Garlik", 2.0, "Zehe"), found("Salt", unit = "pinch"), found("Water"))
            backend.answers["POST /grocery/items"] = HttpStatusCode.Created to item(20, 1, "Flour")
            val model = signedIn()
            advanceUntilIdle()

            // the dialog says the photo is read while it is
            model.discover {
                assertTrue(model.lists().discovery!!.reading)
                "jpeg".toByteArray()
            }
            advanceUntilIdle()
            assertTrue(backend.requests.contains("POST /files jpeg"))
            assertTrue(backend.requests.contains("POST /grocery/files/9/discover/items"))
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
            discoverAnswers(found("Flour", 500.0, "g"), found("Sugar"))
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
            discoverAnswers()
            val model = signedIn()
            advanceUntilIdle()

            model.discover { "jpeg".toByteArray() }
            advanceUntilIdle()
            val none = model.lists().discovery!!
            assertFalse(none.reading)
            assertTrue(none.rows.isEmpty())
            model.dismissDiscovery()

            backend.answers["POST /grocery/files/9/discover/items"] =
                HttpStatusCode.Conflict to refused("The AI features are off.", "grocery.discover_off")
            model.discover { "jpeg".toByteArray() }
            advanceUntilIdle()
            assertNull(model.lists().discovery)
            assertEquals("The AI features are off.", model.lists().error.english())

            backend.answers["POST /grocery/files/9/discover/items"] = HttpStatusCode.InternalServerError to ""
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
            discoverAnswers(found("Flour"), found("Sugar"), found("Eggs", 6.0))
            backend.answers["POST /grocery/items"] = HttpStatusCode.Created to item(20, 1, "Flour")
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
            assertEquals("Not all items could be added.", discovery.error.english())
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
            val bought = item(13, 1, "Bred", photoId = 7, checkedAt = CREATED)
            backend.answers["GET /grocery/lists/1/items"] =
                HttpStatusCode.OK to page(item(10, 1, "Apples", 1), item(11, 1, "Milk", 2, 1.5, "l"), item(12, 1, "Candles"), bought)
            backend.answers["PUT /grocery/items/13"] = HttpStatusCode.OK to item(13, 1, "Bread", photoId = 7, checkedAt = CREATED)
            backend.answers["PUT /grocery/items/13/category"] = HttpStatusCode.OK to item(13, 1, "Bred", 1, photoId = 7, checkedAt = CREATED)
            backend.answers["DELETE /grocery/items/13/photo"] = HttpStatusCode.NoContent to ""
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

            // a delete that works clears the error of the earlier one (AA-3)
            model.deleteItem(model.item("Apples"))
            advanceUntilIdle()
            assertNull((model.screen.value as ListsScreen).error)
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
            backend.answers["GET /grocery/lists/1/items"] = HttpStatusCode.OK to page(item(10, 1, "Apples", 1), item(12, 1, "Candles"))
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
    fun `every user who sees a shared list changes its categories`() =
        runTest {
            backend.answers["GET /grocery/lists"] = HttpStatusCode.OK to page(list(1, "Default"), theirs)
            backend.answers["GET /grocery/lists/3/categories"] = HttpStatusCode.OK to page(category(5, "Snacks"))
            backend.answers["POST /grocery/lists/3/categories"] = HttpStatusCode.Created to category(6, "Drinks")
            val model = signedIn(openList = null)
            advanceUntilIdle()
            fun settings() = (model.screen.value as ListsScreen).settings!!

            model.openSettings(3)
            // its categories loading
            assertTrue(settings().categoriesLoading)
            advanceUntilIdle()
            assertEquals(false, settings().fields.mine)
            assertEquals(listOf("Snacks"), settings().categories.map { it.name })

            // the categories as for the owner
            model.editNewCategory("Drinks")
            model.addCategory()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /grocery/lists/3/categories {"name":"Drinks"}"""))
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
            backend.answers["POST /files"] = HttpStatusCode.Created to file(9)
            backend.answers["PUT /grocery/items/10/photo"] = HttpStatusCode.OK to item(10, 1, "Apples", 1)
            backend.answers["DELETE /grocery/items/10/photo"] = HttpStatusCode.NoContent to ""
            backend.answers["GET /grocery/items/10/photo/9"] = HttpStatusCode.OK to "jpeg"
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
            assertEquals(1, backend.requests.count { it.startsWith("GET /grocery/items/10/photo/9") })

            model.removePhoto(apples)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("DELETE /grocery/items/10/photo"))
        }

    @Test
    fun `a photo the camera took while android ended the app is attached once the lists are back`() =
        runTest {
            backend.answers["POST /files"] = HttpStatusCode.Created to file(9)
            backend.answers["PUT /grocery/items/10/photo"] = HttpStatusCode.OK to item(10, 1, "Apples", 1)
            val store = signedInStore(openList = 1)
            val model = model(store)
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
                HttpStatusCode.BadRequest to refused("Only images.", "files.unsupported_type")
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
            backend.answers["PUT /grocery/items/11/category"] = HttpStatusCode.BadRequest to refused("The list has no such category.")
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
            backend.answers["POST /grocery/lists/1/categories"] = HttpStatusCode.Created to category(3, "Drinks")
            backend.answers["PUT /grocery/lists/1/categories/2"] = HttpStatusCode.OK to category(2, "Dairy", "🧀")
            backend.answers["PUT /grocery/lists/1/categories/order"] =
                HttpStatusCode.OK to page(category(2, "Dairy", "🧀"), category(1, "Fruit & vegetables", "🥦"), category(3, "Drinks"))
            backend.answers["DELETE /grocery/lists/1/categories/1"] = HttpStatusCode.NoContent to ""
            backend.answers["POST /grocery/lists/2/categories"] = HttpStatusCode.Conflict to refused("The list has a category of the name.")
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
            assertNull(settings().fields.error)

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
            assertNull(settings().fields.error)
            assertEquals("Drinks", settings().newCategory)
            assertEquals(0, settings().categoriesPending)
            assertEquals(loads + 1, backend.requests.count { it == "GET /grocery/lists/2/categories" })
        }

    @Test
    fun `a category dragged to another place shows there at once and the order of all of them is sent once (GROCERY-13)`() =
        runTest {
            backend.answers["GET /grocery/lists/1/categories"] =
                HttpStatusCode.OK to page(category(1, "Fruit"), category(2, "Bakery"), category(3, "Dairy"), category(4, "Drinks"))
            backend.answers["PUT /grocery/lists/1/categories/order"] =
                HttpStatusCode.OK to page(category(2, "Bakery"), category(3, "Dairy"), category(1, "Fruit"), category(4, "Drinks"))
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
                HttpStatusCode.OK to page(category(2, "Dairy & eggs"), category(1, "Fruit & vegetables", "🥦"))
            backend.answers["PUT /grocery/lists/1/categories/1"] = HttpStatusCode.OK to category(1, "Fruit & vegetables", "🍎")
            backend.answers["PUT /grocery/lists/1/categories/2"] = HttpStatusCode.OK to category(2, "Milk")
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
            backend.answers["PUT /grocery/lists/1/categories/2"] = HttpStatusCode.OK to category(2, "Milk")
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
                HttpStatusCode.InternalServerError to refused("Something went wrong.", "api.unexpected_error")

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
                HttpStatusCode.OK to page(category(2, "Dairy & eggs"), category(1, "Fruit & vegetables", "🥦"), category(3, "Drinks"))

            model.foreground()
            advanceTimeBy(ChangeStream.RETRY_DELAY_MS / 2)
            runCurrent()
            model.background()
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
    fun `the settings opened from a list return to it, by back and once saved`() =
        runTest {
            backend.answers["PUT /grocery/lists/1"] = HttpStatusCode.OK to list(1, "Weekly")
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
                HttpStatusCode.OK to page(category(1, "A"), category(2, "B"), category(3, "C"), category(4, "D"))
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
            backend.answers["POST /files"] = HttpStatusCode.Created to file(9)
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
            val model = model(MemorySessionStore(signedOut()))
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

            model.foreground()
            runCurrent()
            // the first stream ended at once; the next one loads what it may have missed
            advanceTimeBy(ChangeStream.RETRY_DELAY_MS + 1)
            runCurrent()
            assertTrue(loads() > before)
            model.background()
        }

    @Test
    fun `the list suggests names for what is typed, one chosen is added at once with its quantity and category (GROCERY-11)`() =
        runTest {
            backend.answers["GET /grocery/lists/1/suggestions"] =
                HttpStatusCode.OK to page(suggestion(20, "Milk", 1.5, "l", 2))
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
            assertEquals(emptyList<Any>(), model.lists().suggestions)
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /grocery/items {"listId":1,"name":"Milk","amount":1.5,"unit":"l","categoryId":2}"""))
            assertEquals("", model.lists().itemName)
        }

    @Test
    fun `a name typed goes without a quantity and category, also when it is the name of a suggestion`() =
        runTest {
            backend.answers["GET /grocery/lists/1/suggestions"] =
                HttpStatusCode.OK to page(suggestion(20, "Milk", 1.5, "l", 2))
            backend.answers["POST /grocery/items"] = HttpStatusCode.Created to item(13, 1, "Milk")
            val model = signedIn()
            advanceUntilIdle()
            model.typed("mi")
            advanceUntilIdle()
            model.typed("Milk")
            model.addItem()
            advanceUntilIdle()
            assertTrue(backend.requests.contains("""POST /grocery/items {"listId":1,"name":"Milk"}"""))

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
            model.background()
            runCurrent()
            assertTrue(backend.requests.contains("POST /grocery/items/10/check"))
            assertNull(model.lists().undo)
        }
}
