package io.pelle.sak.model.grocery

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.references.GROCERY_ITEMS
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val PELLE = "pelle@pelle.io"

private const val OTHER = "other@example.org"

private fun values(
    name: String,
    amount: String? = null,
    unit: String? = null,
    category: String? = null,
) = GroceryItemValues(name, amount?.let(::BigDecimal), unit, category)

class GroceryRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val now = Instant.parse("2026-10-27T08:00:00Z")

    private val repository by lazy { GroceryRepository(database, Clock.fixed(now, ZoneOffset.UTC)) }

    // the list of the owner, the one they get when they sign in
    private fun list(owner: String): Int {
        repository.ensureList(owner, DEFAULT_GROCERY_LIST)
        return repository.lists(owner).first { it.owner == owner }.id
    }

    @Test
    fun `a user without a list gets one, private and only once, a user with a list gets none`() {
        repository.ensureList(PELLE, DEFAULT_GROCERY_LIST)
        repository.ensureList(PELLE, DEFAULT_GROCERY_LIST)
        val weekly = repository.insertList(OTHER, "Weekly", shared = false)!!
        repository.ensureList(OTHER, DEFAULT_GROCERY_LIST)

        val default = repository.lists(PELLE).single()
        default shouldBe GroceryList(default.id, PELLE, DEFAULT_GROCERY_LIST, false, now, null)
        repository.lists(OTHER) shouldBe listOf(weekly)
        val milk = repository.insertItem(PELLE, default.id, values("Milk", "2", "l", "DAIRY"))!!
        repository.items(PELLE, default.id)!!.map { it.name } shouldBe listOf("Milk")
        milk.amount!!.compareTo(BigDecimal("2")) shouldBe 0

        // the last own list stays, with another one it goes; a shared list of another user does not count
        repository.insertList(OTHER, "Party", shared = true)
        repository.deleteList(PELLE, default.id) shouldBe GroceryListDelete.LastList
        repository.lists(PELLE).map { it.id } shouldContain default.id
        val second = repository.insertList(PELLE, "Weekly", shared = false)!!
        repository.deleteList(PELLE, default.id) shouldBe GroceryListDelete.Deleted
        repository.deleteList(PELLE, default.id) shouldBe GroceryListDelete.NotFound
        repository.deleteList(PELLE, second.id) shouldBe GroceryListDelete.LastList
    }

    @Test
    fun `a user sees their lists and the shared ones of the others, by name`() {
        repository.insertItem(PELLE, list(PELLE), values("Milk"))
        val party = repository.insertList(PELLE, "Party", shared = false)!!
        val bbq = repository.insertList(OTHER, "Barbecue", shared = true)!!
        repository.insertList(OTHER, "Secret", shared = false)
        repository.insertList(PELLE, "Party", shared = true) shouldBe null

        repository.lists(PELLE).map { it.name } shouldBe listOf("Barbecue", DEFAULT_GROCERY_LIST, "Party")
        (repository.updateList(PELLE, party.id, "Garden party", shared = true) as GroceryListUpdate.Updated).list.shared shouldBe true
        repository.lists(OTHER).map { it.name } shouldBe listOf("Barbecue", "Garden party", "Secret")

        repository.insertItem(OTHER, bbq.id, values("Sausages"))
        repository.deleteList(OTHER, bbq.id) shouldBe GroceryListDelete.Deleted
        repository.list(OTHER, bbq.id) shouldBe null
        database.dsl.fetchCount(GROCERY_ITEMS, GROCERY_ITEMS.LIST_ID.eq(bbq.id), GROCERY_ITEMS.DELETED_AT.isNull) shouldBe 0
    }

    @Test
    fun `another user sees and changes only the shared lists and their items, only the owner changes a list`() {
        val secret = repository.insertList(PELLE, "Secret", shared = false)!!
        val party = repository.insertList(PELLE, "Party", shared = true)!!
        val wine = repository.insertItem(PELLE, secret.id, values("Wine"))!!
        val chips = repository.insertItem(PELLE, party.id, values("Chips"))!!

        repository.list(OTHER, secret.id) shouldBe null
        repository.items(OTHER, secret.id) shouldBe null
        repository.item(OTHER, wine.id) shouldBe null
        repository.insertItem(OTHER, secret.id, values("Beer")) shouldBe null
        repository.updateItem(OTHER, wine.id, values("Water")) shouldBe null
        repository.deleteItem(OTHER, wine.id) shouldBe false
        // nothing was learned of an item that was not added
        repository.products(OTHER, "", null, 10) shouldBe emptyList()
        // the owner sees the items to buy of their list
        repository.products(PELLE, "", secret.id, 10).map { it.name } shouldBe listOf("Chips")
        repository.updateList(OTHER, secret.id, "Mine", shared = true) shouldBe GroceryListUpdate.NotFound
        repository.deleteList(OTHER, secret.id) shouldBe GroceryListDelete.NotFound
        repository.items(PELLE, secret.id)!!.map { it.name } shouldBe listOf("Wine")

        repository.list(OTHER, party.id) shouldBe party
        repository.items(OTHER, party.id)!!.map { it.name } shouldBe listOf("Chips")
        repository.updateItem(OTHER, chips.id, values("Crisps"))!!.name shouldBe "Crisps"
        repository.insertItem(OTHER, party.id, values("Beer"))!!.owner shouldBe OTHER
        repository.deleteItem(OTHER, chips.id) shouldBe true
        repository.updateList(OTHER, party.id, "Mine", shared = true) shouldBe GroceryListUpdate.NotFound
        repository.deleteList(OTHER, party.id) shouldBe GroceryListDelete.NotFound
        repository.updateList(PELLE, party.id, "Secret", shared = true) shouldBe GroceryListUpdate.NameTaken
        repository.deleteList(PELLE, party.id) shouldBe GroceryListDelete.Deleted
        repository.items(OTHER, party.id) shouldBe null
    }

    @Test
    fun `a bought item is deleted, it stays as a deleted row`() {
        val milk = repository.insertItem(PELLE, list(PELLE), values("Milk"))!!
        val bread = repository.insertItem(PELLE, list(PELLE), values("Bread"))!!
        repository.updateItem(PELLE, milk.id, values("Oat milk"))!!.name shouldBe "Oat milk"

        repository.deleteItem(PELLE, milk.id) shouldBe true
        repository.deleteItem(PELLE, milk.id) shouldBe false
        repository.item(PELLE, milk.id) shouldBe null
        repository.updateItem(PELLE, milk.id, values("Milk")) shouldBe null
        repository.items(PELLE, milk.listId)!!.map { it.id } shouldBe listOf(bread.id)
        database.dsl.fetchOne(GROCERY_ITEMS, GROCERY_ITEMS.ID.eq(milk.id))!!.deletedAt shouldBe now
    }

    @Test
    fun `every item teaches the product of its name, in any case, the suggestions leave out what is to buy`() {
        // bought, no longer to buy
        val milk = repository.insertItem(PELLE, list(PELLE), values("Milk", "1", "l", "DAIRY"))!!
        repository.deleteItem(PELLE, milk.id)
        repository.insertItem(PELLE, list(PELLE), values("milk", "2", "l", "DAIRY"))
        val bread = repository.insertItem(PELLE, list(PELLE), values("Bread", category = "BAKERY"))!!
        repository.deleteItem(PELLE, bread.id)
        repository.insertItem(OTHER, list(OTHER), values("Oat milk"))

        val products = repository.products(PELLE, "", null, 10)
        products.map { it.name to it.timesUsed } shouldBe listOf("milk" to 2, "Bread" to 1)
        products.first().amount!!.compareTo(BigDecimal("2")) shouldBe 0
        repository.products(PELLE, "MIL", null, 10).map { it.name } shouldBe listOf("milk")
        // milk is to buy on the list already
        repository.products(PELLE, "", milk.listId, 10).map { it.name } shouldBe listOf("Bread")

        repository.deleteProduct(OTHER, products.first().id) shouldBe false
        repository.deleteProduct(PELLE, products.first().id) shouldBe true
        repository.products(PELLE, "", null, 10).map { it.name } shouldBe listOf("Bread")
    }

    @Test
    fun `a product is the same for every case, unicode form and whitespace of its name, not without its accents`() {
        repository.insertItem(PELLE, list(PELLE), values("Äpfel"))
        repository.insertItem(PELLE, list(PELLE), values("Äpfel"))
        // decomposed: A and a combining diaeresis
        repository.insertItem(PELLE, list(PELLE), values("a\u0308pfel"))
        repository.insertItem(PELLE, list(PELLE), values("Müsli"))
        repository.insertItem(PELLE, list(PELLE), values("Musli"))
        repository.insertItem(PELLE, list(PELLE), values("Milk  bread"))
        repository.insertItem(PELLE, list(PELLE), values("milk bread"))

        repository.products(PELLE, "", null, 10).map { it.name to it.timesUsed } shouldBe
            listOf("a\u0308pfel" to 3, "milk bread" to 2, "Müsli" to 1, "Musli" to 1)
        repository.products(PELLE, "ÄP", null, 10).map { it.timesUsed } shouldBe listOf(3)
        repository.products(PELLE, "MÜS", null, 10).map { it.name } shouldBe listOf("Müsli")
    }

    @Test
    fun `an item to buy hides the suggestion of its name in any case`() {
        val apples = repository.insertItem(PELLE, list(PELLE), values("Äpfel"))!!
        repository.deleteItem(PELLE, apples.id)
        repository.insertItem(PELLE, list(PELLE), values("Bread"))
        repository.products(PELLE, "", apples.listId, 10).map { it.name } shouldBe listOf("Äpfel")

        repository.insertItem(PELLE, apples.listId, values("ÄPFEL"))
        repository.products(PELLE, "", apples.listId, 10) shouldBe emptyList()
    }

    @Test
    fun `the schema takes positive amounts only and a unit with an amount only`() {
        shouldThrow<DataAccessException> { repository.insertItem(PELLE, list(PELLE), values("Milk", "0")) }
        shouldThrow<DataAccessException> { repository.insertItem(PELLE, list(PELLE), values("Milk", unit = "l")) }
        repository.insertItem(PELLE, list(PELLE), values("Milk", "1.5", "l", "DAIRY"))!!.amount!!.compareTo(BigDecimal("1.5")) shouldBe 0
    }
}
