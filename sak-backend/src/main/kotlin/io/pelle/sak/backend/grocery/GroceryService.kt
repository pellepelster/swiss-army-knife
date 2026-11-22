package io.pelle.sak.backend.grocery

import io.pelle.sak.backend.lists.invalidIcon
import io.pelle.sak.backend.lists.invalidName
import io.pelle.sak.backend.lists.Lists
import io.pelle.sak.backend.Checked
import io.pelle.sak.backend.orElse
import io.pelle.sak.backend.result
import io.pelle.sak.backend.DeleteResult
import io.pelle.sak.storage.BlobContent
import io.pelle.sak.backend.files.FileService
import io.pelle.sak.api.Page
import io.pelle.sak.core.Catalog
import io.pelle.sak.core.TextKey
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.core.Language
import io.pelle.sak.core.render
import io.pelle.sak.auth.LoginListener
import io.pelle.sak.backend.lists.ListChanges
import io.pelle.sak.backend.lists.ListSaveResult
import io.pelle.sak.backend.lists.ListTexts
import io.pelle.sak.backend.lists.listValues
import io.pelle.sak.backend.lists.result
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.files.StoredFile
import io.pelle.sak.model.grocery.GroceryCategory
import io.pelle.sak.model.grocery.GroceryCategoryWriteResult
import io.pelle.sak.model.grocery.GroceryCategoryReorderResult
import io.pelle.sak.model.grocery.GroceryCategoryValues
import io.pelle.sak.model.grocery.GroceryItem
import io.pelle.sak.model.grocery.GroceryItemValues
import io.pelle.sak.model.grocery.GroceryItemWriteResult
import io.pelle.sak.model.lists.SakList
import io.pelle.sak.model.grocery.GrocerySuggestion
import io.pelle.sak.model.grocery.GroceryRepository
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
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

sealed interface GroceryCategorySaveResult {
    data class Saved(
        val category: GroceryCategory,
    ) : GroceryCategorySaveResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : GroceryCategorySaveResult

    // the user sees no such list, or it has no such category
    data object NotFound : GroceryCategorySaveResult

    // the list has a category of the name
    data object Exists : GroceryCategorySaveResult
}

sealed interface GroceryCategoriesOrderResult {
    data class Ordered(
        val categories: List<GroceryCategory>,
    ) : GroceryCategoriesOrderResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : GroceryCategoriesOrderResult

    // the user sees no such list
    data object NotFound : GroceryCategoriesOrderResult
}

sealed interface GroceryItemSaveResult {
    data class Saved(
        val item: GroceryItem,
    ) : GroceryItemSaveResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : GroceryItemSaveResult

    data object NotFound : GroceryItemSaveResult

    // the list the new item names is none the user sees (API-6)
    data object UnknownList : GroceryItemSaveResult

    // the category the request names is none of the list of the item (API-6)
    data object UnknownCategory : GroceryItemSaveResult
}

