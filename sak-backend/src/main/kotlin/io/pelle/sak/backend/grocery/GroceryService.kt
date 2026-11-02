package io.pelle.sak.backend.grocery

import io.pelle.sak.api.Catalog
import io.pelle.sak.api.TextKey
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.api.Language
import io.pelle.sak.api.render
import io.pelle.sak.auth.LoginListener
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.grocery.DEFAULT_GROCERY_LIST
import io.pelle.sak.model.files.StoredFile
import io.pelle.sak.model.grocery.GroceryCategory
import io.pelle.sak.model.grocery.GroceryCategoryChange
import io.pelle.sak.model.grocery.GroceryCategoryOrder
import io.pelle.sak.model.grocery.GroceryCategoryValues
import io.pelle.sak.model.grocery.GroceryItem
import io.pelle.sak.model.grocery.GroceryItemValues
import io.pelle.sak.model.grocery.GroceryList
import io.pelle.sak.model.grocery.GroceryListDelete
import io.pelle.sak.model.grocery.GroceryListUpdate
import io.pelle.sak.model.grocery.GrocerySuggestion
import io.pelle.sak.model.grocery.GroceryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.BreakIterator
import java.time.Clock

// the categories a new list starts with, in their order (GROCERY-13): the name in the language of
// the request that makes the list, and an icon
enum class DefaultGroceryCategory(
    val label: TextKey,
    val icon: String,
) {
    FRUIT_VEGETABLES(GroceryTexts.FRUIT_VEGETABLES, "🥦"),
    BAKERY(GroceryTexts.BAKERY, "🥖"),
    DAIRY(GroceryTexts.DAIRY, "🧀"),
    MEAT_FISH(GroceryTexts.MEAT_FISH, "🥩"),
    PANTRY(GroceryTexts.PANTRY, "🥫"),
    FROZEN(GroceryTexts.FROZEN, "🧊"),
    DRINKS(GroceryTexts.DRINKS, "🥤"),
    HOUSEHOLD(GroceryTexts.HOUSEHOLD, "🧻"),
    OTHER(GroceryTexts.OTHER, "🛒"),
    ;

    companion object {
        fun of(language: Language) = entries.map { GroceryCategoryValues(it.label.render(language), it.icon) }
    }
}

// what the user gives of an item
data class GroceryItemInput(
    val name: String,
    val amount: BigDecimal?,
    val unit: String?,
    // a category of the item's list, none when null
    val categoryId: Int?,
)

sealed interface GroceryCategoryResult {
    data class Saved(
        val category: GroceryCategory,
    ) : GroceryCategoryResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : GroceryCategoryResult

    // the user sees no such list, or it has no such category
    data object NotFound : GroceryCategoryResult

    // the list has a category of the name
    data object Exists : GroceryCategoryResult
}

sealed interface GroceryCategoriesResult {
    data class Ordered(
        val categories: List<GroceryCategory>,
    ) : GroceryCategoriesResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : GroceryCategoriesResult

    // the user sees no such list
    data object NotFound : GroceryCategoriesResult
}

sealed interface GroceryListResult {
    data class Saved(
        val list: GroceryList,
    ) : GroceryListResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : GroceryListResult

    data object NotFound : GroceryListResult

    data object Exists : GroceryListResult

    // only the owner shares or unshares a list (LIST-2)
    data object NotOwner : GroceryListResult

    // another user renames a shared list to a name the owner has for another list
    data object OwnerExists : GroceryListResult
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

    // the first list of a user who has none (LIST-1), with the default categories in the language
    // of the sign-in (GROCERY-13)
    override fun signedIn(
        owner: String,
        language: Language,
    ) = repository.ensureList(owner, DEFAULT_GROCERY_LIST, DefaultGroceryCategory.of(language))

    fun lists(user: String): List<GroceryList> = repository.lists(user)

