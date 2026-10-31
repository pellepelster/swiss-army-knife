package io.pelle.sak.model.grocery

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.GroceryItemsRecord
import io.pelle.sak.model.jooq.tables.records.GroceryListsRecord
import io.pelle.sak.model.jooq.tables.records.GroceryProductsRecord
import io.pelle.sak.model.jooq.tables.references.GROCERY_ITEMS
import io.pelle.sak.model.jooq.tables.references.GROCERY_LISTS
import io.pelle.sak.model.jooq.tables.references.GROCERY_PRODUCTS
import org.jooq.DSLContext
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

// the list every item of a user goes to while they have none (LIST-1), made with their first item
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

// what is bought, how much of it and in which category; checked items record when and by whom
data class GroceryItem(
    val id: Int,
    val listId: Int,
    val owner: String,
    val name: String,
    val amount: BigDecimal?,
    val unit: String?,
    val category: String?,
    val checkedAt: Instant?,
    val checkedBy: String?,
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

// the database access of the grocery app: its lists, their items and the products learned from
// them; only GroceryService (sak-backend) uses it. It reads the rows that are not deleted only, who
// may see or change them is up to the service
class GroceryRepository(
    private val database: SakDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    // the lists of the user and the shared ones of the others, the default list of the user first
    fun lists(user: String): List<GroceryList> =
        database.dsl
            .selectFrom(GROCERY_LISTS)
            .where(GROCERY_LISTS.DELETED_AT.isNull, GROCERY_LISTS.OWNER.eq(user).or(GROCERY_LISTS.SHARED.isTrue))
            .orderBy(
                DSL.`when`(GROCERY_LISTS.OWNER.eq(user).and(GROCERY_LISTS.NAME.eq(DEFAULT_GROCERY_LIST)), 0).otherwise(1),
                GROCERY_LISTS.NAME,
                GROCERY_LISTS.ID,
            ).fetch()
            .map { it.toList() }

    fun list(id: Int): GroceryList? =
        database.dsl
            .selectFrom(GROCERY_LISTS)
            .where(GROCERY_LISTS.ID.eq(id), GROCERY_LISTS.DELETED_AT.isNull)
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

    // the list as it is stored now, null when its owner has another list of the name
    fun updateList(
        id: Int,
        name: String,
        shared: Boolean,
    ): GroceryList? =
        try {
            database.dsl
                .update(GROCERY_LISTS)
                .set(GROCERY_LISTS.NAME, name)
                .set(GROCERY_LISTS.SHARED, shared)
                .set(GROCERY_LISTS.UPDATED_AT, clock.instant())
                .where(GROCERY_LISTS.ID.eq(id), GROCERY_LISTS.DELETED_AT.isNull)
                .returning()
                .fetchOne()
                ?.toList()
        } catch (_: IntegrityConstraintViolationException) {
            null
        }

    // the list and its items stay as deleted
    fun deleteList(id: Int) =
        database.dsl.transaction { configuration ->
            val dsl = DSL.using(configuration)
            val now = clock.instant()
            dsl
                .update(GROCERY_ITEMS)
                .set(GROCERY_ITEMS.DELETED_AT, now)
                .where(GROCERY_ITEMS.LIST_ID.eq(id), GROCERY_ITEMS.DELETED_AT.isNull)
                .execute()
            dsl
                .update(GROCERY_LISTS)
                .set(GROCERY_LISTS.DELETED_AT, now)
                .where(GROCERY_LISTS.ID.eq(id), GROCERY_LISTS.DELETED_AT.isNull)
                .execute()
        }

    // the items of the list in the order they were added
    fun items(listId: Int): List<GroceryItem> =
        database.dsl
            .selectFrom(GROCERY_ITEMS)
            .where(GROCERY_ITEMS.LIST_ID.eq(listId), GROCERY_ITEMS.DELETED_AT.isNull)
            .orderBy(GROCERY_ITEMS.ID)
            .fetch()
            .map { it.toItem() }

    fun item(id: Int): GroceryItem? =
        database.dsl
            .selectFrom(GROCERY_ITEMS)
            .where(GROCERY_ITEMS.ID.eq(id), GROCERY_ITEMS.DELETED_AT.isNull)
            .fetchOne()
            ?.toItem()

    // the item on the list, or on the default list of the owner when none is given, which is made
    // when there is none; the product of its name learns it, all in one transaction
    fun insertItem(
        owner: String,
        listId: Int?,
        values: GroceryItemValues,
    ): GroceryItem =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val now = clock.instant()
            val item =
                dsl
                    .insertInto(GROCERY_ITEMS)
                    .set(GROCERY_ITEMS.LIST_ID, listId ?: defaultList(dsl, owner, now))
                    .set(GROCERY_ITEMS.OWNER, owner)
                    .set(GROCERY_ITEMS.NAME, values.name)
                    .set(GROCERY_ITEMS.AMOUNT, values.amount)
                    .set(GROCERY_ITEMS.UNIT, values.unit)
                    .set(GROCERY_ITEMS.CATEGORY, values.category)
                    .set(GROCERY_ITEMS.CREATED_AT, now)
                    .returning()
                    .fetchOne()!!
            learn(dsl, owner, values, now)
            item.toItem()
        }

    // the values of the item and whether it is checked, by whom when it is checked now; a checked
    // item keeps when and by whom it was checked first
    fun updateItem(
        id: Int,
        values: GroceryItemValues,
        checked: Boolean,
        by: String,
    ): GroceryItem? {
        val now = clock.instant()
        return database.dsl
            .update(GROCERY_ITEMS)
            .set(GROCERY_ITEMS.NAME, values.name)
            .set(GROCERY_ITEMS.AMOUNT, values.amount)
            .set(GROCERY_ITEMS.UNIT, values.unit)
            .set(GROCERY_ITEMS.CATEGORY, values.category)
            .set(GROCERY_ITEMS.CHECKED_AT, if (checked) DSL.coalesce(GROCERY_ITEMS.CHECKED_AT, DSL.value(now, GROCERY_ITEMS.CHECKED_AT)) else DSL.castNull(GROCERY_ITEMS.CHECKED_AT))
            .set(GROCERY_ITEMS.CHECKED_BY, if (checked) DSL.coalesce(GROCERY_ITEMS.CHECKED_BY, DSL.value(by, GROCERY_ITEMS.CHECKED_BY)) else DSL.castNull(GROCERY_ITEMS.CHECKED_BY))
            .set(GROCERY_ITEMS.UPDATED_AT, now)
            .where(GROCERY_ITEMS.ID.eq(id), GROCERY_ITEMS.DELETED_AT.isNull)
            .returning()
            .fetchOne()
            ?.toItem()
    }

    // false when there was none; it stays as deleted
    fun deleteItem(id: Int): Boolean =
        database.dsl
            .update(GROCERY_ITEMS)
            .set(GROCERY_ITEMS.DELETED_AT, clock.instant())
            .where(GROCERY_ITEMS.ID.eq(id), GROCERY_ITEMS.DELETED_AT.isNull)
            .execute() > 0

    // the checked items of the list stay as deleted
    fun deleteChecked(listId: Int): Int =
        database.dsl
            .update(GROCERY_ITEMS)
            .set(GROCERY_ITEMS.DELETED_AT, clock.instant())
            .where(GROCERY_ITEMS.LIST_ID.eq(listId), GROCERY_ITEMS.CHECKED_AT.isNotNull, GROCERY_ITEMS.DELETED_AT.isNull)
            .execute()

    // the products of the owner whose name contains the text, the ones bought most and last first;
    // the ones on the list to buy are left out
    fun products(
        owner: String,
        text: String,
        listId: Int?,
        limit: Int,
    ): List<GroceryProduct> {
        val toBuy =
            DSL
                .select(DSL.lower(GROCERY_ITEMS.NAME))
                .from(GROCERY_ITEMS)
                .where(GROCERY_ITEMS.LIST_ID.eq(listId), GROCERY_ITEMS.CHECKED_AT.isNull, GROCERY_ITEMS.DELETED_AT.isNull)
        return database.dsl
            .selectFrom(GROCERY_PRODUCTS)
            .where(
                GROCERY_PRODUCTS.OWNER.eq(owner),
                GROCERY_PRODUCTS.DELETED_AT.isNull,
                DSL.lower(GROCERY_PRODUCTS.NAME).contains(text.lowercase()),
                if (listId == null) DSL.noCondition() else DSL.lower(GROCERY_PRODUCTS.NAME).notIn(toBuy),
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

    // the default list of the owner, made when there is none; one made at the same time by another
    // item of the owner is the one (the name is unique among the lists of the owner)
    private fun defaultList(
        dsl: DSLContext,
        owner: String,
        now: Instant,
    ): Int {
        val default = GROCERY_LISTS.OWNER.eq(owner).and(GROCERY_LISTS.NAME.eq(DEFAULT_GROCERY_LIST)).and(GROCERY_LISTS.DELETED_AT.isNull)
        dsl.select(GROCERY_LISTS.ID).from(GROCERY_LISTS).where(default).fetchOne(GROCERY_LISTS.ID)?.let { return it }
        dsl
            .insertInto(GROCERY_LISTS)
            .set(GROCERY_LISTS.OWNER, owner)
            .set(GROCERY_LISTS.NAME, DEFAULT_GROCERY_LIST)
            .set(GROCERY_LISTS.SHARED, false)
            .set(GROCERY_LISTS.CREATED_AT, now)
            .onConflictDoNothing()
            .execute()
        return dsl.select(GROCERY_LISTS.ID).from(GROCERY_LISTS).where(default).fetchOne(GROCERY_LISTS.ID)!!
    }

    // the product of the name, in any case, takes the values and one more use; made when there is
    // none
    private fun learn(
        dsl: DSLContext,
        owner: String,
        values: GroceryItemValues,
        now: Instant,
    ) {
        val same =
            GROCERY_PRODUCTS.OWNER
                .eq(owner)
                .and(DSL.lower(GROCERY_PRODUCTS.NAME).eq(values.name.lowercase()))
                .and(GROCERY_PRODUCTS.DELETED_AT.isNull)
        fun update() =
            dsl
                .update(GROCERY_PRODUCTS)
                .set(GROCERY_PRODUCTS.NAME, values.name)
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
        GroceryItem(id!!, listId!!, owner!!, name!!, amount, unit, category, checkedAt, checkedBy, createdAt!!, updatedAt)

    private fun GroceryProductsRecord.toProduct() = GroceryProduct(id!!, owner!!, name!!, amount, unit, category, timesUsed!!, lastUsedAt!!)
}
