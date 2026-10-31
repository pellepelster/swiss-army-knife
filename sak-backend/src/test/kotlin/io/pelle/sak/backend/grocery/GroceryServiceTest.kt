package io.pelle.sak.backend.grocery

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.grocery.DEFAULT_GROCERY_LIST
import io.pelle.sak.model.grocery.GroceryListDelete
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
        listId: Int,
        input: GroceryItemInput,
    ) = (service.addItem(user, listId, input) as GroceryItemResult.Saved).item

    // the list of the user, the one they get when they sign in
    private fun list(user: String): Int {
        service.signedIn(user)
        return service.lists(user).first { it.owner == user }.id
    }

    @Test
    fun `a list shows the items to buy by category, the ones without one last, not the bought ones`() {
        val soap = add(PELLE, list(PELLE), input("Soap", category = GroceryCategory.HOUSEHOLD))
        val apples = add(PELLE, list(PELLE), input("Apples", "1", "kg", GroceryCategory.FRUIT_VEGETABLES))
        val candles = add(PELLE, list(PELLE), input("Candles"))
        val milk = add(PELLE, list(PELLE), input("Milk", category = GroceryCategory.DAIRY))
        service.deleteItem(PELLE, apples.id) shouldBe true

        service.items(PELLE, soap.listId)!!.map { it.name } shouldBe listOf("Milk", "Soap", "Candles")
        milk.listId shouldBe soap.listId
        candles.category shouldBe null
    }

    @Test
    fun `every category can be given to an item`() {
        GroceryCategory.entries.forEach { add(PELLE, list(PELLE), input(it.label, category = it)).category shouldBe it.name }
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
        service.signedIn(PELLE)
        val default = service.lists(PELLE).single()
        default.name shouldBe DEFAULT_GROCERY_LIST
        service.signedIn(PELLE)
        service.lists(PELLE) shouldBe listOf(default)

        (service.updateList(PELLE, default.id, "Weekly", shared = true) as GroceryListResult.Saved).list.shared shouldBe true
        // the only own list stays
        service.deleteList(PELLE, default.id) shouldBe GroceryListDelete.LastList
        val party = (service.createList(PELLE, "Party", shared = false) as GroceryListResult.Saved).list
        service.deleteList(PELLE, default.id) shouldBe GroceryListDelete.Deleted
        service.signedIn(PELLE)
        service.lists(PELLE) shouldBe listOf(party)
    }

    @Test
    fun `tells every problem of an item at once`() {
        (service.addItem(PELLE, list(PELLE), GroceryItemInput(" ", BigDecimal("0.0001"), "l", "SWEETS")) as GroceryItemResult.Invalid).errors shouldBe
            listOf(
                ErrorMessage("An item name must have 1 to 128 characters and no control characters."),
                ErrorMessage("An amount must be a positive number up to 9999999.999 with at most 3 decimal places."),
                ErrorMessage("There is no category \"SWEETS\"."),
            )
        (service.addItem(PELLE, list(PELLE), input("Milk", unit = "l")) as GroceryItemResult.Invalid).errors shouldBe
            listOf(ErrorMessage("A unit needs an amount."))
        add(PELLE, list(PELLE), input("Milk", "1.50", " l ")).let {
            it.amount!!.compareTo(BigDecimal("1.5")) shouldBe 0
            it.unit shouldBe "l"
        }
    }

    @Test
    fun `suggests what the user added before, never what another user added`() {
        val milk = add(PELLE, list(PELLE), input("Milk", "1", "l", GroceryCategory.DAIRY))
        // bought, no longer to buy
        service.deleteItem(PELLE, milk.id) shouldBe true
        add(OTHER, list(OTHER), input("Mints"))

        val suggestion = service.suggestions(PELLE, "mi", milk.listId).single()
        suggestion.name shouldBe "Milk"
        suggestion.category shouldBe GroceryCategory.DAIRY.name
        service.forget(OTHER, suggestion.id) shouldBe false
        service.forget(PELLE, suggestion.id) shouldBe true
        service.suggestions(PELLE, "", null) shouldBe emptyList()
    }
}