    // the icon is one emoji, empty or none for no icon; the list starts with the default categories
    // in the language (GROCERY-13)
    fun createList(
        user: String,
        name: String,
        shared: Boolean,
        icon: String? = null,
        language: Language = Language.FALLBACK,
    ): GroceryListResult {
        val normalized = name.trim()
        val emoji = icon?.trim()?.ifEmpty { null }
        listErrors(normalized, emoji)?.let { return GroceryListResult.Invalid(it) }
        return repository.insertList(user, normalized, shared, emoji, DefaultGroceryCategory.of(language))?.let { GroceryListResult.Saved(changed(it)) }
            ?: GroceryListResult.Exists
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
            GroceryListUpdate.NotOwner -> GroceryListResult.NotOwner
            GroceryListUpdate.OwnerNameTaken -> GroceryListResult.OwnerExists
        }
    }

    // with its items, not the last list of the user's own (LIST-1)
    fun deleteList(
        user: String,
        id: Int,
    ): GroceryListDelete = repository.deleteList(user, id).also { if (it == GroceryListDelete.Deleted) changes.changed(id) }

    // the items to buy of a list the user sees, with checked also the latest bought item of each name
    // that is not to buy (GROCERY-14); null
    // for another list. By the order of their category (GROCERY-13), the ones without a category
    // last; in a category the ones to buy first in the order they were added, then the bought ones,
    // the last bought first
    fun items(
        user: String,
        listId: Int,
        checked: Boolean = false,
    ): List<GroceryItem>? {
        val items = repository.items(user, listId, checked) ?: return null
        val order = repository.categories(user, listId).orEmpty().withIndex().associate { (index, category) -> category.id to index }
        return items.sortedWith(
            compareBy<GroceryItem>({ order[it.categoryId] ?: order.size }, { it.checkedAt != null })
                .thenByDescending { it.checkedAt }
                .thenBy { it.id },
        )
    }

    // on a list the user sees, an item names its list (LIST-1); it takes the photo and, without one
    // given, the category of the latest item of its name (GROCERY-11). Its category is one of the list (GROCERY-13)
    fun addItem(
        user: String,
        listId: Int,
        input: GroceryItemInput,
    ): GroceryItemResult {
        val values = itemValues(input).getOrElse { return GroceryItemResult.Invalid(it) }
        val categories = repository.categories(user, listId) ?: return GroceryItemResult.NotFound
        categoryError(values.categoryId, categories)?.let { return GroceryItemResult.Invalid(listOf(it)) }
        return repository.insertItem(user, listId, values)?.let { GroceryItemResult.Saved(changed(it)) } ?: GroceryItemResult.NotFound
    }

    fun updateItem(
        user: String,
        id: Int,
        input: GroceryItemInput,
    ): GroceryItemResult {
        val values = itemValues(input).getOrElse { return GroceryItemResult.Invalid(it) }
        val listId = repository.item(user, id)?.listId ?: return GroceryItemResult.NotFound
        categoryError(values.categoryId, repository.categories(user, listId).orEmpty())?.let { return GroceryItemResult.Invalid(listOf(it)) }
        return repository.updateItem(user, id, values)?.let { GroceryItemResult.Saved(changed(it)) } ?: GroceryItemResult.NotFound
    }

    // the category of an item on a list the user sees: one of its list, or none; the next item of its
    // name on the list takes it (GROCERY-13)
    fun setCategory(
        user: String,
        id: Int,
        categoryId: Int?,
    ): GroceryItemResult {
        val listId = repository.item(user, id)?.listId ?: return GroceryItemResult.NotFound
        categoryError(categoryId, repository.categories(user, listId).orEmpty())?.let { return GroceryItemResult.Invalid(listOf(it)) }
        repository.setCategory(user, id, categoryId)?.let { return GroceryItemResult.Saved(changed(it)) }
        // the category went between the check and the write, or the item
        return if (repository.item(user, id) != null) GroceryItemResult.Invalid(listOf(ErrorMessage(GroceryTexts.UNKNOWN_CATEGORY))) else GroceryItemResult.NotFound
    }

    // the categories of a list the user sees, in their order; null for another one (GROCERY-13)
    fun categories(
        user: String,
        listId: Int,
    ): List<GroceryCategory>? = repository.categories(user, listId)

    // the last category of a list the user sees (LIST-2)
    fun createCategory(
        user: String,
        listId: Int,
        name: String,
        icon: String?,
    ): GroceryCategoryResult {
        val values = categoryValues(name, icon).getOrElse { return GroceryCategoryResult.Invalid(it) }
        return categoryResult(listId, repository.insertCategory(user, listId, values, MAX_CATEGORIES))
    }

    fun updateCategory(
        user: String,
        listId: Int,
        id: Int,
        name: String,
        icon: String?,
    ): GroceryCategoryResult {
        val values = categoryValues(name, icon).getOrElse { return GroceryCategoryResult.Invalid(it) }
        return categoryResult(listId, repository.updateCategory(user, listId, id, values))
    }

    // its items and the suggestions of the list lose it; false when the user sees no such list or it
    // has no such category
    fun deleteCategory(
        user: String,
        listId: Int,
        id: Int,
    ): Boolean = repository.deleteCategory(user, listId, id).also { if (it) changes.changed(listId) }

    // the ids are all categories of a list the user sees, each once, in their new order
    fun orderCategories(
        user: String,
        listId: Int,
        ids: List<Int>,
    ): GroceryCategoriesResult =
        when (val order = repository.orderCategories(user, listId, ids)) {
            is GroceryCategoryOrder.Ordered -> GroceryCategoriesResult.Ordered(order.categories).also { changes.changed(listId) }
            GroceryCategoryOrder.NotFound -> GroceryCategoriesResult.NotFound
            GroceryCategoryOrder.Mismatch -> GroceryCategoriesResult.Invalid(listOf(ErrorMessage(GroceryTexts.INVALID_CATEGORY_ORDER)))
        }

    // bought (GROCERY-3), by any user who sees its list (LIST-2): it leaves the list and stays as its
    // history (GROCERY-11); false when the user sees no such item to buy
    fun checkItem(
        user: String,
        id: Int,
    ): Boolean {
        val listId = repository.item(user, id)?.takeIf { it.checkedAt == null }?.listId ?: return false
        return repository.checkItem(user, id).also { if (it) changes.changed(listId) }
    }

    // an item, to buy or bought, gone for good, by any user who sees its list; false when the user sees no such item
    fun deleteItem(
        user: String,
        id: Int,
    ): Boolean {
        val listId = repository.item(user, id)?.listId ?: return false
        return repository.deleteItem(user, id).also { if (it) changes.changed(listId) }
    }

    // the photo of an item, to buy or bought, on a list the user sees: a file the user uploaded that is an image, or
    // none to take it away; the next item of its name on the list takes it (GROCERY-12)
    fun setPhoto(
        user: String,
        id: Int,
        fileId: Int?,
    ): GroceryPhotoResult {
        if (repository.item(user, id) == null) return GroceryPhotoResult.NotFound
        return repository.setPhoto(user, id, fileId)?.let { GroceryPhotoResult.Set(changed(it)) } ?: GroceryPhotoResult.NotAPhoto
    }

    // the file of the photo of an item the user sees, when it is that photo
    fun photo(
        user: String,
        id: Int,
        fileId: Int,
    ): StoredFile? = repository.photo(user, id, fileId)

    // the names added to the list before whose name contains the text, the ones added most and last
    // first; the ones to buy on the list are left out. Null when the user does not see the list
    // (GROCERY-11)
    fun suggestions(
        user: String,
        listId: Int,
        text: String,
    ): List<GrocerySuggestion>? = repository.suggestions(user, listId, text.trim(), MAX_SUGGESTIONS)

    // the checked items of the name of the suggestion go for good, the list changes (LIST-4); false
    // when the user does not see the list or it has no such suggestion
    fun forget(
        user: String,
        listId: Int,
        id: Int,
    ): Boolean = repository.forget(user, listId, id).also { if (it) changes.changed(listId) }

    private fun categoryResult(
        listId: Int,
        change: GroceryCategoryChange,
    ): GroceryCategoryResult =
        when (change) {
            is GroceryCategoryChange.Saved -> GroceryCategoryResult.Saved(change.category).also { changes.changed(listId) }
            GroceryCategoryChange.NotFound -> GroceryCategoryResult.NotFound
            GroceryCategoryChange.NameTaken -> GroceryCategoryResult.Exists
            GroceryCategoryChange.TooMany ->
                GroceryCategoryResult.Invalid(listOf(ErrorMessage(GroceryTexts.TOO_MANY_CATEGORIES, "max" to MAX_CATEGORIES)))
        }

    private fun categoryValues(
        name: String,
        icon: String?,
    ): Result<GroceryCategoryValues, List<ErrorMessage>> {
        val normalized = name.trim()
        val emoji = icon?.trim()?.ifEmpty { null }
        val errors =
            listOfNotNull(
                if (normalized.isEmpty() || normalized.length > MAX_CATEGORY_NAME_LENGTH || normalized.any { it.isISOControl() }) {
                    ErrorMessage(GroceryTexts.INVALID_CATEGORY_NAME, "max" to MAX_CATEGORY_NAME_LENGTH)
                } else {
                    null
                },
                emoji?.takeUnless(::isOneEmoji)?.let { ErrorMessage(GroceryTexts.INVALID_ICON) },
            )
        return if (errors.isEmpty()) Result.Ok(GroceryCategoryValues(normalized, emoji)) else Result.Err(errors)
    }

    private fun categoryError(
        categoryId: Int?,
        categories: List<GroceryCategory>,
    ) = if (categoryId != null && categories.none { it.id == categoryId }) ErrorMessage(GroceryTexts.UNKNOWN_CATEGORY) else null

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
            )
        return if (errors.isEmpty()) Result.Ok(GroceryItemValues(name, amount, unit, input.categoryId)) else Result.Err(errors)
    }

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

        const val MAX_CATEGORY_NAME_LENGTH = 32

        // the most categories of a list
        const val MAX_CATEGORIES = 30
    }
}

// the texts of the grocery lists (grocery.backend in i18n/texts.yml)
sealed interface GroceryPhotoResult {
    data class Set(
        val item: GroceryItem,
    ) : GroceryPhotoResult

    // the user sees no such item
    data object NotFound : GroceryPhotoResult

    // the file is no image the user uploaded
    data object NotAPhoto : GroceryPhotoResult
}

object GroceryTexts : Catalog("grocery") {
    val NOT_A_PHOTO = text("not_a_photo")
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
    val INVALID_CATEGORY_NAME = text("invalid_category_name")
    val CATEGORY_EXISTS = text("category_exists")
    val TOO_MANY_CATEGORIES = text("too_many_categories")
    val INVALID_CATEGORY_ORDER = text("invalid_category_order")
    val LIST_EXISTS = text("list_exists")
    val ONLY_OWNER_SHARES = text("only_owner_shares")
    val OWNER_LIST_EXISTS = text("owner_list_exists")
    val LAST_LIST = text("last_list")
}
