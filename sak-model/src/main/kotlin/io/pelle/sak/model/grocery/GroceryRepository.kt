package io.pelle.sak.model.grocery

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.files.StoredFile
import io.pelle.sak.model.files.toFile
import io.pelle.sak.model.jooq.tables.records.GroceryCategoriesRecord
import io.pelle.sak.model.jooq.tables.records.GroceryItemsRecord
import io.pelle.sak.model.jooq.tables.records.GroceryListsRecord
import io.pelle.sak.model.jooq.tables.records.GroceryProductsRecord
import io.pelle.sak.model.jooq.tables.references.FILES
import io.pelle.sak.model.jooq.tables.references.GROCERY_CATEGORIES
import io.pelle.sak.model.jooq.tables.references.GROCERY_ITEMS
import io.pelle.sak.model.jooq.tables.references.GROCERY_LISTS
import io.pelle.sak.model.jooq.tables.references.GROCERY_PRODUCTS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

// how many items are to buy on the list of the row (GROCERY_LISTS)
private val ITEMS_TO_BUY =
    DSL
        .field(
            DSL
                .selectCount()
                .from(GROCERY_ITEMS)
                .where(GROCERY_ITEMS.LIST_ID.eq(GROCERY_LISTS.ID), GROCERY_ITEMS.DELETED_AT.isNull),
        ).`as`("items_to_buy")

// the list a user gets when they sign in without a list of their own (LIST-1), a list like the others
const val DEFAULT_GROCERY_LIST = "Default"

data class GroceryList(
    val id: Int,
    val owner: String,
    val name: String,
    // of all users (LIST-2)
    val shared: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant?,
    // how many items are to buy on it
    val items: Int,
    // one emoji, none shows the first letter of the name
    val icon: String? = null,
)

// a category of a list, the groups of its items in the order of position (GROCERY-13)
data class GroceryCategory(
    val id: Int,
    val listId: Int,
    val name: String,
    // one emoji, null for none
    val icon: String?,
    val position: Int,
)

// the name and the icon of a category
data class GroceryCategoryValues(
    val name: String,
    // one emoji, null for none
    val icon: String?,
)

// what changing the categories of a list came to
sealed interface GroceryCategoryChange {
    data class Saved(
        val category: GroceryCategory,
    ) : GroceryCategoryChange

    // the user owns no such list, or it has no such category
    data object NotFound : GroceryCategoryChange

    // the list has another category of the name
    data object NameTaken : GroceryCategoryChange

    // the list has as many categories as it may have
    data object TooMany : GroceryCategoryChange
}

// what ordering the categories of a list came to
sealed interface GroceryCategoryOrder {
    data class Ordered(
        val categories: List<GroceryCategory>,
    ) : GroceryCategoryOrder

    // the user owns no such list
    data object NotFound : GroceryCategoryOrder

    // the ids are not the ones of the categories of the list, each once
    data object Mismatch : GroceryCategoryOrder
}

// what is to buy, how much of it and in which category; a bought item is deleted (GROCERY-3)
data class GroceryItem(
    val id: Int,
    val listId: Int,
    val owner: String,
    val name: String,
    val amount: BigDecimal?,
    val unit: String?,
    // a category of its list, null for none (GROCERY-13)
    val categoryId: Int?,
    val createdAt: Instant,
    val updatedAt: Instant?,
    // the file of its photo, null without one
    val photoFileId: Int? = null,
)

// an item added to a list before, what the suggestions on that list are made of (GROCERY-11)
data class GroceryProduct(
    val id: Int,
    // the list that learned it, the only one it is suggested on
    val listId: Int,
    // the user who added the item of its name first
    val owner: String,
    val name: String,
    val amount: BigDecimal?,
    val unit: String?,
    // a category of its list, null for none (GROCERY-13)
    val categoryId: Int?,
    val timesUsed: Int,
    val lastUsedAt: Instant,
)

// what the user gives of an item
data class GroceryItemValues(
    val name: String,
    val amount: BigDecimal?,
    val unit: String?,
    // a category of the item's list; another one or a deleted one is none (GROCERY-13)
    val categoryId: Int?,
)

// what deleting a list came to
sealed interface GroceryListDelete {
    data object Deleted : GroceryListDelete

    // the user has no such list of their own
    data object NotFound : GroceryListDelete

    // the only list of the user's own, it stays (LIST-1)
    data object LastList : GroceryListDelete
}

