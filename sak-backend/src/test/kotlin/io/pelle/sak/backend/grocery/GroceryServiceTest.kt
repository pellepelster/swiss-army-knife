package io.pelle.sak.backend.grocery

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.grocery.DEFAULT_GROCERY_LIST
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
    category: GroceryCategory? = null,
) = GroceryItemInput(name, amount?.let(::BigDecimal), unit, category?.name)

class GroceryServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val service by lazy { GroceryService(SakDatabase.open(tempDir.resolve("sak.db"))) }

    private fun add(
        user: String,
        listId: Int?,
        input: GroceryItemInput,
    ) = (service.addItem(user, listId, input) as GroceryItemResult.Saved).item

    @Test
    fun `a list shows the items to buy by category, the ones without one last, the bought ones after`() {
        val soap = add(PELLE, null, input("Soap", category = GroceryCategory.HOUSEHOLD))
        val apples = add(PELLE, null, input("Apples", "1", "kg", GroceryCategory.FRUIT_VEGETABLES))
        val candles = add(PELLE, null, input("Candles"))
        val milk = add(PELLE, null, input("Milk", category = GroceryCategory.DAIRY))
        service.updateItem(PELLE, apples.id, input("Apples", "1", "kg", GroceryCategory.FRUIT_VEGETABLES), checked = true)

        service.items(PELLE, soap.listId)!!.map { it.name } shouldBe listOf("Milk", "Soap", "Candles", "Apples")
        milk.listId shouldBe soap.listId
        candles.category shouldBe null
    }

    @Test
    fun `every category is one the schema takes`() {
        GroceryCategory.entries.forEach { add(PELLE, null, input(it.label, category = it)).category shouldBe it.name }
    }

    @Test
    fun `a shared list is seen and changed by every user, only its owner renames, shares and deletes it`() {
        val shared = (service.createList(PELLE, " Party ", shared = true) as GroceryListResult.Saved).list
        val private = (service.createList(PELLE, "Secret", shared = false) as GroceryListResult.Saved).list
        shared.name shouldBe "Party"

        service.lists(OTHER).map { it.name } shouldBe listOf("Party")
        val chips = add(OTHER, shared.id, input("Chips"))
        service.updateItem(PELLE, chips.id, input("Chips"), checked = true).shouldBeInstanceOf<GroceryItemResult.Saved>()
        service.addItem(OTHER, private.id, input("Wine")) shouldBe GroceryItemResult.NotFound
        service.items(OTHER, private.id) shouldBe null

        service.updateList(OTHER, shared.id, "Mine now", shared = true) shouldBe GroceryListResult.NotFound
        service.deleteList(OTHER, shared.id) shouldBe GroceryListResult.NotFound
        service.clearChecked(OTHER, shared.id) shouldBe 1
        service.deleteList(PELLE, shared.id).shouldBeInstanceOf<GroceryListResult.Saved>()
        service.lists(OTHER) shouldBe emptyList()
    }

    @Test
    fun `the default list keeps its name and is never deleted, but can be shared`() {
        val milk = add(PELLE, null, input("Milk"))

        service.updateList(PELLE, milk.listId, "Weekly", shared = false) shouldBe GroceryListResult.Default
        service.deleteList(PELLE, milk.listId) shouldBe GroceryListResult.Default
        (service.updateList(PELLE, milk.listId, DEFAULT_GROCERY_LIST, shared = true) as GroceryListResult.Saved).list.shared shouldBe true
        service.createList(PELLE, DEFAULT_GROCERY_LIST, shared = false) shouldBe GroceryListResult.Exists
    }

    @Test
    fun `tells every problem of an item at once`() {
        (service.addItem(PELLE, null, GroceryItemInput(" ", BigDecimal("0.0001"), "l", "SWEETS")) as GroceryItemResult.Invalid).errors shouldBe
            listOf(
                ErrorMessage("An item name must have 1 to 128 characters and no control characters."),
                ErrorMessage("An amount must be a positive number up to 9999999.999 with at most 3 decimal places."),
                ErrorMessage("There is no category \"SWEETS\"."),
            )
        (service.addItem(PELLE, null, input("Milk", unit = "l")) as GroceryItemResult.Invalid).errors shouldBe
            listOf(ErrorMessage("A unit needs an amount."))
        add(PELLE, null, input("Milk", "1.50", " l ")).let {
            it.amount!!.compareTo(BigDecimal("1.5")) shouldBe 0
            it.unit shouldBe "l"
        }
    }

    @Test
    fun `suggests what the user added before, never what another user added`() {
        val milk = add(PELLE, null, input("Milk", "1", "l", GroceryCategory.DAIRY))
        service.deleteItem(PELLE, milk.id) shouldBe true
        add(OTHER, null, input("Mints"))

        val suggestion = service.suggestions(PELLE, "mi", milk.listId).single()
        suggestion.name shouldBe "Milk"
        suggestion.category shouldBe GroceryCategory.DAIRY.name
        service.forget(OTHER, suggestion.id) shouldBe false
        service.forget(PELLE, suggestion.id) shouldBe true
        service.suggestions(PELLE, "", null) shouldBe emptyList()
    }
}