// the grocery lists, their items and the suggestions, the only way to them; it checks the values,
// GroceryRepository checks who sees and changes what (MODEL-6)
class GroceryService(
    private val repository: GroceryRepository,
    // the photos of the items are files of the file storage
    private val files: FileService,
    private val changes: ListChanges = ListChanges(),
) : LoginListener {
    constructor(
        database: SakDatabase,
        files: FileService,
        clock: Clock = Clock.systemUTC(),
    ) : this(GroceryRepository(database, clock), files)

    // the lists, as every list-based app has them
    private val lists = Lists(repository, GroceryTexts.LISTS, changes)

    // the ids of the lists the user sees that change from now on, a list deleted included (LIST-4)
    fun changes(user: String): Flow<Int> = lists.changes(user)

    fun lists(user: String): List<SakList> = lists.lists(user)

    // a list the user sees, null when there is none
    fun list(
        user: String,
        id: Int,
    ): SakList? = lists.list(user, id)

    // an item of a list the user sees, null when there is none
    fun item(
        user: String,
        id: Int,
    ) = repository.item(user, id)

    // the icon is one emoji, empty or none for no icon; the list starts with the default categories
    // in the language (GROCERY-13)
    // the first list of a user who never had one, with the categories a new list starts with, in the
    // language of the sign-in (LIST-1, GROCERY-13)
    override fun signedIn(
        owner: String,
        language: Language,
    ) {
        repository.insertFirstList(owner, GroceryTexts.FIRST_LIST.render(language), DefaultGroceryCategory.of(language))
    }

    fun createList(
        user: String,
        name: String,
        shared: Boolean,
        icon: String? = null,
        language: Language = Language.FALLBACK,
    ): ListSaveResult = lists.create(name, icon) { repository.insertList(user, it.name, shared, it.icon, DefaultGroceryCategory.of(language)) }

    fun updateList(
        user: String,
        id: Int,
        name: String,
        shared: Boolean,
        icon: String? = null,
    ): ListSaveResult = lists.update(user, id, name, shared, icon)

    // with its items, the last list of the user's own too (LIST-1)
    fun deleteList(
        user: String,
        id: Int,
    ): DeleteResult = lists.delete(user, id)

    // the items to buy of a list the user sees, with checked also the latest bought item of each name
    // not to buy (GROCERY-14); null for another list. By the order of their category (GROCERY-13),
    // those without one last; in a category the ones to buy first as added, then the bought ones,
    // the last bought first
    fun items(
        user: String,
        listId: Int,
        checked: Boolean = false,
    ): List<GroceryItem>? = repository.items(user, listId, checked)

    // on a list the user sees (LIST-1); it takes the photo and, without one given, the category of
    // the latest item of its name (GROCERY-11). Its category is one of the list (GROCERY-13)
    fun addItem(
        user: String,
        listId: Int,
        input: GroceryItemValues,
    ): GroceryItemSaveResult {
        val values = itemValues(input).orElse { return GroceryItemSaveResult.Invalid(it) }
        return when (val result = repository.insertItem(user, listId, values)) {
            is GroceryItemWriteResult.Saved -> GroceryItemSaveResult.Saved(changed(result.item))
            GroceryItemWriteResult.NotFound -> GroceryItemSaveResult.UnknownList
            GroceryItemWriteResult.UnknownCategory -> GroceryItemSaveResult.UnknownCategory
        }
    }

    fun updateItem(
        user: String,
        id: Int,
        input: GroceryItemValues,
    ): GroceryItemSaveResult {
        val values = itemValues(input).orElse { return GroceryItemSaveResult.Invalid(it) }
        return saved(repository.updateItem(user, id, values))
    }

    // the category of an item on a list the user sees: one of its list, or none; the next item of its
    // name on the list takes it (GROCERY-13)
    fun setCategory(
        user: String,
        id: Int,
        categoryId: Int?,
    ): GroceryItemSaveResult = saved(repository.setCategory(user, id, categoryId))

    // of a change of an item
    private fun saved(result: GroceryItemWriteResult): GroceryItemSaveResult =
        when (result) {
            is GroceryItemWriteResult.Saved -> GroceryItemSaveResult.Saved(changed(result.item))
            GroceryItemWriteResult.NotFound -> GroceryItemSaveResult.NotFound
            GroceryItemWriteResult.UnknownCategory -> GroceryItemSaveResult.UnknownCategory
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
    ): GroceryCategorySaveResult {
        val values = categoryValues(name, icon).orElse { return GroceryCategorySaveResult.Invalid(it) }
        return categoryResult(listId, repository.insertCategory(user, listId, values, MAX_CATEGORIES))
    }

    fun updateCategory(
        user: String,
        listId: Int,
        id: Int,
        name: String,
        icon: String?,
    ): GroceryCategorySaveResult {
        val values = categoryValues(name, icon).orElse { return GroceryCategorySaveResult.Invalid(it) }
        return categoryResult(listId, repository.updateCategory(user, listId, id, values))
    }

    // its items and the suggestions of the list lose it; false when the user sees no such list or it
    // has no such category
    fun deleteCategory(
        user: String,
        listId: Int,
        id: Int,
    ): DeleteResult = DeleteResult.of(repository.deleteCategory(user, listId, id).also { if (it) changes.changed(listId) })

    // the ids are all categories of a list the user sees, each once, in their new order
    fun orderCategories(
        user: String,
        listId: Int,
        ids: List<Int>,
    ): GroceryCategoriesOrderResult =
        when (val order = repository.orderCategories(user, listId, ids)) {
            is GroceryCategoryReorderResult.Ordered -> GroceryCategoriesOrderResult.Ordered(order.categories).also { changes.changed(listId) }
            GroceryCategoryReorderResult.NotFound -> GroceryCategoriesOrderResult.NotFound
            GroceryCategoryReorderResult.Mismatch -> GroceryCategoriesOrderResult.Invalid(listOf(ErrorMessage(GroceryTexts.INVALID_CATEGORY_ORDER)))
        }

    // bought (GROCERY-3), by any user who sees its list (LIST-2): it leaves the list and stays as its
    // history (GROCERY-11); false when the user sees no such item to buy
    fun checkItem(
        user: String,
        id: Int,
    ): Boolean = repository.checkItem(user, id)?.let(::changed) != null

    // to buy or bought, gone for good, by any user who sees its list
    fun deleteItem(
        user: String,
        id: Int,
    ): DeleteResult = DeleteResult.of(repository.deleteItem(user, id)?.let(::changed) != null)

    // of an item to buy or bought on a list the user sees: an image the user uploaded, or none to
    // take it away; the next item of its name on the list takes it (GROCERY-12)
    fun setPhoto(
        user: String,
        id: Int,
        fileId: Int?,
    ): GroceryPhotoSetResult {
        if (fileId != null && files.byId(user, fileId)?.contentType?.startsWith("image/") != true) {
            // an item the user does not see is not found, whatever the file is
            return if (repository.item(user, id) == null) GroceryPhotoSetResult.NotFound else GroceryPhotoSetResult.NotAPhoto
        }
        return repository.setPhoto(user, id, fileId)?.let { GroceryPhotoSetResult.Set(changed(it)) } ?: GroceryPhotoSetResult.NotFound
    }

    // the photo of an item the user sees, when the file is that photo: scaled to the smallest of the
    // sizes of the file storage that is at least `size` pixels, a jpeg; larger, or without a size, the
    // photo itself. Null when there is no such photo
    fun photoContent(
        user: String,
        id: Int,
        fileId: Int,
        size: Int?,
    ): GroceryPhotoContent? {
        val file = repository.photo(user, id, fileId) ?: return null
        return when (val scaled = size?.let(FileService::scaledSize)) {
            null -> files.content(file)?.let { GroceryPhotoContent(it, file.contentType) }
            else -> files.thumbnail(file, scaled)?.let { GroceryPhotoContent(it, JPEG) }
        }
    }

    // a page of the names added to the list before whose name contains the text, the ones added most
    // and last first; the ones to buy on the list are left out. Null when the user does not see the
    // list (GROCERY-11)
    fun suggestions(
        user: String,
        listId: Int,
        text: String,
        offset: Long = 0,
        limit: Int = Int.MAX_VALUE,
    ): Page<GrocerySuggestion>? {
        val total = repository.suggestionCount(user, listId, text.trim()) ?: return null
        return Page(repository.suggestions(user, listId, text.trim(), offset, limit).orEmpty(), total)
    }

    // the checked items of the name of the suggestion go for good, the list changes (LIST-4); false
    // when the user does not see the list or it has no such suggestion
    fun forget(
        user: String,
        listId: Int,
        id: Int,
    ): DeleteResult = DeleteResult.of(repository.forget(user, listId, id).also { if (it) changes.changed(listId) })

    private fun categoryResult(
        listId: Int,
        change: GroceryCategoryWriteResult,
    ): GroceryCategorySaveResult =
        when (change) {
            is GroceryCategoryWriteResult.Saved -> GroceryCategorySaveResult.Saved(change.category).also { changes.changed(listId) }
            GroceryCategoryWriteResult.NotFound -> GroceryCategorySaveResult.NotFound
            GroceryCategoryWriteResult.NameTaken -> GroceryCategorySaveResult.Exists
            GroceryCategoryWriteResult.TooMany ->
                GroceryCategorySaveResult.Invalid(listOf(ErrorMessage(GroceryTexts.TOO_MANY_CATEGORIES, "max" to MAX_CATEGORIES)))
        }

    private fun categoryValues(
        name: String,
        icon: String?,
    ): Checked<GroceryCategoryValues> {
        val normalized = name.trim()
        val emoji = icon?.trim()?.ifEmpty { null }
        val errors =
            listOfNotNull(
                if (invalidName(normalized, MAX_CATEGORY_NAME_LENGTH)) {
                    ErrorMessage(GroceryTexts.INVALID_CATEGORY_NAME, "max" to MAX_CATEGORY_NAME_LENGTH)
                } else {
                    null
                },
                ErrorMessage(GroceryTexts.INVALID_ICON).takeIf { invalidIcon(emoji) },
            )
        return if (errors.isEmpty()) Checked.Valid(GroceryCategoryValues(normalized, emoji)) else Checked.Invalid(errors)
    }

    private fun changed(item: GroceryItem) = item.also { changes.changed(it.listId) }

    private fun itemValues(input: GroceryItemValues): Checked<GroceryItemValues> {
        val name = input.name.trim()
        val unit = input.unit?.trim()?.ifEmpty { null }
        val amount = input.amount?.stripTrailingZeros()
        val errors =
            listOfNotNull(
                if (invalidName(name, MAX_ITEM_NAME_LENGTH)) {
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
        return if (errors.isEmpty()) Checked.Valid(GroceryItemValues(name, amount, unit, input.categoryId)) else Checked.Invalid(errors)
    }

    // the values of an item or why they are not valid
    companion object {
        const val MAX_ITEM_NAME_LENGTH = 128

        const val MAX_UNIT_LENGTH = 16

        val MAX_AMOUNT = BigDecimal("9999999.999")


        const val MAX_CATEGORY_NAME_LENGTH = 32

        const val MAX_CATEGORIES = 30
    }
}

// the photo of an item to serve: its bytes and their type
class GroceryPhotoContent(
    val content: BlobContent,
    val contentType: String,
)

private const val JPEG = "image/jpeg"

sealed interface GroceryPhotoSetResult {
    data class Set(
        val item: GroceryItem,
    ) : GroceryPhotoSetResult

    // the user sees no such item
    data object NotFound : GroceryPhotoSetResult

    // the file the request names is no image the user uploaded (API-6)
    data object NotAPhoto : GroceryPhotoSetResult
}

// the texts of the grocery lists (grocery.backend in i18n/texts.yml)
object GroceryTexts : Catalog("grocery") {
    val NOT_A_PHOTO = text("not_a_photo")
    val FIRST_LIST = text("first_list")
    val TOO_MANY_DISCOVERIES = text("too_many_discoveries")
    val DISCOVERY_FAILED = text("discovery_failed")
    val AI_LABEL = text("settings.ai.label")
    val AI_DESCRIPTION = text("settings.ai.description")
    val SCOPE_LABEL = text("settings.label")
    val SCOPE_DESCRIPTION = text("settings.description")
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
    val UNKNOWN_LIST = text("unknown_list")
    val ONLY_OWNER_SHARES = text("only_owner_shares")
    val LISTS = ListTexts(INVALID_ICON, INVALID_LIST_NAME, LIST_EXISTS, ONLY_OWNER_SHARES)
}
