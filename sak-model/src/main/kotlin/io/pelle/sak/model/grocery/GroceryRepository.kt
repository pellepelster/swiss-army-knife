package io.pelle.sak.model.grocery

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.files.StoredFile
import io.pelle.sak.model.files.toFile
import io.pelle.sak.model.lists.ListDelete
import io.pelle.sak.model.lists.ListTable
import io.pelle.sak.model.lists.ListUpdate
import io.pelle.sak.model.lists.SakList
import io.pelle.sak.model.jooq.tables.records.GroceryCategoriesRecord
import io.pelle.sak.model.jooq.tables.records.GroceryItemsRecord
import io.pelle.sak.model.jooq.tables.references.FILES
import io.pelle.sak.model.jooq.tables.references.GROCERY_CATEGORIES
import io.pelle.sak.model.jooq.tables.references.GROCERY_ITEMS
import io.pelle.sak.model.jooq.tables.references.GROCERY_LISTS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

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

    // the user sees no such list, or it has no such category
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

    // the user sees no such list
    data object NotFound : GroceryCategoryOrder

    // the ids are not the ones of the categories of the list, each once
    data object Mismatch : GroceryCategoryOrder
}

// what is to buy, how much of it and in which category; a bought item is checked and stays as the
// history of its list, what its suggestions are made of (GROCERY-3, GROCERY-11)
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
    // when it was bought, null while it is to buy (GROCERY-3)
    val checkedAt: Instant? = null,
)

