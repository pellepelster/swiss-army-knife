package io.pelle.sak.backend.grocery

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.auth.LoginListener
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.grocery.DEFAULT_GROCERY_LIST
import io.pelle.sak.model.grocery.GroceryItem
import io.pelle.sak.model.grocery.GroceryItemValues
import io.pelle.sak.model.grocery.GroceryList
import io.pelle.sak.model.grocery.GroceryListUpdate
import io.pelle.sak.model.grocery.GroceryProduct
import io.pelle.sak.model.grocery.GroceryRepository
import java.math.BigDecimal
import java.time.Clock

// the fixed categories of the items, in the order a list shows them (GROCERY-6); the only list of
// them, the schema does not check the category, itemValues does
enum class GroceryCategory(
    val label: String,
) {
    FRUIT_VEGETABLES("Fruit & vegetables"),
    BAKERY("Bakery"),
    DAIRY("Dairy & eggs"),
    MEAT_FISH("Meat & fish"),
    PANTRY("Pantry"),
    FROZEN("Frozen"),
    DRINKS("Drinks"),
    HOUSEHOLD("Household"),
    OTHER("Other"),
}

// what the user gives of an item
data class GroceryItemInput(
    val name: String,
    val amount: BigDecimal?,
    val unit: String?,
    val category: String?,
)

sealed interface GroceryListResult {
    data class Saved(
        val list: GroceryList,
    ) : GroceryListResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : GroceryListResult

    data object NotFound : GroceryListResult

    data object Exists : GroceryListResult
}

sealed interface GroceryItemResult {
    data class Saved(
        val item: GroceryItem,
    ) : GroceryItemResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : GroceryItemResult

    data object NotFound : GroceryItemResult
}

