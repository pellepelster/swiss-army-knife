package io.pelle.sak.backend.grocery

import io.pelle.sak.backend.isOneEmoji
import io.pelle.sak.api.Language
import io.pelle.sak.backend.lists.ListResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.lists.ListDelete
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

    // the first list of the user, created with the default categories in english when they have none
    private fun list(user: String): Int =
        service.lists(user).firstOrNull { it.owner == user }?.id
            ?: (service.createList(user, "Default", shared = false, language = Language.EN) as ListResult.Saved).list.id

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
        service.checkItem(PELLE, apples.id) shouldBe true

        service.items(PELLE, own)!!.map { it.name } shouldBe listOf("Milk", "Soap", "Candles")
        milk.listId shouldBe soap.listId
        candles.categoryId shouldBe null

        // in the new order of the categories
        val ids = service.categories(PELLE, own)!!.map { it.id }
        (service.orderCategories(PELLE, own, ids.reversed()) as GroceryCategoriesResult.Ordered).categories.map { it.id } shouldBe ids.reversed()
        service.items(PELLE, own)!!.map { it.name } shouldBe listOf("Soap", "Milk", "Candles")
    }

    @Test
    fun `the bought items show with the ones to buy when asked, in their category after them, the last bought first (GROCERY-14)`() {
        val own = list(PELLE)
        val bakery = category(own, "Bakery")
        val rolls = add(PELLE, own, input("Rolls", categoryId = bakery))
        add(PELLE, own, input("Bread", categoryId = bakery))
        val soap = add(PELLE, own, input("Soap"))
        add(PELLE, own, input("Cake", categoryId = bakery))
        service.checkItem(PELLE, rolls.id) shouldBe true
        service.checkItem(PELLE, soap.id) shouldBe true

        service.items(PELLE, own)!!.map { it.name } shouldBe listOf("Bread", "Cake")
        service.items(PELLE, own, checked = true)!!.map { it.name to (it.checkedAt != null) } shouldBe
            listOf("Bread" to false, "Cake" to false, "Rolls" to true, "Soap" to true)
        service.items(OTHER, own, checked = true) shouldBe null

        // each name once, the latest bought item; none of a name to buy
        add(PELLE, own, input("rolls", categoryId = bakery)).let { service.checkItem(PELLE, it.id) }
        add(PELLE, own, input("Soap"))
        service.items(PELLE, own, checked = true)!!.map { it.name to (it.checkedAt != null) } shouldBe
            listOf("Bread" to false, "Cake" to false, "rolls" to true, "Soap" to false)
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
        val party = (service.createList(PELLE, "Party", shared = false, language = Language.DE) as ListResult.Saved).list.id
        service.categories(PELLE, party)!!.first().name shouldBe "Obst & Gemüse"
    }

    @Test
    fun `every user who sees a list changes its categories, an item takes one of its list only`() {
        val own = list(PELLE)
        val party = (service.createList(PELLE, "Party", shared = true) as ListResult.Saved).list.id
        val secret = (service.createList(PELLE, "Secret", shared = false) as ListResult.Saved).list.id

        val snacks = (service.createCategory(PELLE, party, " Snacks ", "🍿") as GroceryCategoryResult.Saved).category
        snacks.name shouldBe "Snacks"
        service.categories(OTHER, party)!!.last() shouldBe snacks
        service.categories(OTHER, secret) shouldBe null
        // a private list of another user is none of theirs, a shared one they change
        service.createCategory(OTHER, secret, "Mine", null) shouldBe GroceryCategoryResult.NotFound
        service.orderCategories(OTHER, secret, emptyList()) shouldBe GroceryCategoriesResult.NotFound
        val dips = (service.createCategory(OTHER, party, "Dips", null) as GroceryCategoryResult.Saved).category
        service.updateCategory(OTHER, party, dips.id, "Sauces", "🥫").shouldBeInstanceOf<GroceryCategoryResult.Saved>()
        service.deleteCategory(OTHER, party, dips.id) shouldBe true
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
        service.checkItem(OTHER, chips.id)
        add(PELLE, party, input("chips")).categoryId shouldBe snacks.id
        add(PELLE, own, input("chips")).categoryId shouldBe null

        // a deleted category goes from its items and suggestions
        service.deleteCategory(PELLE, party, snacks.id) shouldBe true
        service.items(PELLE, party)!!.single().categoryId shouldBe null
        service.suggestions(PELLE, party, "chips")!!.let { it shouldBe emptyList() }
        service.checkItem(PELLE, service.items(PELLE, party)!!.single().id)
        service.suggestions(PELLE, party, "chips")!!.single().categoryId shouldBe null
    }

    @Test
    fun `every change of a category and of the category of an item reaches the users who see the list (LIST-4)`(): Unit =
        runBlocking {
            val own = list(PELLE)
            val party = (service.createList(PELLE, "Party", shared = true) as ListResult.Saved).list.id
            val chips = add(PELLE, party, input("Chips"))
            val heard = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { service.changes(OTHER).take(7).toList() } }

            // the own list of pelle is private, the other user hears none of its changes
            service.createCategory(PELLE, own, "Private", null)
            val snacks = (service.createCategory(PELLE, party, "Snacks", null) as GroceryCategoryResult.Saved).category
            service.updateCategory(PELLE, party, snacks.id, "Crisps", null)
            service.orderCategories(PELLE, party, service.categories(PELLE, party)!!.map { it.id }.reversed())
            service.setCategory(OTHER, chips.id, snacks.id)
            service.deleteCategory(PELLE, party, snacks.id) shouldBe true
            // forgetting deletes bought items, which the eye shows (GROCERY-14)
            service.checkItem(OTHER, chips.id) shouldBe true
            service.forget(PELLE, party, chips.id) shouldBe true

            heard.await() shouldBe List(7) { party }
        }

    @Test
    fun `a change of an item sets its category or takes it away, the list learns it (GROCERY-13)`() {
        val own = list(PELLE)
        val bread = add(PELLE, own, input("Bread"))
        (service.updateItem(PELLE, bread.id, input("Bread", categoryId = category(own, "Bakery"))) as GroceryItemResult.Saved).item.categoryId shouldBe
            category(own, "Bakery")
        service.checkItem(PELLE, bread.id)
        add(PELLE, own, input("bread")).let {
            it.categoryId shouldBe category(own, "Bakery")
            (service.updateItem(PELLE, it.id, input("bread")) as GroceryItemResult.Saved).item.categoryId shouldBe null
            service.checkItem(PELLE, it.id)
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
        val shared = (service.createList(PELLE, " Party ", shared = true) as ListResult.Saved).list
        val private = (service.createList(PELLE, "Secret", shared = false) as ListResult.Saved).list
        shared.name shouldBe "Party"

        service.lists(OTHER).map { it.name } shouldBe listOf("Party")
        val chips = add(OTHER, shared.id, input("Chips"))
        service.updateItem(PELLE, chips.id, input("Crisps")).shouldBeInstanceOf<GroceryItemResult.Saved>()
        service.checkItem(PELLE, chips.id) shouldBe true
        val wine = add(PELLE, private.id, input("Wine"))
        service.checkItem(OTHER, wine.id) shouldBe false
        service.updateItem(OTHER, wine.id, input("Water")) shouldBe GroceryItemResult.NotFound
        service.addItem(OTHER, private.id, input("Wine")) shouldBe GroceryItemResult.UnknownList
        service.items(OTHER, private.id) shouldBe null

        // every user changes the icon of a shared list, only its owner renames, unshares and deletes it (LIST-2)
        val name = shared.name
        (service.updateList(OTHER, shared.id, name, shared = true, icon = "🎉") as ListResult.Saved).list.icon shouldBe "🎉"
        service.updateList(OTHER, shared.id, "Ours", shared = true) shouldBe ListResult.NotOwner
        service.updateList(OTHER, shared.id, name, shared = false) shouldBe ListResult.NotOwner
        service.updateList(OTHER, private.id, "Mine now", shared = false) shouldBe ListResult.NotFound
        service.deleteList(OTHER, shared.id) shouldBe ListDelete.NotFound
        service.deleteList(PELLE, shared.id) shouldBe ListDelete.Deleted
        service.lists(OTHER) shouldBe emptyList()
    }

    @Test
    fun `a user starts without lists, a new list has the default categories, the last one is deleted too`() {
        service.lists(PELLE) shouldBe emptyList()
        val weekly = (service.createList(PELLE, "Weekly", shared = false, language = Language.EN) as ListResult.Saved).list
        service.categories(PELLE, weekly.id)!!.map { it.name } shouldBe DefaultGroceryCategory.of(Language.EN).map { it.name }
        service.deleteList(PELLE, weekly.id) shouldBe ListDelete.Deleted
        service.lists(PELLE) shouldBe emptyList()
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
        service.checkItem(PELLE, milk.id) shouldBe true
        val party = (service.createList(PELLE, "Party", shared = true) as ListResult.Saved).list.id
        add(OTHER, party, input("Mints")).let { service.checkItem(OTHER, it.id) }

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
    fun `a change of a list reaches the users who see it, a private list of another user not (LIST-4)`(): Unit =
        runBlocking {
            val own = list(PELLE)
            val party = (service.createList(PELLE, "Party", shared = true) as ListResult.Saved).list.id
            // listening before anything changes
            val heard = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { service.changes(OTHER).take(4).toList() } }

            add(PELLE, own, input("Wine"))
            val chips = add(PELLE, party, input("Chips"))
            service.updateItem(OTHER, chips.id, input("Crisps"))
            service.checkItem(OTHER, chips.id) shouldBe true
            service.checkItem(OTHER, chips.id) shouldBe false
            service.deleteList(PELLE, party) shouldBe ListDelete.Deleted

            // the own list of pelle is private, the shared one heard also when it goes
            heard.await() shouldBe listOf(party, party, party, party)
        }

    @Test
    fun `a list unshared reaches the users who saw it, its changes after that not (LIST-4)`(): Unit =
        runBlocking {
            val party = (service.createList(PELLE, "Party", shared = true) as ListResult.Saved).list.id
            val picnic = (service.createList(PELLE, "Picnic", shared = true) as ListResult.Saved).list.id
            val heard = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { service.changes(OTHER).take(2).toList() } }

            // its unsharing reaches every user once, a rename of the private list after that not
            service.updateList(PELLE, party, "Party", shared = false)
            service.updateList(PELLE, party, "Our party", shared = false)
            service.updateList(PELLE, picnic, "Picnic", shared = true)

            heard.await() shouldBe listOf(party, picnic)
        }

    @Test
    fun `a list takes one emoji as its icon, an empty one is none`() {
        val created = service.createList(PELLE, "Party", shared = false, icon = " 🎉 ") as ListResult.Saved
        created.list.icon shouldBe "🎉"
        (service.updateList(PELLE, created.list.id, "Party", shared = false, icon = "") as ListResult.Saved).list.icon shouldBe null

        // a flag, a keycap, a family joined of four, a skin tone
        listOf("🇩🇪", "1️⃣", "👨‍👩‍👧‍👦", "👍🏽", "🛒").forEach { isOneEmoji(it) shouldBe true }
        listOf("a", "1", "🎉🎉", "🎉 a", "\u0007").forEach { isOneEmoji(it) shouldBe false }
        service.createList(PELLE, "Wrong", shared = false, icon = "ab") shouldBe
            ListResult.Invalid(listOf(ErrorMessage(GroceryTexts.INVALID_ICON)))
    }
}
