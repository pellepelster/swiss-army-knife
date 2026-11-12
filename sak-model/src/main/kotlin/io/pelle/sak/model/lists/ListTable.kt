package io.pelle.sak.model.lists

import io.pelle.sak.model.SakDatabase
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant

// a list of a list-based app (LIST-1), e.g. a grocery or a todo list
data class SakList(
    val id: Int,
    val owner: String,
    val name: String,
    // of all users (LIST-2)
    val shared: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant?,
    // how many items are left on it, e.g. to buy or to do
    val items: Int,
    // one emoji, none shows the first letter of the name
    val icon: String? = null,
)

// what deleting a list came to
sealed interface ListDelete {
    data object Deleted : ListDelete

    // the user has no such list of their own
    data object NotFound : ListDelete
}

// what changing a list came to
sealed interface ListUpdate {
    data class Updated(
        val list: SakList,
        // the owner unshared it: the other users saw it until now (LIST-4)
        val unshared: Boolean = false,
    ) : ListUpdate

    // the user sees no such list
    data object NotFound : ListUpdate

    // the owner has another list of the name
    data object NameTaken : ListUpdate

    // the user is not the owner and would rename, share or unshare it
    data object NotOwner : ListUpdate
}

// the table of the lists of a list-based app and how many items are left on each, what every such app
// does with its lists the same way (LIST-1, LIST-2, MODEL-6): a user sees and changes their own lists
// and the shared ones of the others, their names and icons; only the owner shares, unshares and
// deletes a list. Only the rows that are not deleted are read
class ListTable<R : Record>(
    private val database: SakDatabase,
    private val clock: Clock,
    val table: Table<R>,
    val id: TableField<R, Int?>,
    private val owner: TableField<R, String?>,
    private val name: TableField<R, String?>,
    private val shared: TableField<R, Boolean?>,
    private val icon: TableField<R, String?>,
    private val createdAt: TableField<R, Instant?>,
    private val updatedAt: TableField<R, Instant?>,
    private val deletedAt: TableField<R, Instant?>,
    // how many items are left on the list of the row, a count correlated with id
    items: Field<Int>,
) {
    private val items = items.`as`("list_items")

    // the lists of the user and the shared ones of the others, by name
    fun lists(user: String): List<SakList> =
        database.dsl
            .select(table.asterisk(), items)
            .from(table)
            .where(visible(user))
            .orderBy(name, id)
            .fetch { it.toList() }

    // null when the user does not see the list
    fun list(
        user: String,
        id: Int,
    ): SakList? = list(database.dsl, user, id)

    // whether the user sees the list or saw it until it was deleted: whom a change of it reaches
    // (LIST-4)
    fun seesList(
        user: String,
        id: Int,
    ): Boolean = database.dsl.fetchExists(table, this.id.eq(id), owner.eq(user).or(shared.isTrue))

    // whether the user sees the live list, in the transaction of dsl
    fun sees(
        dsl: DSLContext,
        user: String,
        id: Int,
    ): Boolean = dsl.fetchExists(table, this.id.eq(id), visible(user))

    // null when the owner has a list of the name; created runs in the same transaction with the id of
    // the new list and the time it was created, e.g. to add what the list starts with
    fun insert(
        owner: String,
        name: String,
        shared: Boolean,
        icon: String? = null,
        created: (DSLContext, Int, Instant) -> Unit = { _, _, _ -> },
    ): SakList? =
        try {
            database.dsl.transactionResult { configuration ->
                val dsl = DSL.using(configuration)
                val now = clock.instant()
                val id =
                    dsl
                        .insertInto(table)
                        .set(this.owner, owner)
                        .set(this.name, name)
                        .set(this.shared, shared)
                        .set(this.icon, icon)
                        .set(createdAt, now)
                        .returningResult(this.id)
                        .fetchOne()!!
                        .value1()!!
                created(dsl, id, now)
                list(dsl, owner, id)!!
            }
        } catch (_: IntegrityConstraintViolationException) {
            null
        }

    // a list the user sees: their own, or a shared one of another user, whose icon every user changes
    // (LIST-2); only the owner renames, shares or unshares a list. A rename of another user would
    // also tell the names of the owner's private lists, which are unique with the shared ones (SEC-19)
    fun update(
        user: String,
        id: Int,
        name: String,
        shared: Boolean,
        icon: String? = null,
    ): ListUpdate =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val current = dsl.selectFrom(table).where(this.id.eq(id), visible(user)).fetchOne() ?: return@transactionResult ListUpdate.NotFound
            val isOwner = current.get(owner) == user
            if (!isOwner && (shared != current.get(this.shared) || name != current.get(this.name))) {
                return@transactionResult ListUpdate.NotOwner
            }
            try {
                // another user never writes the name or the shared flag, and changes only a list they
                // still see: one the owner unshared or deleted meanwhile stays as it is
                val updated =
                    dsl
                        .update(table)
                        .apply { if (isOwner) set(this@ListTable.name, name).set(this@ListTable.shared, shared) }
                        .set(this.icon, icon)
                        .set(updatedAt, clock.instant())
                        .where(this.id.eq(id), if (isOwner) own(user) else visible(user))
                        .execute() > 0
                val unshared = isOwner && current.get(this.shared) == true && !shared
                if (updated) ListUpdate.Updated(list(dsl, user, id)!!, unshared) else ListUpdate.NotFound
            } catch (_: IntegrityConstraintViolationException) {
                // the names are unique among the lists of the owner, only the owner writes one
                ListUpdate.NameTaken
            }
        }

    // a list of the user's own, the last one too (LIST-1); it stays as deleted. deleted runs in the
    // same transaction with the time it was deleted, what goes with the list is deleted there
    fun delete(
        user: String,
        id: Int,
        deleted: (DSLContext, Instant) -> Unit,
    ): ListDelete =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val now = clock.instant()
            if (dsl.update(table).set(deletedAt, now).where(this.id.eq(id), own(user)).execute() == 0) return@transactionResult ListDelete.NotFound
            deleted(dsl, now)
            ListDelete.Deleted
        }

    // the live lists the user sees: their own and the shared ones of the others (LIST-2)
    fun visible(user: String): Condition = deletedAt.isNull.and(owner.eq(user).or(shared.isTrue))

    // the live lists the user owns, the only ones they share or delete (LIST-2)
    private fun own(user: String): Condition = deletedAt.isNull.and(owner.eq(user))

    private fun list(
        dsl: DSLContext,
        user: String,
        id: Int,
    ): SakList? =
        dsl
            .select(table.asterisk(), items)
            .from(table)
            .where(this.id.eq(id), visible(user))
            .fetchOne { it.toList() }

    private fun Record.toList() = SakList(get(id)!!, get(owner)!!, get(name)!!, get(shared)!!, get(createdAt)!!, get(updatedAt), get(items) ?: 0, get(icon))
}
