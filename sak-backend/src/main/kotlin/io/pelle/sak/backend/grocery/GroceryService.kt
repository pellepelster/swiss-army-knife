package io.pelle.sak.backend.grocery

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.grocery.DEFAULT_GROCERY_LIST
import io.pelle.sak.model.grocery.GroceryItem
import io.pelle.sak.model.grocery.GroceryItemValues
import io.pelle.sak.model.grocery.GroceryList
import io.pelle.sak.model.grocery.GroceryProduct
import io.pelle.sak.model.grocery.GroceryRepository
import java.math.BigDecimal
import java.time.Clock

// the fixed categories of the items, in the order a list shows them (GROCERY-6); the schema takes
// these names only
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

// what the user gives of an item, before it is checked
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

    // the default list keeps its name and is never deleted (LIST-1)
    data object Default : GroceryListResult
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

// the grocery lists, their items and the suggestions, the only way to them. A user sees and changes
// their own lists and the items on them, and the shared lists of the others with their items
// (LIST-2); only the owner of a list renames, shares or deletes it
class GroceryService(
    private val repository: GroceryRepository,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
    ) : this(GroceryRepository(database, clock))

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
        val list = repository.list(id)?.takeIf { it.owner == user } ?: return GroceryListResult.NotFound
        val normalized = name.trim()
        listNameError(normalized)?.let { return GroceryListResult.Invalid(listOf(it)) }
        if (list.name == DEFAULT_GROCERY_LIST && normalized != DEFAULT_GROCERY_LIST) return GroceryListResult.Default
        return repository.updateList(id, normalized, shared)?.let { GroceryListResult.Saved(it) } ?: GroceryListResult.Exists
    }

    fun deleteList(
        user: String,
        id: Int,
    ): GroceryListResult {
        val list = repository.list(id)?.takeIf { it.owner == user } ?: return GroceryListResult.NotFound
        if (list.name == DEFAULT_GROCERY_LIST) return GroceryListResult.Default
        repository.deleteList(id)
        return GroceryListResult.Saved(list)
    }

    // the items of a list the user sees, null for another one: the ones to buy first, each by the
    // order of its category, the ones without a category last, then in the order they were added
    fun items(
        user: String,
        listId: Int,
    ): List<GroceryItem>? {
        visibleList(user, listId) ?: return null
        return repository.items(listId).sortedWith(
            compareBy<GroceryItem>({ it.checkedAt != null }, { categoryOrder(it.category) }, { it.id }),
        )
    }

    // on the list, or on the default list of the user when none is given (LIST-1); the product of
    // its name learns it (GROCERY-7)
    fun addItem(
        user: String,
        listId: Int?,
        input: GroceryItemInput,
    ): GroceryItemResult {
        if (listId != null) visibleList(user, listId) ?: return GroceryItemResult.NotFound
        val values = itemValues(input).getOrElse { return GroceryItemResult.Invalid(it) }
        return GroceryItemResult.Saved(repository.insertItem(user, listId, values))
    }

    fun updateItem(
        user: String,
        id: Int,
        input: GroceryItemInput,
        checked: Boolean,
    ): GroceryItemResult {
        visibleItem(user, id) ?: return GroceryItemResult.NotFound
        val values = itemValues(input).getOrElse { return GroceryItemResult.Invalid(it) }
        return repository.updateItem(id, values, checked, user)?.let { GroceryItemResult.Saved(it) } ?: GroceryItemResult.NotFound
    }

    // false when the user sees no such item
    fun deleteItem(
        user: String,
        id: Int,
    ): Boolean = visibleItem(user, id) != null && repository.deleteItem(id)

    // the checked items of the list go, null when the user sees no such list
    fun clearChecked(
        user: String,
        listId: Int,
    ): Int? = visibleList(user, listId)?.let { repository.deleteChecked(listId) }

    // the products of the user whose name contains the text, the ones bought most and last first;
    // the ones to buy on the list are left out
    fun suggestions(
        user: String,
        text: String,
        listId: Int?,
    ): List<GroceryProduct> = repository.products(user, text.trim(), listId?.takeIf { visibleList(user, it) != null }, MAX_SUGGESTIONS)

    fun forget(
        user: String,
        id: Int,
    ): Boolean = repository.deleteProduct(user, id)

    // the own lists of the user and the shared ones (LIST-2)
    private fun visibleList(
        user: String,
        id: Int,
    ) = repository.list(id)?.takeIf { it.owner == user || it.shared }

    private fun visibleItem(
        user: String,
        id: Int,
    ) = repository.item(id)?.takeIf { visibleList(user, it.listId) != null }

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
