package io.pelle.sak.backend.grocery

import io.pelle.sak.api.Catalog
import io.pelle.sak.api.TextKey
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.auth.LoginListener
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.grocery.DEFAULT_GROCERY_LIST
import io.pelle.sak.model.grocery.GroceryItem
import io.pelle.sak.model.grocery.GroceryItemValues
import io.pelle.sak.model.grocery.GroceryList
import io.pelle.sak.model.grocery.GroceryListDelete
import io.pelle.sak.model.grocery.GroceryListUpdate
import io.pelle.sak.model.grocery.GroceryProduct
import io.pelle.sak.model.grocery.GroceryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.BreakIterator
import java.time.Clock

// the fixed categories of the items, in the order a list shows them (GROCERY-6); the only list of
// them, the schema does not check the category, itemValues does
enum class GroceryCategory(
    val label: TextKey,
) {
    FRUIT_VEGETABLES(GroceryTexts.FRUIT_VEGETABLES),
    BAKERY(GroceryTexts.BAKERY),
    DAIRY(GroceryTexts.DAIRY),
    MEAT_FISH(GroceryTexts.MEAT_FISH),
    PANTRY(GroceryTexts.PANTRY),
    FROZEN(GroceryTexts.FROZEN),
    DRINKS(GroceryTexts.DRINKS),
    HOUSEHOLD(GroceryTexts.HOUSEHOLD),
    OTHER(GroceryTexts.OTHER),
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
    private val changes: GroceryChanges = GroceryChanges(),
) : LoginListener {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
    ) : this(GroceryRepository(database, clock))

    // the ids of the lists the user sees that change from now on, a list deleted included (LIST-4)
    fun changes(user: String): Flow<Int> = changes.lists.filter { withContext(Dispatchers.IO) { repository.seesList(user, it) } }

    // the first list of a user who has none (LIST-1)
    override fun signedIn(owner: String) = repository.ensureList(owner, DEFAULT_GROCERY_LIST)

    fun lists(user: String): List<GroceryList> = repository.lists(user)

    // the icon is one emoji, empty or none for no icon
    fun createList(
        user: String,
        name: String,
        shared: Boolean,
        icon: String? = null,
    ): GroceryListResult {
        val normalized = name.trim()
        val emoji = icon?.trim()?.ifEmpty { null }
        listErrors(normalized, emoji)?.let { return GroceryListResult.Invalid(it) }
        return repository.insertList(user, normalized, shared, emoji)?.let { GroceryListResult.Saved(changed(it)) } ?: GroceryListResult.Exists
    }

    fun updateList(
        user: String,
        id: Int,
        name: String,
        shared: Boolean,
        icon: String? = null,
    ): GroceryListResult {
        val normalized = name.trim()
        val emoji = icon?.trim()?.ifEmpty { null }
        listErrors(normalized, emoji)?.let { return GroceryListResult.Invalid(it) }
        return when (val update = repository.updateList(user, id, normalized, shared, emoji)) {
            is GroceryListUpdate.Updated -> GroceryListResult.Saved(changed(update.list))
            GroceryListUpdate.NotFound -> GroceryListResult.NotFound
            GroceryListUpdate.NameTaken -> GroceryListResult.Exists
        }
    }

    // with its items, not the last list of the user's own (LIST-1)
    fun deleteList(
        user: String,
        id: Int,
    ): GroceryListDelete = repository.deleteList(user, id).also { if (it == GroceryListDelete.Deleted) changes.changed(id) }

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
        return repository.insertItem(user, listId, values)?.let { GroceryItemResult.Saved(changed(it)) } ?: GroceryItemResult.NotFound
    }

    fun updateItem(
        user: String,
        id: Int,
        input: GroceryItemInput,
    ): GroceryItemResult {
        val values = itemValues(input).getOrElse { return GroceryItemResult.Invalid(it) }
        return repository.updateItem(user, id, values)?.let { GroceryItemResult.Saved(changed(it)) } ?: GroceryItemResult.NotFound
    }

    // bought (GROCERY-3), by any user who sees its list (LIST-2); false when the user sees no such item
    fun deleteItem(
        user: String,
        id: Int,
    ): Boolean {
        val listId = repository.item(user, id)?.listId ?: return false
        return repository.deleteItem(user, id).also { if (it) changes.changed(listId) }
    }

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

    private fun changed(list: GroceryList) = list.also { changes.changed(it.id) }

    private fun changed(item: GroceryItem) = item.also { changes.changed(it.listId) }

    private fun listErrors(
        name: String,
        icon: String?,
    ): List<ErrorMessage>? =
        listOfNotNull(
            listNameError(name),
            icon?.takeUnless(::isOneEmoji)?.let { ErrorMessage(GroceryTexts.INVALID_ICON) },
        ).ifEmpty { null }

    private fun listNameError(name: String) =
        if (name.isEmpty() || name.length > MAX_LIST_NAME_LENGTH || name.any { it.isISOControl() }) {
            ErrorMessage(GroceryTexts.INVALID_LIST_NAME, "max" to MAX_LIST_NAME_LENGTH)
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
                    ErrorMessage(GroceryTexts.INVALID_ITEM_NAME, "max" to MAX_ITEM_NAME_LENGTH)
                } else {
                    null
                },
                if (amount != null && (amount.signum() <= 0 || amount.scale() > 3 || amount > MAX_AMOUNT)) {
                    ErrorMessage(GroceryTexts.INVALID_AMOUNT, "max" to MAX_AMOUNT)
                } else {
                    null
                },
                if (unit != null && (unit.length > MAX_UNIT_LENGTH || unit.any { it.isISOControl() })) {
                    ErrorMessage(GroceryTexts.INVALID_UNIT, "max" to MAX_UNIT_LENGTH)
                } else {
                    null
                },
                if (unit != null && amount == null) ErrorMessage(GroceryTexts.UNIT_WITHOUT_AMOUNT) else null,
                if (input.category != null && GroceryCategory.entries.none { it.name == input.category }) {
                    ErrorMessage(GroceryTexts.UNKNOWN_CATEGORY, "category" to input.category)
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

        // the longest emoji in utf-16, e.g. a flag of a region or a family joined of several
        const val MAX_ICON_LENGTH = 32

        // one emoji: a single grapheme (the character iterator of the jdk counts the extended
        // grapheme clusters) that starts with a pictograph, a pair of regional indicators (a flag)
        // or a keycap
        fun isOneEmoji(text: String): Boolean {
            if (text.isEmpty() || text.length > MAX_ICON_LENGTH) return false
            val graphemes = BreakIterator.getCharacterInstance().apply { setText(text) }
            if (graphemes.next() != text.length) return false
            val first = text.codePointAt(0)
            return Character.isExtendedPictographic(first) || first in REGIONAL_INDICATORS || text.contains(KEYCAP)
        }

        private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

        private const val KEYCAP = '\u20E3'

        const val MAX_ITEM_NAME_LENGTH = 128

        const val MAX_UNIT_LENGTH = 16

        val MAX_AMOUNT = BigDecimal("9999999.999")

        // the most suggestions answered at once
        const val MAX_SUGGESTIONS = 10
    }
}

// the texts of the grocery lists (i18n/grocery.<language>.yml)
object GroceryTexts : Catalog("grocery") {
    val FRUIT_VEGETABLES = text("category.fruit_vegetables")
    val BAKERY = text("category.bakery")
    val DAIRY = text("category.dairy")
    val MEAT_FISH = text("category.meat_fish")
    val PANTRY = text("category.pantry")
    val FROZEN = text("category.frozen")
    val DRINKS = text("category.drinks")
    val HOUSEHOLD = text("category.household")
    val OTHER = text("category.other")
    val INVALID_ICON = text("invalid_icon")
    val INVALID_LIST_NAME = text("invalid_list_name")
    val INVALID_ITEM_NAME = text("invalid_item_name")
    val INVALID_AMOUNT = text("invalid_amount")
    val INVALID_UNIT = text("invalid_unit")
    val UNIT_WITHOUT_AMOUNT = text("unit_without_amount")
    val UNKNOWN_CATEGORY = text("unknown_category")
    val LIST_EXISTS = text("list_exists")
    val LAST_LIST = text("last_list")
}