// what changing a list came to
sealed interface GroceryListUpdate {
    data class Updated(
        val list: GroceryList,
    ) : GroceryListUpdate

    // the user has no such list of their own
    data object NotFound : GroceryListUpdate

    // the owner has another list of the name
    data object NameTaken : GroceryListUpdate
}

// the database access of the grocery app: its lists, their items and the products learned from
// them; only GroceryService (sak-backend) uses it. It reads the rows that are not deleted only, and
// checks who sees and changes them in the sql of each method (MODEL-6): a user sees and changes
// their own lists and the shared ones of the others with their items (LIST-2), only the owner
// changes or deletes a list and its categories (GROCERY-1, GROCERY-13)
class GroceryRepository(
    private val database: SakDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    // the lists of the user and the shared ones of the others, by name
    fun lists(user: String): List<GroceryList> =
        database.dsl
            .select(GROCERY_LISTS.asterisk(), ITEMS_TO_BUY)
            .from(GROCERY_LISTS)
            .where(visibleList(user))
            .orderBy(GROCERY_LISTS.NAME, GROCERY_LISTS.ID)
            .fetch { it.into(GROCERY_LISTS).toList(it.get(ITEMS_TO_BUY) ?: 0) }

    // a private list of the name with the categories for the owner when they have no list of their
    // own (LIST-1); a list made at the same time, e.g. by another sign-in, is the one (the name is
    // unique among the lists of the owner)
    fun ensureList(
        owner: String,
        name: String,
        categories: List<GroceryCategoryValues> = emptyList(),
    ) {
        database.dsl.transaction { configuration ->
            val dsl = DSL.using(configuration)
            if (dsl.fetchExists(GROCERY_LISTS, ownList(owner))) return@transaction
            val now = clock.instant()
            val list =
                dsl
                    .insertInto(GROCERY_LISTS)
                    .set(GROCERY_LISTS.OWNER, owner)
                    .set(GROCERY_LISTS.NAME, name)
                    .set(GROCERY_LISTS.SHARED, false)
                    .set(GROCERY_LISTS.CREATED_AT, now)
                    .onConflictDoNothing()
                    .returning(GROCERY_LISTS.ID)
                    .fetchOne() ?: return@transaction
            insertCategories(dsl, owner, list.id!!, categories, now)
        }
    }

    // null when the user does not see the list
    fun list(
        user: String,
        id: Int,
    ): GroceryList? =
        database.dsl
            .select(GROCERY_LISTS.asterisk(), ITEMS_TO_BUY)
            .from(GROCERY_LISTS)
            .where(GROCERY_LISTS.ID.eq(id), visibleList(user))
            .fetchOne { it.into(GROCERY_LISTS).toList(it.get(ITEMS_TO_BUY) ?: 0) }

    // whether the user sees the list or saw it until it was deleted: whom a change of it reaches
    // (LIST-4)
    fun seesList(
        user: String,
        id: Int,
    ): Boolean =
        database.dsl.fetchExists(
            GROCERY_LISTS,
            GROCERY_LISTS.ID.eq(id),
            GROCERY_LISTS.OWNER.eq(user).or(GROCERY_LISTS.SHARED.isTrue),
        )

    // the list with the categories in their order; null when the owner has a list of the name
    fun insertList(
        owner: String,
        name: String,
        shared: Boolean,
        icon: String? = null,
        categories: List<GroceryCategoryValues> = emptyList(),
    ): GroceryList? =
        try {
            database.dsl.transactionResult { configuration ->
                val dsl = DSL.using(configuration)
                val now = clock.instant()
                dsl
                    .insertInto(GROCERY_LISTS)
                    .set(GROCERY_LISTS.OWNER, owner)
                    .set(GROCERY_LISTS.NAME, name)
                    .set(GROCERY_LISTS.SHARED, shared)
                    .set(GROCERY_LISTS.ICON, icon)
                    .set(GROCERY_LISTS.CREATED_AT, now)
                    .returning()
                    .fetchOne()!!
                    .also { insertCategories(dsl, owner, it.id!!, categories, now) }
                    .toList(items = 0)
            }
        } catch (_: IntegrityConstraintViolationException) {
            null
        }

    // a list of the user's own
    fun updateList(
        user: String,
        id: Int,
        name: String,
        shared: Boolean,
        icon: String? = null,
    ): GroceryListUpdate =
        try {
            database.dsl
                .update(GROCERY_LISTS)
                .set(GROCERY_LISTS.NAME, name)
                .set(GROCERY_LISTS.SHARED, shared)
                .set(GROCERY_LISTS.ICON, icon)
                .set(GROCERY_LISTS.UPDATED_AT, clock.instant())
                .where(GROCERY_LISTS.ID.eq(id), ownList(user))
                .returning()
                .fetchOne()
                ?.let { GroceryListUpdate.Updated(it.toList(itemsToBuy(it.id!!))) }
                ?: GroceryListUpdate.NotFound
        } catch (_: IntegrityConstraintViolationException) {
            GroceryListUpdate.NameTaken
        }

    // a list of the user's own, not their last one (LIST-1); it and its items stay as deleted. The
    // check of the other lists is part of the update, two lists deleted at once leave one
    fun deleteList(
        user: String,
        id: Int,
    ): GroceryListDelete =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val now = clock.instant()
            val other = GROCERY_LISTS.`as`("other")
            val anotherOwn =
                DSL.exists(
                    DSL
                        .selectOne()
                        .from(other)
                        .where(other.OWNER.eq(user), other.DELETED_AT.isNull, other.ID.ne(id)),
                )
            val deleted =
                dsl
                    .update(GROCERY_LISTS)
                    .set(GROCERY_LISTS.DELETED_AT, now)
                    .where(GROCERY_LISTS.ID.eq(id), ownList(user), anotherOwn)
                    .execute() > 0
            when {
                deleted -> {
                    dsl
                        .update(GROCERY_ITEMS)
                        .set(GROCERY_ITEMS.DELETED_AT, now)
                        .where(GROCERY_ITEMS.LIST_ID.eq(id), GROCERY_ITEMS.DELETED_AT.isNull)
                        .execute()
                    // what the list learned and its categories go with it (GROCERY-11, GROCERY-13)
                    dsl
                        .update(GROCERY_PRODUCTS)
                        .set(GROCERY_PRODUCTS.DELETED_AT, now)
                        .where(GROCERY_PRODUCTS.LIST_ID.eq(id), GROCERY_PRODUCTS.DELETED_AT.isNull)
                        .execute()
                    dsl
                        .update(GROCERY_CATEGORIES)
                        .set(GROCERY_CATEGORIES.DELETED_AT, now)
                        .where(GROCERY_CATEGORIES.LIST_ID.eq(id), GROCERY_CATEGORIES.DELETED_AT.isNull)
                        .execute()
                    GroceryListDelete.Deleted
                }
                dsl.fetchExists(GROCERY_LISTS, GROCERY_LISTS.ID.eq(id), ownList(user)) -> GroceryListDelete.LastList
                else -> GroceryListDelete.NotFound
            }
        }

    // the items of the list in the order they were added, null when the user does not see the list
    fun items(
        user: String,
        listId: Int,
    ): List<GroceryItem>? =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            if (!dsl.fetchExists(GROCERY_LISTS, GROCERY_LISTS.ID.eq(listId), visibleList(user))) return@transactionResult null
            dsl
                .selectFrom(GROCERY_ITEMS)
                .where(GROCERY_ITEMS.LIST_ID.eq(listId), GROCERY_ITEMS.DELETED_AT.isNull)
                .orderBy(GROCERY_ITEMS.ID)
                .fetch()
                .map { it.toItem() }
        }

    // null when the user does not see its list
    fun item(
        user: String,
        id: Int,
    ): GroceryItem? =
        database.dsl
            .selectFrom(GROCERY_ITEMS)
            .where(GROCERY_ITEMS.ID.eq(id), visibleItem(user))
            .fetchOne()
            ?.toItem()

    // the item on the list, null when the user does not see the list; the list's product of its name
    // learns it, in the same transaction, and gives it its photo and, without one given, its category
    // (GROCERY-11). The user is its owner (MODEL-5)
    fun insertItem(
        user: String,
        listId: Int,
        values: GroceryItemValues,
    ): GroceryItem? =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            if (!dsl.fetchExists(GROCERY_LISTS, GROCERY_LISTS.ID.eq(listId), visibleList(user))) return@transactionResult null
            val now = clock.instant()
            val key = groceryNameKey(values.name)
            // the photo and, without one given, the category the list learned of the name (GROCERY-12, GROCERY-13)
            val learned =
                dsl
                    .select(GROCERY_PRODUCTS.PHOTO_FILE_ID, GROCERY_PRODUCTS.CATEGORY_ID)
                    .from(GROCERY_PRODUCTS)
                    .where(listProduct(listId, key))
                    .fetchOne()
            val photo = learned?.value1()
            val item =
                dsl
                    .insertInto(GROCERY_ITEMS)
                    .set(GROCERY_ITEMS.LIST_ID, listId)
                    .set(GROCERY_ITEMS.OWNER, user)
                    .set(GROCERY_ITEMS.NAME, values.name)
                    .set(GROCERY_ITEMS.NAME_KEY, key)
                    .set(GROCERY_ITEMS.AMOUNT, values.amount)
                    .set(GROCERY_ITEMS.UNIT, values.unit)
                    .set(GROCERY_ITEMS.CATEGORY_ID, categoryOf(dsl, listId, values.categoryId ?: learned?.value2()))
                    .set(GROCERY_ITEMS.CREATED_AT, now)
                    .set(GROCERY_ITEMS.PHOTO_FILE_ID, photo)
                    .returning()
                    .fetchOne()!!
            learn(dsl, user, listId, values.copy(categoryId = item.categoryId), now)
            item.toItem()
        }

    // null when the user does not see its list
    fun updateItem(
        user: String,
        id: Int,
        values: GroceryItemValues,
    ): GroceryItem? =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val current = dsl.selectFrom(GROCERY_ITEMS).where(GROCERY_ITEMS.ID.eq(id), visibleItem(user)).fetchOne() ?: return@transactionResult null
            val now = clock.instant()
            val item =
                dsl
                    .update(GROCERY_ITEMS)
                    .set(GROCERY_ITEMS.NAME, values.name)
                    .set(GROCERY_ITEMS.NAME_KEY, groceryNameKey(values.name))
                    .set(GROCERY_ITEMS.AMOUNT, values.amount)
                    .set(GROCERY_ITEMS.UNIT, values.unit)
                    .set(GROCERY_ITEMS.CATEGORY_ID, categoryOf(dsl, current.listId!!, values.categoryId))
                    .set(GROCERY_ITEMS.UPDATED_AT, now)
                    .where(GROCERY_ITEMS.ID.eq(id))
                    .returning()
                    .fetchOne()!!
            // a category changed goes onto the list's product of the name too (GROCERY-13)
            if (item.categoryId != current.categoryId) teachCategory(dsl, item, now)
            item.toItem()
        }

    // the category of an item on a list the user sees, one of its list or none; the list's product
    // of its name takes it too, so the next item of the name on the list has it (GROCERY-13). Null
    // when the user does not see the item or the category is none of the live ones of its list
    fun setCategory(
        user: String,
        id: Int,
        categoryId: Int?,
    ): GroceryItem? =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val current = dsl.selectFrom(GROCERY_ITEMS).where(GROCERY_ITEMS.ID.eq(id), visibleItem(user)).fetchOne() ?: return@transactionResult null
            if (categoryId != null && categoryOf(dsl, current.listId!!, categoryId) == null) return@transactionResult null
            val now = clock.instant()
            val item =
                dsl
                    .update(GROCERY_ITEMS)
                    .set(GROCERY_ITEMS.CATEGORY_ID, categoryId)
                    .set(GROCERY_ITEMS.UPDATED_AT, now)
                    .where(GROCERY_ITEMS.ID.eq(id))
                    .returning()
                    .fetchOne()!!
            teachCategory(dsl, item, now)
            item.toItem()
        }

    // the live categories of a list the user sees, in their order; null when the user does not see it
    fun categories(
        user: String,
        listId: Int,
    ): List<GroceryCategory>? {
        if (!database.dsl.fetchExists(GROCERY_LISTS, GROCERY_LISTS.ID.eq(listId), visibleList(user))) return null
        return database.dsl
            .selectFrom(GROCERY_CATEGORIES)
            .where(GROCERY_CATEGORIES.LIST_ID.eq(listId), GROCERY_CATEGORIES.DELETED_AT.isNull)
            .orderBy(GROCERY_CATEGORIES.POSITION, GROCERY_CATEGORIES.ID)
            .fetch { it.toCategory() }
    }

    // a category of a list of the user's own, the last one; at most max of them
    fun insertCategory(
        user: String,
        listId: Int,
        values: GroceryCategoryValues,
        max: Int,
    ): GroceryCategoryChange =
        try {
            database.dsl.transactionResult { configuration ->
                val dsl = DSL.using(configuration)
                if (!dsl.fetchExists(GROCERY_LISTS, GROCERY_LISTS.ID.eq(listId), ownList(user))) {
                    return@transactionResult GroceryCategoryChange.NotFound
                }
                // one statement: the count and the place are checked where the row goes in, two
                // categories added at the same time can not both pass the limit
                val live = GROCERY_CATEGORIES.LIST_ID.eq(listId).and(GROCERY_CATEGORIES.DELETED_AT.isNull)
                val position = DSL.coalesce(DSL.field(DSL.select(DSL.max(GROCERY_CATEGORIES.POSITION).plus(1)).from(GROCERY_CATEGORIES).where(live)), DSL.inline(0))
                val key = groceryNameKey(values.name)
                val inserted =
                    dsl
                        .insertInto(
                            GROCERY_CATEGORIES,
                            GROCERY_CATEGORIES.LIST_ID,
                            GROCERY_CATEGORIES.OWNER,
                            GROCERY_CATEGORIES.NAME,
                            GROCERY_CATEGORIES.NAME_KEY,
                            GROCERY_CATEGORIES.ICON,
                            GROCERY_CATEGORIES.POSITION,
                            GROCERY_CATEGORIES.CREATED_AT,
                        ).select(
                            DSL
                                .select(
                                    DSL.`val`(listId, GROCERY_CATEGORIES.LIST_ID),
                                    DSL.`val`(user, GROCERY_CATEGORIES.OWNER),
                                    DSL.`val`(values.name, GROCERY_CATEGORIES.NAME),
                                    DSL.`val`(key, GROCERY_CATEGORIES.NAME_KEY),
                                    DSL.`val`(values.icon, GROCERY_CATEGORIES.ICON),
                                    position,
                                    DSL.`val`(clock.instant(), GROCERY_CATEGORIES.CREATED_AT),
                                ).where(DSL.field(DSL.selectCount().from(GROCERY_CATEGORIES).where(live)).lt(max)),
                        ).execute()
                if (inserted == 0) return@transactionResult GroceryCategoryChange.TooMany
                // the live name is unique on the list
                GroceryCategoryChange.Saved(dsl.selectFrom(GROCERY_CATEGORIES).where(live, GROCERY_CATEGORIES.NAME_KEY.eq(key)).fetchOne()!!.toCategory())
            }
        } catch (_: IntegrityConstraintViolationException) {
            GroceryCategoryChange.NameTaken
        }

    // the name and the icon of a category of a list of the user's own; TooMany never
    fun updateCategory(
        user: String,
        listId: Int,
        id: Int,
        values: GroceryCategoryValues,
    ): GroceryCategoryChange =
        try {
            database.dsl
                .update(GROCERY_CATEGORIES)
                .set(GROCERY_CATEGORIES.NAME, values.name)
                .set(GROCERY_CATEGORIES.NAME_KEY, groceryNameKey(values.name))
                .set(GROCERY_CATEGORIES.ICON, values.icon)
                .set(GROCERY_CATEGORIES.UPDATED_AT, clock.instant())
                .where(ownCategory(user, listId, id))
                .returning()
                .fetchOne()
                ?.let { GroceryCategoryChange.Saved(it.toCategory()) } ?: GroceryCategoryChange.NotFound
        } catch (_: IntegrityConstraintViolationException) {
            GroceryCategoryChange.NameTaken
        }

    // a category of a list of the user's own; its items and the products of the list lose it. It
    // stays as deleted. False when the user owns no such list or it has no such category
    fun deleteCategory(
        user: String,
        listId: Int,
        id: Int,
    ): Boolean =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val now = clock.instant()
            val deleted =
                dsl
                    .update(GROCERY_CATEGORIES)
                    .set(GROCERY_CATEGORIES.DELETED_AT, now)
                    .where(ownCategory(user, listId, id))
                    .execute() > 0
            if (deleted) {
                dsl
                    .update(GROCERY_ITEMS)
                    .setNull(GROCERY_ITEMS.CATEGORY_ID)
                    .set(GROCERY_ITEMS.UPDATED_AT, now)
                    .where(GROCERY_ITEMS.CATEGORY_ID.eq(id))
                    .execute()
                dsl
                    .update(GROCERY_PRODUCTS)
                    .setNull(GROCERY_PRODUCTS.CATEGORY_ID)
                    .set(GROCERY_PRODUCTS.UPDATED_AT, now)
                    .where(GROCERY_PRODUCTS.CATEGORY_ID.eq(id))
                    .execute()
            }
            deleted
        }

    // the categories of a list of the user's own in the order of the ids, which are all of its live
    // categories, each once
    fun orderCategories(
        user: String,
        listId: Int,
        ids: List<Int>,
    ): GroceryCategoryOrder =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            if (!dsl.fetchExists(GROCERY_LISTS, GROCERY_LISTS.ID.eq(listId), ownList(user))) return@transactionResult GroceryCategoryOrder.NotFound
            val live =
                dsl
                    .select(GROCERY_CATEGORIES.ID)
                    .from(GROCERY_CATEGORIES)
                    .where(GROCERY_CATEGORIES.LIST_ID.eq(listId), GROCERY_CATEGORIES.DELETED_AT.isNull)
                    .fetch { it.value1()!! }
            if (ids.size != live.size || ids.toSet() != live.toSet()) return@transactionResult GroceryCategoryOrder.Mismatch
            val now = clock.instant()
            ids.forEachIndexed { position, id ->
                dsl
                    .update(GROCERY_CATEGORIES)
                    .set(GROCERY_CATEGORIES.POSITION, position)
                    .set(GROCERY_CATEGORIES.UPDATED_AT, now)
                    .where(GROCERY_CATEGORIES.ID.eq(id), GROCERY_CATEGORIES.POSITION.ne(position))
                    .execute()
            }
            GroceryCategoryOrder.Ordered(
                dsl
                    .selectFrom(GROCERY_CATEGORIES)
                    .where(GROCERY_CATEGORIES.LIST_ID.eq(listId), GROCERY_CATEGORIES.DELETED_AT.isNull)
                    .orderBy(GROCERY_CATEGORIES.POSITION, GROCERY_CATEGORIES.ID)
                    .fetch { it.toCategory() },
            )
        }

    // the photo of an item on a list the user sees, a file the user uploaded that is an image, or none;
    // the list's product of its name takes it too, so it comes back on the next item of the name on
    // that list (GROCERY-12). Null when the user does not see the item or the file is no image of
    // theirs; the file stays when the photo goes
    fun setPhoto(
        user: String,
        id: Int,
        fileId: Int?,
    ): GroceryItem? =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            if (fileId != null &&
                !dsl.fetchExists(
                    FILES,
                    FILES.ID.eq(fileId),
                    FILES.OWNER.eq(user),
                    FILES.DELETED_AT.isNull,
                    FILES.CONTENT_TYPE.startsWith("image/"),
                )
            ) {
                return@transactionResult null
            }
            val now = clock.instant()
            val item =
                dsl
                    .update(GROCERY_ITEMS)
                    .set(GROCERY_ITEMS.PHOTO_FILE_ID, fileId)
                    .set(GROCERY_ITEMS.UPDATED_AT, now)
                    .where(GROCERY_ITEMS.ID.eq(id), visibleItem(user))
                    .returning()
                    .fetchOne() ?: return@transactionResult null
            dsl
                .update(GROCERY_PRODUCTS)
                .set(GROCERY_PRODUCTS.PHOTO_FILE_ID, fileId)
                .set(GROCERY_PRODUCTS.UPDATED_AT, now)
                .where(listProduct(item.listId!!, item.nameKey!!))
                .execute()
            item.toItem()
        }

    // the file of the photo of an item on a list the user sees, when it is that photo; whoever
    // uploaded it, a shared list shows it to every user (LIST-2)
    fun photo(
        user: String,
        id: Int,
        fileId: Int,
    ): StoredFile? =
        database.dsl
            .select(FILES.asterisk())
            .from(GROCERY_ITEMS)
            .join(FILES)
            .on(FILES.ID.eq(GROCERY_ITEMS.PHOTO_FILE_ID))
            .where(GROCERY_ITEMS.ID.eq(id), GROCERY_ITEMS.PHOTO_FILE_ID.eq(fileId), visibleItem(user), FILES.DELETED_AT.isNull)
            .fetchOne()
            ?.into(FILES)
            ?.toFile()

    // bought (GROCERY-3); false when the user does not see its list. It stays as deleted
    fun deleteItem(
        user: String,
        id: Int,
    ): Boolean =
        database.dsl
            .update(GROCERY_ITEMS)
            .set(GROCERY_ITEMS.DELETED_AT, clock.instant())
            .where(GROCERY_ITEMS.ID.eq(id), visibleItem(user))
            .execute() > 0

    // the products the list learned whose name contains the text, both normalized (groceryNameKey),
    // the ones bought most and last first; the ones to buy on the list are left out. Null when the
    // user does not see the list (GROCERY-11)
    fun products(
        user: String,
        listId: Int,
        text: String,
        limit: Int,
    ): List<GroceryProduct>? {
        if (!database.dsl.fetchExists(GROCERY_LISTS, GROCERY_LISTS.ID.eq(listId), visibleList(user))) return null
        val toBuy =
            DSL
                .select(GROCERY_ITEMS.NAME_KEY)
                .from(GROCERY_ITEMS)
                .where(GROCERY_ITEMS.LIST_ID.eq(listId), GROCERY_ITEMS.DELETED_AT.isNull)
        return database.dsl
            .selectFrom(GROCERY_PRODUCTS)
            .where(
                GROCERY_PRODUCTS.LIST_ID.eq(listId),
                GROCERY_PRODUCTS.DELETED_AT.isNull,
                GROCERY_PRODUCTS.NAME_KEY.contains(groceryNameKey(text)),
                GROCERY_PRODUCTS.NAME_KEY.notIn(toBuy),
            ).orderBy(GROCERY_PRODUCTS.TIMES_USED.desc(), GROCERY_PRODUCTS.LAST_USED_AT.desc(), GROCERY_PRODUCTS.ID)
            .limit(limit)
            .fetch()
            .map { it.toProduct() }
    }

    // a product the list learned, which every user who sees the list forgets (GROCERY-11); false
    // when the user does not see the list or it has no such product. It stays as deleted
    fun deleteProduct(
        user: String,
        listId: Int,
        id: Int,
    ): Boolean =
        database.dsl
            .update(GROCERY_PRODUCTS)
            .set(GROCERY_PRODUCTS.DELETED_AT, clock.instant())
            .where(
                GROCERY_PRODUCTS.ID.eq(id),
                GROCERY_PRODUCTS.LIST_ID.eq(listId),
                GROCERY_PRODUCTS.DELETED_AT.isNull,
                GROCERY_PRODUCTS.LIST_ID.`in`(DSL.select(GROCERY_LISTS.ID).from(GROCERY_LISTS).where(visibleList(user))),
            ).execute() > 0

    // the live lists the user sees: their own and the shared ones of the others (LIST-2)
    private fun visibleList(user: String): Condition =
        GROCERY_LISTS.DELETED_AT.isNull.and(GROCERY_LISTS.OWNER.eq(user).or(GROCERY_LISTS.SHARED.isTrue))

    // the live lists the user owns, the only ones they change or delete (GROCERY-1)
    private fun ownList(user: String): Condition = GROCERY_LISTS.DELETED_AT.isNull.and(GROCERY_LISTS.OWNER.eq(user))

    // the live items on the lists the user sees
    private fun visibleItem(user: String): Condition =
        GROCERY_ITEMS.DELETED_AT.isNull.and(
            GROCERY_ITEMS.LIST_ID.`in`(DSL.select(GROCERY_LISTS.ID).from(GROCERY_LISTS).where(visibleList(user))),
        )

    // the list's product of the normalized name (groceryNameKey) takes the values, the name as
    // written last and one more use; made when there is none, owned by the user who adds (MODEL-5)
    private fun learn(
        dsl: DSLContext,
        owner: String,
        listId: Int,
        values: GroceryItemValues,
        now: Instant,
    ) {
        val key = groceryNameKey(values.name)
        val same = listProduct(listId, key)
        fun update() =
            dsl
                .update(GROCERY_PRODUCTS)
                .set(GROCERY_PRODUCTS.NAME, values.name)
                .set(GROCERY_PRODUCTS.NAME_KEY, key)
                .set(GROCERY_PRODUCTS.AMOUNT, values.amount)
                .set(GROCERY_PRODUCTS.UNIT, values.unit)
                .set(GROCERY_PRODUCTS.CATEGORY_ID, values.categoryId)
                .set(GROCERY_PRODUCTS.TIMES_USED, GROCERY_PRODUCTS.TIMES_USED.plus(1))
                .set(GROCERY_PRODUCTS.LAST_USED_AT, now)
                .set(GROCERY_PRODUCTS.UPDATED_AT, now)
                .where(same)
                .execute()
        if (update() > 0) return
        val inserted =
            dsl
                .insertInto(GROCERY_PRODUCTS)
                .set(GROCERY_PRODUCTS.OWNER, owner)
                .set(GROCERY_PRODUCTS.LIST_ID, listId)
                .set(GROCERY_PRODUCTS.NAME, values.name)
                .set(GROCERY_PRODUCTS.NAME_KEY, key)
                .set(GROCERY_PRODUCTS.AMOUNT, values.amount)
                .set(GROCERY_PRODUCTS.UNIT, values.unit)
                .set(GROCERY_PRODUCTS.CATEGORY_ID, values.categoryId)
                .set(GROCERY_PRODUCTS.TIMES_USED, 1)
                .set(GROCERY_PRODUCTS.LAST_USED_AT, now)
                .set(GROCERY_PRODUCTS.CREATED_AT, now)
                .onConflictDoNothing()
                .execute()
        // made at the same time by another item of the list
        if (inserted == 0) update()
    }

    // the live product the list learned of the normalized name (groceryNameKey)
    private fun listProduct(
        listId: Int,
        key: String,
    ): Condition =
        GROCERY_PRODUCTS.LIST_ID
            .eq(listId)
            .and(GROCERY_PRODUCTS.NAME_KEY.eq(key))
            .and(GROCERY_PRODUCTS.DELETED_AT.isNull)

    // the live category of the list user owns
    private fun ownCategory(
        user: String,
        listId: Int,
        id: Int,
    ): Condition =
        GROCERY_CATEGORIES.ID
            .eq(id)
            .and(GROCERY_CATEGORIES.LIST_ID.eq(listId))
            .and(GROCERY_CATEGORIES.DELETED_AT.isNull)
            .and(GROCERY_CATEGORIES.LIST_ID.`in`(DSL.select(GROCERY_LISTS.ID).from(GROCERY_LISTS).where(ownList(user))))

    // the list's product of the name of the item takes its category (GROCERY-13)
    private fun teachCategory(
        dsl: DSLContext,
        item: GroceryItemsRecord,
        now: Instant,
    ) = dsl
        .update(GROCERY_PRODUCTS)
        .set(GROCERY_PRODUCTS.CATEGORY_ID, item.categoryId)
        .set(GROCERY_PRODUCTS.UPDATED_AT, now)
        .where(listProduct(item.listId!!, item.nameKey!!))
        .execute()

    // the id when it is a live category of the list, else null
    private fun categoryOf(
        dsl: DSLContext,
        listId: Int,
        id: Int?,
    ): Int? =
        id?.takeIf {
            dsl.fetchExists(GROCERY_CATEGORIES, GROCERY_CATEGORIES.ID.eq(it), GROCERY_CATEGORIES.LIST_ID.eq(listId), GROCERY_CATEGORIES.DELETED_AT.isNull)
        }

    // the categories of a new list in their order, made by its owner
    private fun insertCategories(
        dsl: DSLContext,
        owner: String,
        listId: Int,
        categories: List<GroceryCategoryValues>,
        now: Instant,
    ) = categories.forEachIndexed { position, values -> insertCategory(dsl, owner, listId, values, position, now) }

    private fun insertCategory(
        dsl: DSLContext,
        owner: String,
        listId: Int,
        values: GroceryCategoryValues,
        position: Int,
        now: Instant,
    ): GroceryCategoriesRecord =
        dsl
            .insertInto(GROCERY_CATEGORIES)
            .set(GROCERY_CATEGORIES.LIST_ID, listId)
            .set(GROCERY_CATEGORIES.OWNER, owner)
            .set(GROCERY_CATEGORIES.NAME, values.name)
            .set(GROCERY_CATEGORIES.NAME_KEY, groceryNameKey(values.name))
            .set(GROCERY_CATEGORIES.ICON, values.icon)
            .set(GROCERY_CATEGORIES.POSITION, position)
            .set(GROCERY_CATEGORIES.CREATED_AT, now)
            .returning()
            .fetchOne()!!

    private fun itemsToBuy(listId: Int): Int =
        database.dsl.fetchCount(GROCERY_ITEMS, GROCERY_ITEMS.LIST_ID.eq(listId), GROCERY_ITEMS.DELETED_AT.isNull)

    private fun GroceryListsRecord.toList(items: Int) = GroceryList(id!!, owner!!, name!!, shared!!, createdAt!!, updatedAt, items, icon)

    private fun GroceryItemsRecord.toItem() =
        GroceryItem(id!!, listId!!, owner!!, name!!, amount, unit, categoryId, createdAt!!, updatedAt, photoFileId)

    private fun GroceryProductsRecord.toProduct() =
        GroceryProduct(id!!, listId!!, owner!!, name!!, amount, unit, categoryId, timesUsed!!, lastUsedAt!!)

    private fun GroceryCategoriesRecord.toCategory() = GroceryCategory(id!!, listId!!, name!!, icon, position!!)
}