// the grocery lists, their items and the suggestions, the only way to them; it checks the values,
// GroceryRepository checks who sees and changes what (MODEL-6)
class GroceryService(
    private val repository: GroceryRepository,
) : LoginListener {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
    ) : this(GroceryRepository(database, clock))

    // the first list of a user who has none (LIST-1)
    override fun signedIn(owner: String) = repository.ensureList(owner, DEFAULT_GROCERY_LIST)

    fun lists(user: String): List<GroceryList> = repository.lists(user)

    fun createList(
        user: String,
        name: String,
        shared: Boolean,
    ): GroceryListResult {
        val normalized = name.trim()
        listNameError(normalized)?.let { return GroceryListResult.Invalid(listOf(it)) }
        return repository.insertList(user, normalized, shared)?.let { GroceryListResult.Saved(it) } ?: GroceryListResult.Exists
    }

    fun updateList(
        user: String,
        id: Int,
        name: String,
        shared: Boolean,
    ): GroceryListResult {
        val normalized = name.trim()
        listNameError(normalized)?.let { return GroceryListResult.Invalid(listOf(it)) }
        return when (val update = repository.updateList(user, id, normalized, shared)) {
            is GroceryListUpdate.Updated -> GroceryListResult.Saved(update.list)
            GroceryListUpdate.NotFound -> GroceryListResult.NotFound
            GroceryListUpdate.NameTaken -> GroceryListResult.Exists
        }
    }

    // with its items; false when the user has no such list of their own
    fun deleteList(
        user: String,
        id: Int,
    ): Boolean = repository.deleteList(user, id)

    // the items to buy of a list the user sees, null for another one: by the order of their
    // category, the ones without a category last, then in the order they were added
    fun items(
        user: String,
        listId: Int,
    ): List<GroceryItem>? = repository.items(user, listId)?.sortedWith(compareBy({ categoryOrder(it.category) }, { it.id }))

    // on a list the user sees, an item names its list (LIST-1); the product of its name learns it
    // (GROCERY-7)
    fun addItem(
        user: String,
        listId: Int,
        input: GroceryItemInput,
    ): GroceryItemResult {
        val values = itemValues(input).getOrElse { return GroceryItemResult.Invalid(it) }
        return repository.insertItem(user, listId, values)?.let { GroceryItemResult.Saved(it) } ?: GroceryItemResult.NotFound
    }

    fun updateItem(
        user: String,
        id: Int,
        input: GroceryItemInput,
    ): GroceryItemResult {
        val values = itemValues(input).getOrElse { return GroceryItemResult.Invalid(it) }
        return repository.updateItem(user, id, values)?.let { GroceryItemResult.Saved(it) } ?: GroceryItemResult.NotFound
    }

    // bought (GROCERY-2), by any user who sees its list (LIST-2); false when the user sees no such item
    fun deleteItem(
        user: String,
        id: Int,
    ): Boolean = repository.deleteItem(user, id)

    // the products of the user whose name contains the text, the ones bought most and last first;
    // the ones to buy on the list are left out
    fun suggestions(
        user: String,
        text: String,
        listId: Int?,
    ): List<GroceryProduct> = repository.products(user, text.trim(), listId, MAX_SUGGESTIONS)

    fun forget(
        user: String,
        id: Int,
    ): Boolean = repository.deleteProduct(user, id)

    private fun listNameError(name: String) =
        if (name.isEmpty() || name.length > MAX_LIST_NAME_LENGTH || name.any { it.isISOControl() }) {
            ErrorMessage("A list name must have 1 to $MAX_LIST_NAME_LENGTH characters and no control characters.")
        } else {
            null
        }

    private fun itemValues(input: GroceryItemInput): Result<GroceryItemValues, List<ErrorMessage>> {
        val name = input.name.trim()
        val unit = input.unit?.trim()?.ifEmpty { null }
        val amount = input.amount?.stripTrailingZeros()
        val errors =
            listOfNotNull(
                if (name.isEmpty() || name.length > MAX_ITEM_NAME_LENGTH || name.any { it.isISOControl() }) {
                    ErrorMessage("An item name must have 1 to $MAX_ITEM_NAME_LENGTH characters and no control characters.")
                } else {
                    null
                },
                if (amount != null && (amount.signum() <= 0 || amount.scale() > 3 || amount > MAX_AMOUNT)) {
                    ErrorMessage("An amount must be a positive number up to $MAX_AMOUNT with at most 3 decimal places.")
                } else {
                    null
                },
                if (unit != null && (unit.length > MAX_UNIT_LENGTH || unit.any { it.isISOControl() })) {
                    ErrorMessage("A unit must have at most $MAX_UNIT_LENGTH characters and no control characters.")
                } else {
                    null
                },
                if (unit != null && amount == null) ErrorMessage("A unit needs an amount.") else null,
                if (input.category != null && GroceryCategory.entries.none { it.name == input.category }) {
                    ErrorMessage("There is no category \"${input.category}\".")
                } else {
                    null
                },
            )
        return if (errors.isEmpty()) Result.Ok(GroceryItemValues(name, amount, unit, input.category)) else Result.Err(errors)
    }

    private fun categoryOrder(category: String?) = category?.let { GroceryCategory.valueOf(it).ordinal } ?: GroceryCategory.entries.size

    // the values of an item or why they are not valid
    private sealed interface Result<out T, out E> {
        data class Ok<T>(
            val value: T,
        ) : Result<T, Nothing>

        data class Err<E>(
            val error: E,
        ) : Result<Nothing, E>
    }

    private inline fun <T, E> Result<T, E>.getOrElse(handle: (E) -> T): T =
        when (this) {
            is Result.Ok -> value
            is Result.Err -> handle(error)
        }

    companion object {
        const val MAX_LIST_NAME_LENGTH = 64

        const val MAX_ITEM_NAME_LENGTH = 128

        const val MAX_UNIT_LENGTH = 16

        val MAX_AMOUNT = BigDecimal("9999999.999")

        // the most suggestions answered at once
        const val MAX_SUGGESTIONS = 10
    }
}
