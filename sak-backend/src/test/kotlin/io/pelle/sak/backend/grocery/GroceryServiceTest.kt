package io.pelle.sak.backend.grocery

import io.pelle.sak.api.Language
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.grocery.DEFAULT_GROCERY_LIST
import io.pelle.sak.model.grocery.GroceryListDelete
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

private const val PELLE = "pelle@pelle.io"

private const val OTHER = "other@example.org"

private fun input(
    name: String,
    amount: String? = null,
    unit: String? = null,
    categoryId: Int? = null,
) = GroceryItemInput(name, amount?.let(::BigDecimal), unit, categoryId)

class GroceryServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val service by lazy { GroceryService(SakDatabase.open(tempDir.resolve("sak.db"))) }

    private fun add(
        user: String,
        listId: Int,
        input: GroceryItemInput,
    ) = (service.addItem(user, listId, input) as GroceryItemResult.Saved).item

    // the list of the user, the one they get when they sign in
    private fun list(user: String): Int {
        service.signedIn(user, Language.EN)
        return service.lists(user).first { it.owner == user }.id
    }

    // the id of the category of the name on the list
    private fun category(
        listId: Int,
        name: String,
    ) = service.categories(PELLE, listId)!!.single { it.name == name }.id

    @Test
    fun `a list shows the items to buy by category, the ones without one last, not the bought ones`() {
        val own = list(PELLE)
        val soap = add(PELLE, own, input("Soap", categoryId = category(own, "Household")))
        val apples = add(PELLE, own, input("Apples", "1", "kg", category(own, "Fruit & vegetables")))
        val candles = add(PELLE, own, input("Candles"))
        val milk = add(PELLE, own, input("Milk", categoryId = category(own, "Dairy & eggs")))
        service.deleteItem(PELLE, apples.id) shouldBe true

        service.items(PELLE, own)!!.map { it.name } shouldBe listOf("Milk", "Soap", "Candles")
        milk.listId shouldBe soap.listId
        candles.categoryId shouldBe null

        // in the new order of the categories
        val ids = service.categories(PELLE, own)!!.map { it.id }
        (service.orderCategories(PELLE, own, ids.reversed()) as GroceryCategoriesResult.Ordered).categories.map { it.id } shouldBe ids.reversed()
        service.items(PELLE, own)!!.map { it.name } shouldBe listOf("Soap", "Milk", "Candles")
    }

    @Test
    fun `a new list starts with the default categories in the language of its request, with their icons (GROCERY-13)`() {
        service.categories(PELLE, list(PELLE))!!.map { it.icon to it.name } shouldBe
            listOf(
                "🥦" to "Fruit & vegetables",
                "🥖" to "Bakery",
                "🧀" to "Dairy & eggs",
                "🥩" to "Meat & fish",
                "🥫" to "Pantry",
                "🧊" to "Frozen",
                "🥤" to "Drinks",
                "🧻" to "Household",
                "🛒" to "Other",
            )
        val party = (service.createList(PELLE, "Party", shared = false, language = Language.DE) as GroceryListResult.Saved).list.id
        service.categories(PELLE, party)!!.first().name shouldBe "Obst & Gemüse"
    }

    @Test
    fun `only the owner changes the categories of a list, every user who sees it sees them, an item takes one of its list only`() {
        val own = list(PELLE)
        val party = (service.createList(PELLE, "Party", shared = true) as GroceryListResult.Saved).list.id
        val secret = (service.createList(PELLE, "Secret", shared = false) as GroceryListResult.Saved).list.id

        val snacks = (service.createCategory(PELLE, party, " Snacks ", "🍿") as GroceryCategoryResult.Saved).category
        snacks.name shouldBe "Snacks"
        service.categories(OTHER, party)!!.last() shouldBe snacks
        service.categories(OTHER, secret) shouldBe null
        service.createCategory(OTHER, party, "Mine", null) shouldBe GroceryCategoryResult.NotFound
        service.updateCategory(OTHER, party, snacks.id, "Mine", null) shouldBe GroceryCategoryResult.NotFound
        service.deleteCategory(OTHER, party, snacks.id) shouldBe false
        service.orderCategories(OTHER, party, emptyList()) shouldBe GroceryCategoriesResult.NotFound
        // one name in any case and whitespace
        service.createCategory(PELLE, party, "snacks", null) shouldBe GroceryCategoryResult.Exists
        service.createCategory(PELLE, party, "SNACKS ", null) shouldBe GroceryCategoryResult.Exists
        service.updateCategory(PELLE, party, snacks.id, "Crisps", "")
            .let { (it as GroceryCategoryResult.Saved).category } shouldBe snacks.copy(name = "Crisps", icon = null)
        // a category of another list is none of this one
        service.updateCategory(PELLE, own, snacks.id, "Crisps", null) shouldBe GroceryCategoryResult.NotFound
        (service.orderCategories(PELLE, party, listOf(snacks.id)) as GroceryCategoriesResult.Invalid).errors shouldBe
            listOf(ErrorMessage(GroceryTexts.INVALID_CATEGORY_ORDER))

        // every user of the list sets a category of the list, no other one
        val chips = add(OTHER, party, input("Chips"))
        (service.setCategory(OTHER, chips.id, snacks.id) as GroceryItemResult.Saved).item.categoryId shouldBe snacks.id
        (service.setCategory(OTHER, chips.id, category(own, "Bakery")) as GroceryItemResult.Invalid).errors shouldBe
            listOf(ErrorMessage(GroceryTexts.UNKNOWN_CATEGORY))
        (service.addItem(PELLE, own, input("Bread", categoryId = snacks.id)) as GroceryItemResult.Invalid).errors shouldBe
            listOf(ErrorMessage(GroceryTexts.UNKNOWN_CATEGORY))
        service.setCategory(OTHER, add(PELLE, secret, input("Wine")).id, null) shouldBe GroceryItemResult.NotFound
        // the next item of the name on the list gets it
        service.deleteItem(OTHER, chips.id)
        add(PELLE, party, input("chips")).categoryId shouldBe snacks.id
        add(PELLE, own, input("chips")).categoryId shouldBe null

        // a deleted category goes from its items and suggestions
        service.deleteCategory(PELLE, party, snacks.id) shouldBe true
        service.items(PELLE, party)!!.single().categoryId shouldBe null
        service.suggestions(PELLE, party, "chips")!!.let { it shouldBe emptyList() }
        service.deleteItem(PELLE, service.items(PELLE, party)!!.single().id)
        service.suggestions(PELLE, party, "chips")!!.single().categoryId shouldBe null
    }

    @Test
    fun `every change of a category and of the category of an item reaches the users who see the list (LIST-4)`() =
        runBlocking {
            val own = list(PELLE)
            val party = (service.createList(PELLE, "Party", shared = true) as GroceryListResult.Saved).list.id
            val chips = add(PELLE, party, input("Chips"))
            val heard = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { service.changes(OTHER).take(5).toList() } }

            // the own list of pelle is private, the other user hears none of its changes
            service.createCategory(PELLE, own, "Private", null)
            val snacks = (service.createCategory(PELLE, party, "Snacks", null) as GroceryCategoryResult.Saved).category
            service.updateCategory(PELLE, party, snacks.id, "Crisps", null)
            service.orderCategories(PELLE, party, service.categories(PELLE, party)!!.map { it.id }.reversed())
            service.setCategory(OTHER, chips.id, snacks.id)
            service.deleteCategory(PELLE, party, snacks.id) shouldBe true

            heard.await() shouldBe List(5) { party }
        }

    @Test
    fun `a change of an item sets its category or takes it away, the list learns it (GROCERY-13)`() {
        val own = list(PELLE)
        val bread = add(PELLE, own, input("Bread"))
        (service.updateItem(PELLE, bread.id, input("Bread", categoryId = category(own, "Bakery"))) as GroceryItemResult.Saved).item.categoryId shouldBe
            category(own, "Bakery")
        service.deleteItem(PELLE, bread.id)
        add(PELLE, own, input("bread")).let {
            it.categoryId shouldBe category(own, "Bakery")
            (service.updateItem(PELLE, it.id, input("bread")) as GroceryItemResult.Saved).item.categoryId shouldBe null
            service.deleteItem(PELLE, it.id)
        }
        add(PELLE, own, input("Bread")).categoryId shouldBe null
    }

    @Test
    fun `a name, an icon and a number of the categories that do not fit are refused`() {
        val own = list(PELLE)
        (service.createCategory(PELLE, own, " ", "ab") as GroceryCategoryResult.Invalid).errors shouldBe
            listOf(
                ErrorMessage(GroceryTexts.INVALID_CATEGORY_NAME, "max" to GroceryService.MAX_CATEGORY_NAME_LENGTH),
                ErrorMessage(GroceryTexts.INVALID_ICON),
            )
        (service.createCategory(PELLE, own, "x".repeat(33), null) as GroceryCategoryResult.Invalid).errors.single().key shouldBe
            GroceryTexts.INVALID_CATEGORY_NAME
        repeat(GroceryService.MAX_CATEGORIES - 9) { service.createCategory(PELLE, own, "C$it", null).shouldBeInstanceOf<GroceryCategoryResult.Saved>() }
        (service.createCategory(PELLE, own, "One more", null) as GroceryCategoryResult.Invalid).errors shouldBe
            listOf(ErrorMessage(GroceryTexts.TOO_MANY_CATEGORIES, "max" to GroceryService.MAX_CATEGORIES))
    }

    @Test
    fun `a shared list is seen and changed by every user, only its owner renames, shares and deletes it`() {
        val shared = (service.createList(PELLE, " Party ", shared = true) as GroceryListResult.Saved).list
        val private = (service.createList(PELLE, "Secret", shared = false) as GroceryListResult.Saved).list
        shared.name shouldBe "Party"

        service.lists(OTHER).map { it.name } shouldBe listOf("Party")
        val chips = add(OTHER, shared.id, input("Chips"))
        service.updateItem(PELLE, chips.id, input("Crisps")).shouldBeInstanceOf<GroceryItemResult.Saved>()
        service.deleteItem(PELLE, chips.id) shouldBe true
        val wine = add(PELLE, private.id, input("Wine"))
        service.deleteItem(OTHER, wine.id) shouldBe false
        service.updateItem(OTHER, wine.id, input("Water")) shouldBe GroceryItemResult.NotFound
        service.addItem(OTHER, private.id, input("Wine")) shouldBe GroceryItemResult.NotFound
        service.items(OTHER, private.id) shouldBe null

        service.updateList(OTHER, shared.id, "Mine now", shared = true) shouldBe GroceryListResult.NotFound
        service.deleteList(OTHER, shared.id) shouldBe GroceryListDelete.NotFound
        service.deleteList(PELLE, shared.id) shouldBe GroceryListDelete.Deleted
        service.lists(OTHER) shouldBe emptyList()
    }

    @Test
    fun `the list of the sign-in is a list like the others`() {
        service.signedIn(PELLE, Language.EN)
        val default = service.lists(PELLE).single()
        default.name shouldBe DEFAULT_GROCERY_LIST
        service.signedIn(PELLE, Language.EN)
        service.lists(PELLE) shouldBe listOf(default)

        (service.updateList(PELLE, default.id, "Weekly", shared = true) as GroceryListResult.Saved).list.shared shouldBe true
        // the only own list stays
        service.deleteList(PELLE, default.id) shouldBe GroceryListDelete.LastList
        val party = (service.createList(PELLE, "Party", shared = false) as GroceryListResult.Saved).list
        service.deleteList(PELLE, default.id) shouldBe GroceryListDelete.Deleted
        service.signedIn(PELLE, Language.EN)
        service.lists(PELLE) shouldBe listOf(party)
    }

    @Test
    fun `tells every problem of an item at once`() {
        (service.addItem(PELLE, list(PELLE), GroceryItemInput(" ", BigDecimal("0.0001"), "l", null)) as GroceryItemResult.Invalid).errors shouldBe
            listOf(
                ErrorMessage(GroceryTexts.INVALID_ITEM_NAME, "max" to GroceryService.MAX_ITEM_NAME_LENGTH),
                ErrorMessage(GroceryTexts.INVALID_AMOUNT, "max" to GroceryService.MAX_AMOUNT),
            )
        (service.addItem(PELLE, list(PELLE), input("Milk", unit = "l")) as GroceryItemResult.Invalid).errors shouldBe
            listOf(ErrorMessage(GroceryTexts.UNIT_WITHOUT_AMOUNT))
        add(PELLE, list(PELLE), input("Milk", "1.50", " l ")).let {
            it.amount!!.compareTo(BigDecimal("1.5")) shouldBe 0
            it.unit shouldBe "l"
        }
    }

    @Test
    fun `suggests what was added to the list before, by every user who sees it, on that list only (GROCERY-11)`() {
        val own = list(PELLE)
        val otherOwn = list(OTHER)
        val milk = add(PELLE, own, input("Milk", "1", "l", category(own, "Dairy & eggs")))
        // bought, no longer to buy
        service.deleteItem(PELLE, milk.id) shouldBe true
        val party = (service.createList(PELLE, "Party", shared = true) as GroceryListResult.Saved).list.id
        add(OTHER, party, input("Mints")).let { service.deleteItem(OTHER, it.id) }

        val suggestion = service.suggestions(PELLE, own, "mi")!!.single()
        suggestion.name shouldBe "Milk"
        suggestion.categoryId shouldBe category(own, "Dairy & eggs")
        service.suggestions(PELLE, party, "mi")!!.map { it.name } shouldBe listOf("Mints")
        service.suggestions(OTHER, otherOwn, "mi") shouldBe emptyList()
        service.suggestions(OTHER, own, "mi") shouldBe null

        service.forget(OTHER, own, suggestion.id) shouldBe false
        service.forget(PELLE, party, suggestion.id) shouldBe false
        service.forget(PELLE, own, suggestion.id) shouldBe true
        service.suggestions(PELLE, own, "") shouldBe emptyList()
    }

    @Test
    fun `a change of a list reaches the users who see it, a private list of another user not (LIST-4)`() =
        runBlocking {
            val own = list(PELLE)
            val party = (service.createList(PELLE, "Party", shared = true) as GroceryListResult.Saved).list.id
            // listening before anything changes
            val heard = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { service.changes(OTHER).take(4).toList() } }

            add(PELLE, own, input("Wine"))
            val chips = add(PELLE, party, input("Chips"))
            service.updateItem(OTHER, chips.id, input("Crisps"))
            service.deleteItem(OTHER, chips.id) shouldBe true
            service.deleteItem(OTHER, chips.id) shouldBe false
            service.deleteList(PELLE, party) shouldBe GroceryListDelete.Deleted

            // the own list of pelle is private, the shared one heard also when it goes
            heard.await() shouldBe listOf(party, party, party, party)
        }

    @Test
    fun `a list takes one emoji as its icon, an empty one is none`() {
        val created = service.createList(PELLE, "Party", shared = false, icon = " 🎉 ") as GroceryListResult.Saved
        created.list.icon shouldBe "🎉"
        (service.updateList(PELLE, created.list.id, "Party", shared = false, icon = "") as GroceryListResult.Saved).list.icon shouldBe null

        // a flag, a keycap, a family joined of four, a skin tone
        listOf("🇩🇪", "1️⃣", "👨‍👩‍👧‍👦", "👍🏽", "🛒").forEach { GroceryService.isOneEmoji(it) shouldBe true }
        listOf("a", "1", "🎉🎉", "🎉 a", "\u0007").forEach { GroceryService.isOneEmoji(it) shouldBe false }
        service.createList(PELLE, "Wrong", shared = false, icon = "ab") shouldBe
            GroceryListResult.Invalid(listOf(ErrorMessage(GroceryTexts.INVALID_ICON)))
    }
}
