package io.pelle.sak.model.grocery

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.GroceryItemsRecord
import io.pelle.sak.model.jooq.tables.records.GroceryListsRecord
import io.pelle.sak.model.jooq.tables.records.GroceryProductsRecord
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
)

// what is to buy, how much of it and in which category; a bought item is deleted (GROCERY-2)
data class GroceryItem(
    val id: Int,
    val listId: Int,
    val owner: String,
    val name: String,
    val amount: BigDecimal?,
    val unit: String?,
    val category: String?,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

// an item a user added before, what the suggestions are made of (GROCERY-7)
data class GroceryProduct(
    val id: Int,
    val owner: String,
    val name: String,
    val amount: BigDecimal?,
    val unit: String?,
    val category: String?,
    val timesUsed: Int,
    val lastUsedAt: Instant,
)

// what the user gives of an item
data class GroceryItemValues(
    val name: String,
    val amount: BigDecimal?,
    val unit: String?,
    val category: String?,
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
// changes or deletes a list (GROCERY-1)
class GroceryRepository(
    private val database: SakDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    // the lists of the user and the shared ones of the others, by name
    fun lists(user: String): List<GroceryList> =
        database.dsl
            .selectFrom(GROCERY_LISTS)
            .where(visibleList(user))
            .orderBy(GROCERY_LISTS.NAME, GROCERY_LISTS.ID)
            .fetch()
            .map { it.toList() }

    // a private list of the name for the owner when they have no list of their own (LIST-1); a
    // list made at the same time, e.g. by another sign-in, is the one (the name is unique among the
    // lists of the owner)
    fun ensureList(
        owner: String,
        name: String,
    ) {
        database.dsl.transaction { configuration ->
            val dsl = DSL.using(configuration)
            if (dsl.fetchExists(GROCERY_LISTS, ownList(owner))) return@transaction
            dsl
                .insertInto(GROCERY_LISTS)
                .set(GROCERY_LISTS.OWNER, owner)
                .set(GROCERY_LISTS.NAME, name)
                .set(GROCERY_LISTS.SHARED, false)
                .set(GROCERY_LISTS.CREATED_AT, clock.instant())
                .onConflictDoNothing()
                .execute()
        }
    }

    // null when the user does not see the list
    fun list(
        user: String,
        id: Int,
    ): GroceryList? =
        database.dsl
            .selectFrom(GROCERY_LISTS)
            .where(GROCERY_LISTS.ID.eq(id), visibleList(user))
            .fetchOne()
            ?.toList()

    // null when the owner has a list of the name
    fun insertList(
        owner: String,
        name: String,
        shared: Boolean,
    ): GroceryList? =
        try {
            database.dsl
                .insertInto(GROCERY_LISTS)
                .set(GROCERY_LISTS.OWNER, owner)
                .set(GROCERY_LISTS.NAME, name)
                .set(GROCERY_LISTS.SHARED, shared)
                .set(GROCERY_LISTS.CREATED_AT, clock.instant())
                .returning()
                .fetchOne()
                ?.toList()
        } catch (_: IntegrityConstraintViolationException) {
            null
        }

    // a list of the user's own
    fun updateList(
        user: String,
        id: Int,
        name: String,
        shared: Boolean,
    ): GroceryListUpdate =
        try {
            database.dsl
                .update(GROCERY_LISTS)
                .set(GROCERY_LISTS.NAME, name)
                .set(GROCERY_LISTS.SHARED, shared)
                .set(GROCERY_LISTS.UPDATED_AT, clock.instant())
                .where(GROCERY_LISTS.ID.eq(id), ownList(user))
                .returning()
                .fetchOne()
                ?.let { GroceryListUpdate.Updated(it.toList()) }
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

    // the item on the list, null when the user does not see the list; the product of its name
    // learns it, in the same transaction. The user is its owner (GROCERY-5)
    fun insertItem(
        user: String,
        listId: Int,
        values: GroceryItemValues,
    ): GroceryItem? =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            if (!dsl.fetchExists(GROCERY_LISTS, GROCERY_LISTS.ID.eq(listId), visibleList(user))) return@transactionResult null
            val now = clock.instant()
            val item =
                dsl
                    .insertInto(GROCERY_ITEMS)
                    .set(GROCERY_ITEMS.LIST_ID, listId)
                    .set(GROCERY_ITEMS.OWNER, user)
                    .set(GROCERY_ITEMS.NAME, values.name)
                    .set(GROCERY_ITEMS.NAME_KEY, groceryNameKey(values.name))
                    .set(GROCERY_ITEMS.AMOUNT, values.amount)
                    .set(GROCERY_ITEMS.UNIT, values.unit)
                    .set(GROCERY_ITEMS.CATEGORY, values.category)
                    .set(GROCERY_ITEMS.CREATED_AT, now)
                    .returning()
                    .fetchOne()!!
            learn(dsl, user, values, now)
            item.toItem()
        }

    // null when the user does not see its list
    fun updateItem(
        user: String,
        id: Int,
        values: GroceryItemValues,
    ): GroceryItem? =
        database.dsl
            .update(GROCERY_ITEMS)
            .set(GROCERY_ITEMS.NAME, values.name)
            .set(GROCERY_ITEMS.NAME_KEY, groceryNameKey(values.name))
            .set(GROCERY_ITEMS.AMOUNT, values.amount)
            .set(GROCERY_ITEMS.UNIT, values.unit)
            .set(GROCERY_ITEMS.CATEGORY, values.category)
            .set(GROCERY_ITEMS.UPDATED_AT, clock.instant())
            .where(GROCERY_ITEMS.ID.eq(id), visibleItem(user))
            .returning()
            .fetchOne()
            ?.toItem()

    // bought (GROCERY-2); false when the user does not see its list. It stays as deleted
    fun deleteItem(
        user: String,
        id: Int,
    ): Boolean =
        database.dsl
            .update(GROCERY_ITEMS)
            .set(GROCERY_ITEMS.DELETED_AT, clock.instant())
            .where(GROCERY_ITEMS.ID.eq(id), visibleItem(user))
            .execute() > 0

    // the products of the owner whose name contains the text, both normalized (groceryNameKey), the
    // ones bought most and last first; the ones to buy on the list are left out, when the owner sees it
    fun products(
        owner: String,
        text: String,
        listId: Int?,
        limit: Int,
    ): List<GroceryProduct> {
        val toBuy =
            DSL
                .select(GROCERY_ITEMS.NAME_KEY)
                .from(GROCERY_ITEMS)
                .where(GROCERY_ITEMS.LIST_ID.eq(listId), visibleItem(owner))
        return database.dsl
            .selectFrom(GROCERY_PRODUCTS)
            .where(
                GROCERY_PRODUCTS.OWNER.eq(owner),
                GROCERY_PRODUCTS.DELETED_AT.isNull,
                GROCERY_PRODUCTS.NAME_KEY.contains(groceryNameKey(text)),
                if (listId == null) DSL.noCondition() else GROCERY_PRODUCTS.NAME_KEY.notIn(toBuy),
            ).orderBy(GROCERY_PRODUCTS.TIMES_USED.desc(), GROCERY_PRODUCTS.LAST_USED_AT.desc(), GROCERY_PRODUCTS.ID)
            .limit(limit)
            .fetch()
            .map { it.toProduct() }
    }

    // false when the owner has no such product; it stays as deleted
    fun deleteProduct(
        owner: String,
        id: Int,
    ): Boolean =
        database.dsl
            .update(GROCERY_PRODUCTS)
            .set(GROCERY_PRODUCTS.DELETED_AT, clock.instant())
            .where(GROCERY_PRODUCTS.ID.eq(id), GROCERY_PRODUCTS.OWNER.eq(owner), GROCERY_PRODUCTS.DELETED_AT.isNull)
            .execute() > 0

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

    // the product of the normalized name (groceryNameKey) takes the values, the name as written
    // last and one more use; made when there is none
    private fun learn(
        dsl: DSLContext,
        owner: String,
        values: GroceryItemValues,
        now: Instant,
    ) {
        val key = groceryNameKey(values.name)
        val same =
            GROCERY_PRODUCTS.OWNER
                .eq(owner)
                .and(GROCERY_PRODUCTS.NAME_KEY.eq(key))
                .and(GROCERY_PRODUCTS.DELETED_AT.isNull)
        fun update() =
            dsl
                .update(GROCERY_PRODUCTS)
                .set(GROCERY_PRODUCTS.NAME, values.name)
                .set(GROCERY_PRODUCTS.NAME_KEY, key)
                .set(GROCERY_PRODUCTS.AMOUNT, values.amount)
                .set(GROCERY_PRODUCTS.UNIT, values.unit)
                .set(GROCERY_PRODUCTS.CATEGORY, values.category)
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
                .set(GROCERY_PRODUCTS.NAME, values.name)
                .set(GROCERY_PRODUCTS.NAME_KEY, key)
                .set(GROCERY_PRODUCTS.AMOUNT, values.amount)
                .set(GROCERY_PRODUCTS.UNIT, values.unit)
                .set(GROCERY_PRODUCTS.CATEGORY, values.category)
                .set(GROCERY_PRODUCTS.TIMES_USED, 1)
                .set(GROCERY_PRODUCTS.LAST_USED_AT, now)
                .set(GROCERY_PRODUCTS.CREATED_AT, now)
                .onConflictDoNothing()
                .execute()
        // made at the same time by another item of the owner
        if (inserted == 0) update()
    }

    private fun GroceryListsRecord.toList() = GroceryList(id!!, owner!!, name!!, shared!!, createdAt!!, updatedAt)

    private fun GroceryItemsRecord.toItem() =
        GroceryItem(id!!, listId!!, owner!!, name!!, amount, unit, category, createdAt!!, updatedAt)

    private fun GroceryProductsRecord.toProduct() = GroceryProduct(id!!, owner!!, name!!, amount, unit, category, timesUsed!!, lastUsedAt!!)
}
