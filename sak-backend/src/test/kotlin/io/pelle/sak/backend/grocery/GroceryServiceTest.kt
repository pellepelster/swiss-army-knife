package io.pelle.sak.backend.grocery

import io.pelle.sak.api.Language
import io.pelle.sak.api.render
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
        GroceryCategory.entries.forEach { add(PELLE, list(PELLE), input(it.label.render(Language.EN), category = it)).category shouldBe it.name }
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
                ErrorMessage(GroceryTexts.INVALID_ITEM_NAME, "max" to GroceryService.MAX_ITEM_NAME_LENGTH),
                ErrorMessage(GroceryTexts.INVALID_AMOUNT, "max" to GroceryService.MAX_AMOUNT),
                ErrorMessage(GroceryTexts.UNKNOWN_CATEGORY, "category" to "SWEETS"),
            )
        (service.addItem(PELLE, list(PELLE), input("Milk", unit = "l")) as GroceryItemResult.Invalid).errors shouldBe
            listOf(ErrorMessage(GroceryTexts.UNIT_WITHOUT_AMOUNT))
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

    @Test
    fun `a shared list learns from every user, it stays with the list when it is unshared (GROCERY-11)`() {
        // the own lists first, the ones of the sign-in
        val own = list(PELLE)
        val otherOwn = list(OTHER)
        val party = (service.createList(PELLE, "Party", shared = true) as GroceryListResult.Saved).list.id
        val chips = add(OTHER, party, input("Chips"))
        service.deleteItem(OTHER, chips.id) shouldBe true

        // the list learned it, and the user who added it; not the other user personally
        service.suggestions(PELLE, "ch", party).map { it.name } shouldBe listOf("Chips")
        service.suggestions(PELLE, "ch", own) shouldBe emptyList()
        service.suggestions(OTHER, "ch", otherOwn).map { it.name } shouldBe listOf("Chips")

        // one suggestion of a name, also when the user learned it too
        add(PELLE, party, input("Chips")).let { service.deleteItem(PELLE, it.id) }
        service.suggestions(PELLE, "ch", party).map { it.name } shouldBe listOf("Chips")

        // unshared: the owner keeps what the list learned, the other user no longer sees the list
        service.updateList(PELLE, party, "Party", shared = false, icon = null)
        service.suggestions(OTHER, "ch", party).map { it.listId } shouldBe listOf(null)
        service.suggestions(PELLE, "ch", party).map { it.name } shouldBe listOf("Chips")
        // shared again: back for everyone
        service.updateList(PELLE, party, "Party", shared = true, icon = null)
        service.suggestions(OTHER, "ch", party).any { it.listId == party } shouldBe true

        // every user of the list forgets what it learned; a deleted list takes it along
        val learned = service.suggestions(OTHER, "ch", party).first { it.listId == party }
        service.forget(OTHER, learned.id) shouldBe true
        add(OTHER, party, input("Dip")).let { service.deleteItem(OTHER, it.id) }
        service.deleteList(PELLE, party) shouldBe GroceryListDelete.Deleted
        service.suggestions(PELLE, "dip", null) shouldBe emptyList()
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
