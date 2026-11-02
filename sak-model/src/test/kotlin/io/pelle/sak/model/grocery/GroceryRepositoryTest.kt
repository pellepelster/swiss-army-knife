package io.pelle.sak.model.grocery

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.files.FileRepository
import io.pelle.sak.model.jooq.tables.references.GROCERY_CATEGORIES
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
    categoryId: Int? = null,
) = GroceryItemValues(name, amount?.let(::BigDecimal), unit, categoryId)

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
        default shouldBe GroceryList(default.id, PELLE, DEFAULT_GROCERY_LIST, false, now, null, items = 0)
        repository.lists(OTHER) shouldBe listOf(weekly)
        val milk = repository.insertItem(PELLE, default.id, values("Milk", "2", "l"))!!
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
        repository.checkItem(OTHER, wine.id) shouldBe false
        // the other user does not see what the private list learned
        repository.suggestions(OTHER, secret.id, "", 10) shouldBe null
        // wine is to buy, chips were added to another list
        repository.suggestions(PELLE, secret.id, "", 10) shouldBe emptyList()
        repository.updateList(OTHER, secret.id, "Mine", shared = true) shouldBe GroceryListUpdate.NotFound
        repository.deleteList(OTHER, secret.id) shouldBe GroceryListDelete.NotFound
        repository.items(PELLE, secret.id)!!.map { it.name } shouldBe listOf("Wine")

        repository.list(OTHER, party.id) shouldBe party.copy(items = 1)
        repository.items(OTHER, party.id)!!.map { it.name } shouldBe listOf("Chips")
        repository.updateItem(OTHER, chips.id, values("Crisps"))!!.name shouldBe "Crisps"
        repository.insertItem(OTHER, party.id, values("Beer"))!!.owner shouldBe OTHER
        repository.checkItem(OTHER, chips.id) shouldBe true
        // every user renames a shared list, only its owner unshares and deletes it (LIST-2)
        (repository.updateList(OTHER, party.id, "Ours", shared = true) as GroceryListUpdate.Updated).list.let {
            it.name shouldBe "Ours"
            it.owner shouldBe PELLE
        }
        repository.updateList(OTHER, party.id, "Ours", shared = false) shouldBe GroceryListUpdate.NotOwner
        // the names are unique among the lists of the owner, also when another user renames
        repository.updateList(OTHER, party.id, "Secret", shared = true) shouldBe GroceryListUpdate.OwnerNameTaken
        repository.updateList(PELLE, party.id, "Secret", shared = true) shouldBe GroceryListUpdate.NameTaken
        repository.updateList(OTHER, secret.id, "Mine", shared = false) shouldBe GroceryListUpdate.NotFound
        repository.deleteList(OTHER, party.id) shouldBe GroceryListDelete.NotFound
        // unshared by its owner, the list is none of the other user's to change, also with the flag they had
        repository.updateList(PELLE, party.id, "Ours", shared = false)
        repository.updateList(OTHER, party.id, "Theirs", shared = false) shouldBe GroceryListUpdate.NotFound
        repository.list(PELLE, party.id)!!.let {
            it.name shouldBe "Ours"
            it.shared shouldBe false
        }
        repository.deleteList(PELLE, party.id) shouldBe GroceryListDelete.Deleted
        repository.items(OTHER, party.id) shouldBe null
    }

    @Test
    fun `a bought item is checked, it leaves the list and stays as its history, it still changes`() {
        val milk = repository.insertItem(PELLE, list(PELLE), values("Milk"))!!
        val bread = repository.insertItem(PELLE, list(PELLE), values("Bread"))!!

        repository.checkItem(OTHER, milk.id) shouldBe false
        repository.checkItem(PELLE, milk.id) shouldBe true
        repository.checkItem(PELLE, milk.id) shouldBe false
        repository.items(PELLE, milk.listId)!!.map { it.id } shouldBe listOf(bread.id)
        repository.items(PELLE, milk.listId, checked = true)!!.map { it.id } shouldBe listOf(milk.id, bread.id)
        database.dsl.fetchOne(GROCERY_ITEMS, GROCERY_ITEMS.ID.eq(milk.id))!!.let {
            it.checkedAt shouldBe now
            it.deletedAt shouldBe null
        }

        // a spelling fixed, it stays bought
        repository.updateItem(PELLE, milk.id, values("Oat milk"))!!.let {
            it.name shouldBe "Oat milk"
            it.checkedAt shouldBe now
        }
        repository.item(PELLE, milk.id)!!.name shouldBe "Oat milk"
        repository.deleteItem(PELLE, milk.id) shouldBe true
        repository.items(PELLE, milk.listId, checked = true)!!.map { it.id } shouldBe listOf(bread.id)
    }

    @Test
    fun `an item to buy deleted goes for good, no suggestion counts it`() {
        val own = list(PELLE)
        val typo = repository.insertItem(PELLE, own, values("Mlik"))!!
        repository.deleteItem(OTHER, typo.id) shouldBe false
        repository.deleteItem(PELLE, typo.id) shouldBe true
        repository.deleteItem(PELLE, typo.id) shouldBe false
        repository.items(PELLE, own) shouldBe emptyList()
        repository.suggestions(PELLE, own, "", 10) shouldBe emptyList()
        database.dsl.fetchOne(GROCERY_ITEMS, GROCERY_ITEMS.ID.eq(typo.id))!!.deletedAt shouldBe now

        // a name changed before it is bought is the one suggested
        repository.insertItem(PELLE, own, values("Bred"))!!.let {
            repository.updateItem(PELLE, it.id, values("Bread"))
            repository.checkItem(PELLE, it.id)
        }
        repository.suggestions(PELLE, own, "", 10)!!.map { it.name } shouldBe listOf("Bread")
    }

    @Test
    fun `a suggestion has the values of the latest item of its name, forgetting it deletes the bought items of the name for good`() {
        val own = list(PELLE)
        val photo = FileRepository(database).insert("b".repeat(64), "image/jpeg", 1, PELLE).id
        repository.insertItem(PELLE, own, values("Milk", "1", "l")).let { repository.checkItem(PELLE, it!!.id) }
        val latest = repository.insertItem(PELLE, own, values("milk", "2", "l"))!!
        repository.setPhoto(PELLE, latest.id, photo)
        repository.checkItem(PELLE, latest.id)

        val milk = repository.suggestions(PELLE, own, "", 10)!!.single()
        listOf(milk.id, milk.name, milk.timesUsed, milk.lastUsedAt) shouldBe listOf(latest.id, "milk", 2, now)
        milk.amount!!.compareTo(BigDecimal("2")) shouldBe 0
        // the next item takes the photo of the latest
        val toBuy = repository.insertItem(PELLE, own, values("MILK"))!!
        toBuy.photoFileId shouldBe photo

        // the item to buy stays, the bought ones go; the name comes back once it is bought again
        repository.forget(OTHER, own, milk.id) shouldBe false
        repository.forget(PELLE, own, milk.id) shouldBe true
        repository.forget(PELLE, own, milk.id) shouldBe false
        repository.items(PELLE, own)!!.map { it.id } shouldBe listOf(toBuy.id)
        repository.checkItem(PELLE, toBuy.id)
        repository.suggestions(PELLE, own, "", 10)!!.single().let {
            it.name shouldBe "MILK"
            it.timesUsed shouldBe 1
        }
    }

    @Test
    fun `a list counts its items to buy, a bought item no longer`() {
        val own = list(PELLE)
        val party = repository.insertList(PELLE, "Party", shared = false)!!
        party.items shouldBe 0
        val milk = repository.insertItem(PELLE, own, values("Milk"))!!
        repository.insertItem(PELLE, own, values("Bread"))!!
        repository.checkItem(PELLE, milk.id) shouldBe true

        repository.lists(PELLE).associate { it.name to it.items } shouldBe mapOf(DEFAULT_GROCERY_LIST to 1, "Party" to 0)
        repository.list(PELLE, own)!!.items shouldBe 1
        (repository.updateList(PELLE, own, "Home", shared = true) as GroceryListUpdate.Updated).list.items shouldBe 1
    }

    @Test
    fun `every item teaches the list's product of its name, in any case, the suggestions leave out what is to buy`() {
        val own = list(PELLE)
        // bought, no longer to buy
        val milk = repository.insertItem(PELLE, own, values("Milk", "1", "l"))!!
        repository.checkItem(PELLE, milk.id)
        val again = repository.insertItem(PELLE, own, values("milk", "2", "l"))!!
        val bread = repository.insertItem(PELLE, own, values("Bread"))!!
        repository.checkItem(PELLE, bread.id)
        repository.checkItem(PELLE, again.id)

        val suggestions = repository.suggestions(PELLE, own, "", 10)!!
        suggestions.map { it.name to it.timesUsed } shouldBe listOf("milk" to 2, "Bread" to 1)
        suggestions.forEach { it.listId shouldBe own }
        suggestions.first().amount!!.compareTo(BigDecimal("2")) shouldBe 0
        repository.suggestions(PELLE, own, "MIL", 10)!!.map { it.name } shouldBe listOf("milk")
        // milk is to buy on the list
        repository.insertItem(PELLE, own, values("Milk"))
        repository.suggestions(PELLE, own, "", 10)!!.map { it.name } shouldBe listOf("Bread")

        repository.forget(PELLE, own, suggestions.first().id) shouldBe true
        repository.forget(PELLE, own, suggestions.first().id) shouldBe false
        repository.suggestions(PELLE, own, "", 10)!!.map { it.name } shouldBe listOf("Bread")
    }

    @Test
    fun `a list learns alone, what one list learned is never suggested on another, not of the same user`() {
        val own = list(PELLE)
        val party = repository.insertList(PELLE, "Party", shared = false)!!.id
        val dairy = (repository.insertCategory(PELLE, own, GroceryCategoryValues("Dairy", null), 30) as GroceryCategoryChange.Saved).category.id
        repository.insertItem(PELLE, own, values("Milk", categoryId = dairy)).let { repository.checkItem(PELLE, it!!.id) }
        repository.insertItem(PELLE, party, values("Chips")).let { repository.checkItem(PELLE, it!!.id) }
        repository.insertItem(PELLE, party, values("Milk", "2", "l")).let { repository.checkItem(PELLE, it!!.id) }

        repository.suggestions(PELLE, own, "", 10)!!.map { it.name to it.categoryId } shouldBe listOf("Milk" to dairy)
        repository.suggestions(PELLE, party, "", 10)!!.map { it.name to it.timesUsed } shouldBe listOf("Chips" to 1, "Milk" to 1)
        val chips = repository.suggestions(PELLE, party, "chips", 10)!!.single()
        // forgotten on its own list only
        repository.forget(PELLE, own, chips.id) shouldBe false
        repository.forget(PELLE, party, chips.id) shouldBe true
    }

    @Test
    fun `a photo goes onto the list's product of the name, the next item of the name on that list only gets it`() {
        val own = list(PELLE)
        val party = repository.insertList(PELLE, "Party", shared = false)!!.id
        val photo = FileRepository(database).insert("a".repeat(64), "image/jpeg", 1, PELLE).id
        val milk = repository.insertItem(PELLE, own, values("Milk"))!!
        repository.setPhoto(PELLE, milk.id, photo)!!.photoFileId shouldBe photo
        repository.checkItem(PELLE, milk.id)
        repository.insertItem(PELLE, party, values("Milk")).let { repository.checkItem(PELLE, it!!.id) }

        repository.insertItem(PELLE, own, values("milk"))!!.photoFileId shouldBe photo
        repository.insertItem(PELLE, party, values("milk"))!!.photoFileId shouldBe null
    }

    @Test
    fun `what a shared list learned is seen and forgotten by everyone who sees it, it stays with the list, a deleted list takes it`() {
        // not the last list of its owner, it can be deleted
        list(PELLE)
        val party = repository.insertList(PELLE, "Party", shared = true)!!.id
        repository.insertItem(OTHER, party, values("Chips")).let { repository.checkItem(OTHER, it!!.id) }
        repository.insertItem(PELLE, party, values("Dip")).let { repository.checkItem(PELLE, it!!.id) }

        repository.suggestions(PELLE, party, "", 10)!!.map { it.name } shouldBe listOf("Chips", "Dip")
        repository.suggestions(OTHER, party, "", 10)!!.map { it.name } shouldBe listOf("Chips", "Dip")
        // the other user learned nothing on their own list
        repository.suggestions(OTHER, list(OTHER), "", 10) shouldBe emptyList()

        // unshared: the owner keeps it, the other user no longer sees it; shared again it is back
        repository.updateList(PELLE, party, "Party", shared = false)
        repository.suggestions(OTHER, party, "", 10) shouldBe null
        val dip = repository.suggestions(PELLE, party, "dip", 10)!!.single()
        repository.forget(OTHER, party, dip.id) shouldBe false
        repository.updateList(PELLE, party, "Party", shared = true)
        repository.forget(OTHER, party, dip.id) shouldBe true
        repository.suggestions(PELLE, party, "", 10)!!.map { it.name } shouldBe listOf("Chips")

        repository.deleteList(PELLE, party) shouldBe GroceryListDelete.Deleted
        repository.suggestions(PELLE, party, "", 10) shouldBe null
        database.dsl.fetchCount(GROCERY_ITEMS, GROCERY_ITEMS.LIST_ID.eq(party), GROCERY_ITEMS.DELETED_AT.isNull) shouldBe 0
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

        // all of them bought
        repository.items(PELLE, list(PELLE))!!.forEach { repository.checkItem(PELLE, it.id) }
        repository.suggestions(PELLE, list(PELLE), "", 10)!!.map { it.name to it.timesUsed } shouldBe
            listOf("a\u0308pfel" to 3, "milk bread" to 2, "Müsli" to 1, "Musli" to 1)
        repository.suggestions(PELLE, list(PELLE), "ÄP", 10)!!.map { it.timesUsed } shouldBe listOf(3)
        repository.suggestions(PELLE, list(PELLE), "MÜS", 10)!!.map { it.name } shouldBe listOf("Müsli")
    }

    @Test
    fun `an item to buy hides the suggestion of its name in any case`() {
        val apples = repository.insertItem(PELLE, list(PELLE), values("Äpfel"))!!
        repository.checkItem(PELLE, apples.id)
        repository.insertItem(PELLE, list(PELLE), values("Bread"))
        repository.suggestions(PELLE, apples.listId, "Ä", 10)!!.map { it.name } shouldBe listOf("Äpfel")

        repository.insertItem(PELLE, apples.listId, values("ÄPFEL"))
        repository.suggestions(PELLE, apples.listId, "Ä", 10) shouldBe emptyList()
    }

    @Test
    fun `a list has its categories in their order, every user who sees it changes them, an item takes one of its list only`() {
        val own = list(PELLE)
        val party = repository.insertList(PELLE, "Party", shared = true, categories = listOf(GroceryCategoryValues("Snacks", "🍿"), GroceryCategoryValues("Drinks", null)))!!.id
        val (snacks, drinks) = repository.categories(OTHER, party)!!
        listOf(snacks.name, snacks.icon, snacks.position) shouldBe listOf("Snacks", "🍿", 0)
        drinks.position shouldBe 1
        repository.categories(OTHER, own) shouldBe null
        repository.categories(PELLE, own) shouldBe emptyList()

        // a private list of another user is none of theirs
        repository.insertCategory(OTHER, own, GroceryCategoryValues("Mine", null), 30) shouldBe GroceryCategoryChange.NotFound
        repository.insertCategory(PELLE, party, GroceryCategoryValues("Snacks", null), 30) shouldBe GroceryCategoryChange.NameTaken
        repository.insertCategory(PELLE, party, GroceryCategoryValues("Fruit", null), 2) shouldBe GroceryCategoryChange.TooMany
        val fruit = (repository.insertCategory(PELLE, party, GroceryCategoryValues("Fruit", "🍎"), 30) as GroceryCategoryChange.Saved).category
        fruit.position shouldBe 2
        repository.updateCategory(PELLE, party, fruit.id, GroceryCategoryValues("Drinks", null)) shouldBe GroceryCategoryChange.NameTaken
        // every user who sees the shared list changes its categories
        (repository.updateCategory(OTHER, party, fruit.id, GroceryCategoryValues("Obst", "🍎")) as GroceryCategoryChange.Saved).category.name shouldBe "Obst"
        repository.updateCategory(PELLE, own, fruit.id, GroceryCategoryValues("Obst", null)) shouldBe GroceryCategoryChange.NotFound

        repository.orderCategories(PELLE, party, listOf(fruit.id, snacks.id)) shouldBe GroceryCategoryOrder.Mismatch
        repository.orderCategories(PELLE, party, listOf(fruit.id, snacks.id, snacks.id)) shouldBe GroceryCategoryOrder.Mismatch
        repository.orderCategories(OTHER, own, emptyList()) shouldBe GroceryCategoryOrder.NotFound
        (repository.orderCategories(PELLE, party, listOf(fruit.id, snacks.id, drinks.id)) as GroceryCategoryOrder.Ordered).categories.map { it.id } shouldBe
            listOf(fruit.id, snacks.id, drinks.id)

        // a category of another list is none
        repository.insertItem(PELLE, own, values("Chips", categoryId = snacks.id))!!.categoryId shouldBe null
        val chips = repository.insertItem(OTHER, party, values("Chips", categoryId = drinks.id))!!
        chips.categoryId shouldBe drinks.id
        repository.setCategory(OTHER, chips.id, fruit.id)!!.categoryId shouldBe fruit.id
        repository.setCategory(OTHER, chips.id, 9999) shouldBe null
        repository.updateItem(OTHER, chips.id, values("Crisps", categoryId = snacks.id))!!.categoryId shouldBe snacks.id

        // deleted, it goes from its items and the suggestions of the list
        repository.deleteCategory(OTHER, party, snacks.id) shouldBe true
        repository.deleteCategory(PELLE, party, snacks.id) shouldBe false
        repository.item(PELLE, chips.id)!!.let {
            it.categoryId shouldBe null
            it.updatedAt shouldBe now
        }
        repository.categories(PELLE, party)!!.map { it.name } shouldBe listOf("Obst", "Drinks")

        // a deleted list takes them along
        repository.deleteList(PELLE, party) shouldBe GroceryListDelete.Deleted
        database.dsl.fetchCount(GROCERY_CATEGORIES, GROCERY_CATEGORIES.LIST_ID.eq(party), GROCERY_CATEGORIES.DELETED_AT.isNull) shouldBe 0
    }

    @Test
    fun `the list of the sign-in gets the categories, once`() {
        repository.ensureList(PELLE, DEFAULT_GROCERY_LIST, listOf(GroceryCategoryValues("Bakery", "🥖")))
        repository.ensureList(PELLE, DEFAULT_GROCERY_LIST, listOf(GroceryCategoryValues("Other", null)))
        repository.categories(PELLE, repository.lists(PELLE).single().id)!!.map { it.name } shouldBe listOf("Bakery")
    }

    @Test
    fun `the schema takes positive amounts only and a unit with an amount only`() {
        shouldThrow<DataAccessException> { repository.insertItem(PELLE, list(PELLE), values("Milk", "0")) }
        shouldThrow<DataAccessException> { repository.insertItem(PELLE, list(PELLE), values("Milk", unit = "l")) }
        repository.insertItem(PELLE, list(PELLE), values("Milk", "1.5", "l"))!!.amount!!.compareTo(BigDecimal("1.5")) shouldBe 0
    }
}