// a name added to a list before, what the suggestions of the list are (GROCERY-11): the values of
// the latest live item of the name, how many live items of the name the list has and when the
// latest was added
data class GrocerySuggestion(
    // the latest item of the name, what forgetting the suggestion names
    val id: Int,
    val listId: Int,
    val name: String,
    val amount: BigDecimal?,
    val unit: String?,
    // a category of the list, null for none (GROCERY-13)
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

// the database access of the grocery app: its lists, their categories, their items and the
// suggestions made of them; only GroceryService (sak-backend) uses it. It reads the rows that are not deleted only, and
// checks who sees and changes them in the sql of each method (MODEL-6): a user sees and changes
// their own lists and the shared ones of the others, their names, icons, categories and items
// (LIST-2); only the owner shares, unshares and deletes a list
class GroceryRepository(
    private val database: SakDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    // the lists, with how many items are to buy on each
    private val lists =
        ListTable(
            database,
            clock,
            GROCERY_LISTS,
            GROCERY_LISTS.ID,
            GROCERY_LISTS.OWNER,
            GROCERY_LISTS.NAME,
            GROCERY_LISTS.SHARED,
            GROCERY_LISTS.ICON,
            GROCERY_LISTS.CREATED_AT,
            GROCERY_LISTS.UPDATED_AT,
            GROCERY_LISTS.DELETED_AT,
            DSL.field(
                DSL
                    .selectCount()
                    .from(GROCERY_ITEMS)
                    .where(GROCERY_ITEMS.LIST_ID.eq(GROCERY_LISTS.ID), GROCERY_ITEMS.DELETED_AT.isNull, GROCERY_ITEMS.CHECKED_AT.isNull),
            ),
        )

    // the lists of the user and the shared ones of the others, by name
    fun lists(user: String): List<SakList> = lists.lists(user)

    // null when the user does not see the list
    fun list(
        user: String,
        id: Int,
    ): SakList? = lists.list(user, id)

    // whether the user sees the list or saw it until it was deleted: whom a change of it reaches
    // (LIST-4)
    fun seesList(
        user: String,
        id: Int,
    ): Boolean = lists.seesList(user, id)

    // the list with the categories in their order; null when the owner has a list of the name
    fun insertList(
        owner: String,
        name: String,
        shared: Boolean,
        icon: String? = null,
        categories: List<GroceryCategoryValues> = emptyList(),
    ): SakList? = lists.insert(owner, name, shared, icon) { dsl, id, now -> insertCategories(dsl, owner, id, categories, now) }

    // a list the user sees, see ListTable.update
    fun updateList(
        user: String,
        id: Int,
        name: String,
        shared: Boolean,
        icon: String? = null,
    ): ListUpdate = lists.update(user, id, name, shared, icon)

    // a list of the user's own, the last one too (LIST-1); it, its items and its categories stay as
    // deleted
    fun deleteList(
        user: String,
        id: Int,
    ): ListDelete =
        lists.delete(user, id) { dsl, now ->
            dsl
                .update(GROCERY_ITEMS)
                .set(GROCERY_ITEMS.DELETED_AT, now)
                .where(GROCERY_ITEMS.LIST_ID.eq(id), GROCERY_ITEMS.DELETED_AT.isNull)
                .execute()
            // its categories go with it (GROCERY-13)
            dsl
                .update(GROCERY_CATEGORIES)
                .set(GROCERY_CATEGORIES.DELETED_AT, now)
                .where(GROCERY_CATEGORIES.LIST_ID.eq(id), GROCERY_CATEGORIES.DELETED_AT.isNull)
                .execute()
        }

    // the items to buy of the list in the order they were added, with checked also the latest bought
    // item of each name that is not to buy (GROCERY-14); null when the user does not see the list
    fun items(
        user: String,
        listId: Int,
        checked: Boolean = false,
    ): List<GroceryItem>? =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            if (!dsl.fetchExists(GROCERY_LISTS, GROCERY_LISTS.ID.eq(listId), visibleList(user))) return@transactionResult null
            val toBuy = GROCERY_ITEMS.LIST_ID.eq(listId).and(GROCERY_ITEMS.DELETED_AT.isNull).and(GROCERY_ITEMS.CHECKED_AT.isNull)
            // the latest bought item of each name that is not to buy, so the history shows each name once (GROCERY-14)
            val bought =
                if (checked) {
                    val latestFirst =
                        DSL
                            .rowNumber()
                            .over(DSL.partitionBy(GROCERY_ITEMS.NAME_KEY).orderBy(GROCERY_ITEMS.CHECKED_AT.desc(), GROCERY_ITEMS.ID.desc()))
                            .`as`("latest_first")
                    val ranked =
                        DSL
                            .select(GROCERY_ITEMS.ID, latestFirst)
                            .from(GROCERY_ITEMS)
                            .where(
                                GROCERY_ITEMS.LIST_ID.eq(listId),
                                GROCERY_ITEMS.DELETED_AT.isNull,
                                GROCERY_ITEMS.CHECKED_AT.isNotNull,
                                GROCERY_ITEMS.NAME_KEY.notIn(DSL.select(GROCERY_ITEMS.NAME_KEY).from(GROCERY_ITEMS).where(toBuy)),
                            ).asTable("ranked")
                    GROCERY_ITEMS.ID.`in`(DSL.select(ranked.field(GROCERY_ITEMS.ID)).from(ranked).where(ranked.field(latestFirst)!!.eq(1)))
                } else {
                    DSL.falseCondition()
                }
            dsl
                .selectFrom(GROCERY_ITEMS)
                .where(toBuy.or(bought))
                .orderBy(GROCERY_ITEMS.ID)
                .fetch()
                .map { it.toItem() }
        }

    // a live item, to buy or bought; null when the user does not see its list, or it is deleted
    fun item(
        user: String,
        id: Int,
    ): GroceryItem? =
        database.dsl
            .selectFrom(GROCERY_ITEMS)
            .where(GROCERY_ITEMS.ID.eq(id), visibleItem(user))
            .fetchOne()
            ?.toItem()

    // the item on the list, null when the user does not see the list. It takes the photo and, without
    // one given, the category of the latest item of its name on the list (GROCERY-12, GROCERY-13). The
    // user is its owner (MODEL-5)
    fun insertItem(
        user: String,
        listId: Int,
        values: GroceryItemValues,
    ): GroceryItem? =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            if (!dsl.fetchExists(GROCERY_LISTS, GROCERY_LISTS.ID.eq(listId), visibleList(user))) return@transactionResult null
            val key = groceryNameKey(values.name)
            val latest =
                dsl
                    .select(GROCERY_ITEMS.PHOTO_FILE_ID, GROCERY_ITEMS.CATEGORY_ID)
                    .from(GROCERY_ITEMS)
                    .where(GROCERY_ITEMS.LIST_ID.eq(listId), GROCERY_ITEMS.NAME_KEY.eq(key), GROCERY_ITEMS.DELETED_AT.isNull)
                    .orderBy(GROCERY_ITEMS.ID.desc())
                    .limit(1)
                    .fetchOne()
            dsl
                .insertInto(GROCERY_ITEMS)
                .set(GROCERY_ITEMS.LIST_ID, listId)
                .set(GROCERY_ITEMS.OWNER, user)
                .set(GROCERY_ITEMS.NAME, values.name)
                .set(GROCERY_ITEMS.NAME_KEY, key)
                .set(GROCERY_ITEMS.AMOUNT, values.amount)
                .set(GROCERY_ITEMS.UNIT, values.unit)
                .set(GROCERY_ITEMS.CATEGORY_ID, categoryOf(dsl, listId, values.categoryId ?: latest?.value2()))
                .set(GROCERY_ITEMS.PHOTO_FILE_ID, latest?.value1())
                .set(GROCERY_ITEMS.CREATED_AT, clock.instant())
                .returning()
                .fetchOne()!!
                .toItem()
        }

    // null when the user does not see its list
    fun updateItem(
        user: String,
        id: Int,
        values: GroceryItemValues,
    ): GroceryItem? =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val listId =
                dsl
                    .select(GROCERY_ITEMS.LIST_ID)
                    .from(GROCERY_ITEMS)
                    .where(GROCERY_ITEMS.ID.eq(id), visibleItem(user))
                    .fetchOne()
                    ?.value1() ?: return@transactionResult null
            dsl
                .update(GROCERY_ITEMS)
                .set(GROCERY_ITEMS.NAME, values.name)
                .set(GROCERY_ITEMS.NAME_KEY, groceryNameKey(values.name))
                .set(GROCERY_ITEMS.AMOUNT, values.amount)
                .set(GROCERY_ITEMS.UNIT, values.unit)
                .set(GROCERY_ITEMS.CATEGORY_ID, categoryOf(dsl, listId, values.categoryId))
                .set(GROCERY_ITEMS.UPDATED_AT, clock.instant())
                .where(GROCERY_ITEMS.ID.eq(id))
                .returning()
                .fetchOne()!!
                .toItem()
        }

    // the category of a live item, to buy or bought, on a list the user sees, one of its list or none; the next item
    // of the name on the list takes it (GROCERY-13). Null when the user does not see the item or the
    // category is none of the live ones of its list
    fun setCategory(
        user: String,
        id: Int,
        categoryId: Int?,
    ): GroceryItem? =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val current = dsl.selectFrom(GROCERY_ITEMS).where(GROCERY_ITEMS.ID.eq(id), visibleItem(user)).fetchOne() ?: return@transactionResult null
            if (categoryId != null && categoryOf(dsl, current.listId!!, categoryId) == null) return@transactionResult null
            dsl
                .update(GROCERY_ITEMS)
                .set(GROCERY_ITEMS.CATEGORY_ID, categoryId)
                .set(GROCERY_ITEMS.UPDATED_AT, clock.instant())
                .where(GROCERY_ITEMS.ID.eq(id))
                .returning()
                .fetchOne()!!
                .toItem()
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

    // a category of a list the user sees (LIST-2), the last one; at most max of them
    fun insertCategory(
        user: String,
        listId: Int,
        values: GroceryCategoryValues,
        max: Int,
    ): GroceryCategoryChange =
        try {
            database.dsl.transactionResult { configuration ->
                val dsl = DSL.using(configuration)
                if (!dsl.fetchExists(GROCERY_LISTS, GROCERY_LISTS.ID.eq(listId), visibleList(user))) {
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

    // the name and the icon of a category of a list the user sees; TooMany never
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
                .where(visibleCategory(user, listId, id))
                .returning()
                .fetchOne()
                ?.let { GroceryCategoryChange.Saved(it.toCategory()) } ?: GroceryCategoryChange.NotFound
        } catch (_: IntegrityConstraintViolationException) {
            GroceryCategoryChange.NameTaken
        }

    // a category of a list the user sees; the items of the list lose it, the checked ones too, so
    // its suggestions do (GROCERY-13). It stays as deleted. False when the user sees no such list or it has no such category
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
                    .where(visibleCategory(user, listId, id))
                    .execute() > 0
            if (deleted) {
                dsl
                    .update(GROCERY_ITEMS)
                    .setNull(GROCERY_ITEMS.CATEGORY_ID)
                    .set(GROCERY_ITEMS.UPDATED_AT, now)
                    .where(GROCERY_ITEMS.CATEGORY_ID.eq(id))
                    .execute()
            }
            deleted
        }

    // the categories of a list the user sees in the order of the ids, which are all of its live
    // categories, each once
    fun orderCategories(
        user: String,
        listId: Int,
        ids: List<Int>,
    ): GroceryCategoryOrder =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            if (!dsl.fetchExists(GROCERY_LISTS, GROCERY_LISTS.ID.eq(listId), visibleList(user))) return@transactionResult GroceryCategoryOrder.NotFound
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

    // the photo of a live item, to buy or bought, on a list the user sees, a file the user uploaded that is an image,
    // or none; the next item of the name on the list takes it (GROCERY-12). Null when the user does not see the item or the file is no image of
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
            dsl
                .update(GROCERY_ITEMS)
                .set(GROCERY_ITEMS.PHOTO_FILE_ID, fileId)
                .set(GROCERY_ITEMS.UPDATED_AT, clock.instant())
                .where(GROCERY_ITEMS.ID.eq(id), visibleItem(user))
                .returning()
                .fetchOne()
                ?.toItem()
        }

    // the file of the photo of a live item, to buy or checked, on a list the user sees, when it is
    // that photo; whoever uploaded it, a shared list shows it to every user (LIST-2)
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

    // bought (GROCERY-3): the item leaves the list and stays as its history, what its suggestions are
    // made of (GROCERY-11); false when the user does not see it or it is checked or deleted
    fun checkItem(
        user: String,
        id: Int,
    ): Boolean =
        database.dsl
            .update(GROCERY_ITEMS)
            .set(GROCERY_ITEMS.CHECKED_AT, clock.instant())
            .where(GROCERY_ITEMS.ID.eq(id), itemToBuy(user))
            .execute() > 0

    // an item, to buy or bought, gone for good, e.g. one added by mistake: no list shows it, no
    // suggestion counts it; false when the user does not see it. It stays as deleted
    fun deleteItem(
        user: String,
        id: Int,
    ): Boolean =
        database.dsl
            .update(GROCERY_ITEMS)
            .set(GROCERY_ITEMS.DELETED_AT, clock.instant())
            .where(GROCERY_ITEMS.ID.eq(id), visibleItem(user))
            .execute() > 0

    // a page of the names added to the list before whose name contains the text, both normalized
    // (groceryNameKey), with the values of the latest live item of each, the ones added most and last
    // first; the ones to buy on the list are left out. Null when the user does not see the list
    // (GROCERY-11)
    fun suggestions(
        user: String,
        listId: Int,
        text: String,
        offset: Long,
        limit: Int,
    ): List<GrocerySuggestion>? {
        if (!sees(user, listId)) return null
        val (items, latest) = suggestionItems(listId, text)
        val id = items.field(GROCERY_ITEMS.ID)!!
        val createdAt = items.field(GROCERY_ITEMS.CREATED_AT)!!
        val times = items.field(SUGGESTION_TIMES_USED, Int::class.java)!!
        return database.dsl
            .select(
                id,
                items.field(GROCERY_ITEMS.NAME)!!,
                items.field(GROCERY_ITEMS.AMOUNT)!!,
                items.field(GROCERY_ITEMS.UNIT)!!,
                items.field(GROCERY_ITEMS.CATEGORY_ID)!!,
                times,
                createdAt,
            ).from(items)
            .where(latest)
            .orderBy(times.desc(), createdAt.desc(), id)
            .limit(limit)
            .offset(offset)
            .fetch { (id, name, amount, unit, categoryId, times, createdAt) ->
                GrocerySuggestion(id!!, listId, name!!, amount, unit, categoryId, times!!, createdAt!!)
            }
    }

    // how many suggestions the list has for the text, as suggestions counts them; null when the user
    // does not see the list
    fun suggestionCount(
        user: String,
        listId: Int,
        text: String,
    ): Long? {
        if (!sees(user, listId)) return null
        val (items, latest) = suggestionItems(listId, text)
        return database.dsl.selectCount().from(items).where(latest).fetchOne(0, Long::class.java) ?: 0
    }

    private fun sees(
        user: String,
        listId: Int,
    ) = database.dsl.fetchExists(GROCERY_LISTS, GROCERY_LISTS.ID.eq(listId), visibleList(user))

    // the items of the list whose name contains the text, the ones to buy left out, numbered latest
    // first within their name and counted by it; the condition keeps the latest of each name
    private fun suggestionItems(
        listId: Int,
        text: String,
    ): Pair<Table<*>, Condition> {
        // two windows: the builder of one is changed by its order
        val latestFirst = DSL.rowNumber().over(DSL.partitionBy(GROCERY_ITEMS.NAME_KEY).orderBy(GROCERY_ITEMS.ID.desc())).`as`("latest_first")
        val timesUsed = DSL.count().over(DSL.partitionBy(GROCERY_ITEMS.NAME_KEY)).`as`(SUGGESTION_TIMES_USED)
        val toBuy =
            DSL
                .select(GROCERY_ITEMS.NAME_KEY)
                .from(GROCERY_ITEMS)
                .where(GROCERY_ITEMS.LIST_ID.eq(listId), GROCERY_ITEMS.DELETED_AT.isNull, GROCERY_ITEMS.CHECKED_AT.isNull)
        // the names filtered before the windows, which then number only what is shown
        val items =
            DSL
                .select(GROCERY_ITEMS.asterisk(), latestFirst, timesUsed)
                .from(GROCERY_ITEMS)
                .where(
                    GROCERY_ITEMS.LIST_ID.eq(listId),
                    GROCERY_ITEMS.DELETED_AT.isNull,
                    GROCERY_ITEMS.NAME_KEY.contains(groceryNameKey(text)),
                    GROCERY_ITEMS.NAME_KEY.notIn(toBuy),
                ).asTable("items")
        return items to items.field(latestFirst)!!.eq(1)
    }

    // forgets the suggestion of the name of the item (GROCERY-11): the checked items of the name on
    // the list are deleted for good, an item to buy stays; adding the name again brings it back.
    // Every user who sees the list forgets; false when the user does not see the list or it has no
    // checked item of the name
    fun forget(
        user: String,
        listId: Int,
        itemId: Int,
    ): Boolean =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val key =
                dsl
                    .select(GROCERY_ITEMS.NAME_KEY)
                    .from(GROCERY_ITEMS)
                    .where(GROCERY_ITEMS.ID.eq(itemId), GROCERY_ITEMS.LIST_ID.eq(listId), visibleItem(user))
                    .fetchOne()
                    ?.value1() ?: return@transactionResult false
            dsl
                .update(GROCERY_ITEMS)
                .set(GROCERY_ITEMS.DELETED_AT, clock.instant())
                .where(
                    GROCERY_ITEMS.LIST_ID.eq(listId),
                    GROCERY_ITEMS.NAME_KEY.eq(key),
                    GROCERY_ITEMS.CHECKED_AT.isNotNull,
                    GROCERY_ITEMS.DELETED_AT.isNull,
                ).execute() > 0
        }

    // the live lists the user sees: their own and the shared ones of the others (LIST-2)
    private fun visibleList(user: String): Condition = lists.visible(user)

    // the live items, to buy or checked, on the lists the user sees
    private fun visibleItem(user: String): Condition =
        GROCERY_ITEMS.DELETED_AT.isNull.and(
            GROCERY_ITEMS.LIST_ID.`in`(DSL.select(GROCERY_LISTS.ID).from(GROCERY_LISTS).where(visibleList(user))),
        )

    // the items to buy on the lists the user sees, the only ones that are checked
    private fun itemToBuy(user: String): Condition = visibleItem(user).and(GROCERY_ITEMS.CHECKED_AT.isNull)

    // the live category of a list the user sees, which they change (LIST-2)
    private fun visibleCategory(
        user: String,
        listId: Int,
        id: Int,
    ): Condition =
        GROCERY_CATEGORIES.ID
            .eq(id)
            .and(GROCERY_CATEGORIES.LIST_ID.eq(listId))
            .and(GROCERY_CATEGORIES.DELETED_AT.isNull)
            .and(GROCERY_CATEGORIES.LIST_ID.`in`(DSL.select(GROCERY_LISTS.ID).from(GROCERY_LISTS).where(visibleList(user))))

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

    private fun GroceryItemsRecord.toItem() =
        GroceryItem(id!!, listId!!, owner!!, name!!, amount, unit, categoryId, createdAt!!, updatedAt, photoFileId, checkedAt)

    private fun GroceryCategoriesRecord.toCategory() = GroceryCategory(id!!, listId!!, name!!, icon, position!!)
}

// the column of the suggestions with how many items of the name the list has had
private const val SUGGESTION_TIMES_USED = "times_used"
