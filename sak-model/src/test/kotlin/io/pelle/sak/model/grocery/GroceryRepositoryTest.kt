package io.pelle.sak.model.grocery

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    @Test
    fun `the first item of a user without a list makes their default list, the next ones go to it too`() {
        val milk = repository.insertItem(PELLE, null, values("Milk", "2", "l", "DAIRY"))
        val bread = repository.insertItem(PELLE, null, values("Bread"))
        val eggs = repository.insertItem(OTHER, null, values("Eggs"))

        bread.listId shouldBe milk.listId
        eggs.listId shouldNotBe milk.listId
        repository.list(milk.listId) shouldBe GroceryList(milk.listId, PELLE, DEFAULT_GROCERY_LIST, false, now, null)
        repository.items(milk.listId).map { it.name } shouldBe listOf("Milk", "Bread")
        milk.amount!!.compareTo(BigDecimal("2")) shouldBe 0
    }

    @Test
    fun `a user sees their lists, the default one first, and the shared ones of the others`() {
        repository.insertItem(PELLE, null, values("Milk"))
        val party = repository.insertList(PELLE, "Party", shared = false)!!
        val bbq = repository.insertList(OTHER, "Barbecue", shared = true)!!
        repository.insertList(OTHER, "Secret", shared = false)
        repository.insertList(PELLE, "Party", shared = true) shouldBe null

        repository.lists(PELLE).map { it.name } shouldBe listOf(DEFAULT_GROCERY_LIST, "Barbecue", "Party")
        repository.updateList(party.id, "Garden party", shared = true)!!.shared shouldBe true
        repository.lists(OTHER).map { it.name } shouldBe listOf("Barbecue", "Garden party", "Secret")

        repository.insertItem(OTHER, bbq.id, values("Sausages"))
        repository.deleteList(bbq.id)
        repository.list(bbq.id) shouldBe null
        database.dsl.fetchCount(GROCERY_ITEMS, GROCERY_ITEMS.LIST_ID.eq(bbq.id), GROCERY_ITEMS.DELETED_AT.isNull) shouldBe 0
    }

    @Test
    fun `a checked item keeps when and by whom it was checked first, the checked ones are cleared at once`() {
        val milk = repository.insertItem(PELLE, null, values("Milk"))
        val bread = repository.insertItem(PELLE, null, values("Bread"))

        val checked = repository.updateItem(milk.id, values("Milk"), checked = true, by = OTHER)!!
        checked.checkedAt shouldBe now
        checked.checkedBy shouldBe OTHER
        repository.updateItem(milk.id, values("Oat milk"), checked = true, by = PELLE)!!.checkedBy shouldBe OTHER
        repository.updateItem(milk.id, values("Oat milk"), checked = false, by = PELLE)!!.checkedAt shouldBe null
        repository.updateItem(milk.id, values("Oat milk"), checked = true, by = PELLE)

        repository.deleteChecked(milk.listId) shouldBe 1
        repository.items(milk.listId).map { it.id } shouldBe listOf(bread.id)
        repository.deleteItem(bread.id) shouldBe true
        repository.deleteItem(bread.id) shouldBe false
    }

    @Test
    fun `every item teaches the product of its name, in any case, the suggestions leave out what is to buy`() {
        val milk = repository.insertItem(PELLE, null, values("Milk", "1", "l", "DAIRY"))
        repository.deleteItem(milk.id)
        repository.insertItem(PELLE, null, values("milk", "2", "l", "DAIRY"))
        val bread = repository.insertItem(PELLE, null, values("Bread", category = "BAKERY"))
        repository.deleteItem(bread.id)
        repository.insertItem(OTHER, null, values("Oat milk"))

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
    fun `the schema takes positive amounts only, a unit with an amount only and the known categories only`() {
        shouldThrow<DataAccessException> { repository.insertItem(PELLE, null, values("Milk", "0")) }
        shouldThrow<DataAccessException> { repository.insertItem(PELLE, null, values("Milk", unit = "l")) }
        shouldThrow<DataAccessException> { repository.insertItem(PELLE, null, values("Milk", category = "SWEETS")) }
        repository.insertItem(PELLE, null, values("Milk", "1.5", "l", "DAIRY")).amount!!.compareTo(BigDecimal("1.5")) shouldBe 0
    }
}
