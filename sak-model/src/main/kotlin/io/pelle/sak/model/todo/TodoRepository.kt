package io.pelle.sak.model.todo

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.lists.ListDelete
import io.pelle.sak.model.lists.ListTable
import io.pelle.sak.model.lists.ListUpdate
import io.pelle.sak.model.lists.SakList
import io.pelle.sak.model.jooq.tables.records.TodoItemsRecord
import io.pelle.sak.model.jooq.tables.references.TODO_ITEMS
import io.pelle.sak.model.jooq.tables.references.TODO_LISTS
import org.jooq.Condition
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

// the order of the priorities, the high ones first (TODO-5)
private val PRIORITY_ORDER =
    DSL
        .`when`(TODO_ITEMS.PRIORITY.eq(TodoPriority.HIGH.key), 0)
        .`when`(TODO_ITEMS.PRIORITY.eq(TodoPriority.NORMAL.key), 1)
        .otherwise(2)

// how urgent a task is (TODO-5); key is how it is stored and sent
enum class TodoPriority(
    val key: String,
) {
    HIGH("high"),
    NORMAL("normal"),
    LOW("low"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

// a task to do; done, it stays struck through until its list is purged (TODO-3, TODO-6)
data class TodoItem(
    val id: Int,
    val listId: Int,
    val owner: String,
    val title: String,
    val createdAt: Instant,
    val updatedAt: Instant?,
    // the day it is to be done by, null for no day (TODO-4)
    val dueDate: LocalDate? = null,
    val priority: TodoPriority = TodoPriority.NORMAL,
    // when it was done, null while it is open (TODO-3)
    val doneAt: Instant? = null,
)

// the lists and tasks of the todo app, for TodoService only. Reads live rows only and checks who
// sees and changes them in the sql of each method (MODEL-6): a user changes their own lists and the
// shared ones of the others (LIST-2); only the owner shares, unshares and deletes a list
class TodoRepository(
    private val database: SakDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    // the lists, with how many tasks are open on each
    private val lists =
        ListTable(
            database,
            clock,
            TODO_LISTS,
            TODO_LISTS.ID,
            TODO_LISTS.OWNER,
            TODO_LISTS.NAME,
            TODO_LISTS.SHARED,
            TODO_LISTS.ICON,
            TODO_LISTS.CREATED_AT,
            TODO_LISTS.UPDATED_AT,
            TODO_LISTS.DELETED_AT,
            DSL.field(
                DSL
                    .selectCount()
                    .from(TODO_ITEMS)
                    .where(TODO_ITEMS.LIST_ID.eq(TODO_LISTS.ID), TODO_ITEMS.DELETED_AT.isNull, TODO_ITEMS.DONE_AT.isNull),
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

    // null when the owner has a list of the name
    fun insertList(
        owner: String,
        name: String,
        shared: Boolean,
        icon: String? = null,
    ): SakList? = lists.insert(owner, name, shared, icon)

    // a list the user sees, see ListTable.update
    fun updateList(
        user: String,
        id: Int,
        name: String,
        shared: Boolean,
        icon: String? = null,
    ): ListUpdate = lists.update(user, id, name, shared, icon)

    // a list of the user's own, the last one too (LIST-1); it and its tasks stay as deleted
    fun deleteList(
        user: String,
        id: Int,
    ): ListDelete =
        lists.delete(user, id) { dsl, now ->
            dsl
                .update(TODO_ITEMS)
                .set(TODO_ITEMS.DELETED_AT, now)
                .where(TODO_ITEMS.LIST_ID.eq(id), TODO_ITEMS.DELETED_AT.isNull)
                .execute()
        }

    // open and done alike, so a task keeps its place when it is done (TODO-3): by priority (TODO-5),
    // then in the order they were added; null when the user does not see the list
    fun items(
        user: String,
        listId: Int,
    ): List<TodoItem>? =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            if (!lists.sees(dsl, user, listId)) return@transactionResult null
            dsl
                .selectFrom(TODO_ITEMS)
                .where(TODO_ITEMS.LIST_ID.eq(listId), TODO_ITEMS.DELETED_AT.isNull)
                .orderBy(PRIORITY_ORDER, TODO_ITEMS.ID)
                .fetch()
                .map { it.toItem() }
        }

    // a task, open or done; null when the user does not see its list
    fun item(
        user: String,
        id: Int,
    ): TodoItem? =
        database.dsl
            .selectFrom(TODO_ITEMS)
            .where(TODO_ITEMS.ID.eq(id), visibleItem(user))
            .fetchOne()
            ?.toItem()

    // the task on the list, null when the user does not see the list; the user is its owner (MODEL-5)
    fun insertItem(
        user: String,
        listId: Int,
        title: String,
        dueDate: LocalDate? = null,
        priority: TodoPriority = TodoPriority.NORMAL,
    ): TodoItem? =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            if (!lists.sees(dsl, user, listId)) return@transactionResult null
            dsl
                .insertInto(TODO_ITEMS)
                .set(TODO_ITEMS.LIST_ID, listId)
                .set(TODO_ITEMS.OWNER, user)
                .set(TODO_ITEMS.TITLE, title)
                .set(TODO_ITEMS.DUE_DATE, dueDate)
                .set(TODO_ITEMS.PRIORITY, priority.key)
                .set(TODO_ITEMS.CREATED_AT, clock.instant())
                .returning()
                .fetchOne()!!
                .toItem()
        }

    // done or open again (TODO-3), also a task another user added to a shared list (LIST-2); a done
    // task keeps when it was done first. Null when the user does not see it
    fun setDone(
        user: String,
        id: Int,
        done: Boolean,
    ): TodoItem? {
        val now = clock.instant()
        return database.dsl
            .update(TODO_ITEMS)
            .set(TODO_ITEMS.DONE_AT, if (done) DSL.coalesce(TODO_ITEMS.DONE_AT, DSL.`val`(now, TODO_ITEMS.DONE_AT)) else DSL.`val`(null, TODO_ITEMS.DONE_AT))
            .set(TODO_ITEMS.UPDATED_AT, now)
            .where(TODO_ITEMS.ID.eq(id), visibleItem(user))
            .returning()
            .fetchOne()
            ?.toItem()
    }

    // the priority of a task, open or done (TODO-5), also of one another user added to a shared list
    // (LIST-2); null when the user does not see it
    fun setPriority(
        user: String,
        id: Int,
        priority: TodoPriority,
    ): TodoItem? =
        database.dsl
            .update(TODO_ITEMS)
            .set(TODO_ITEMS.PRIORITY, priority.key)
            .set(TODO_ITEMS.UPDATED_AT, clock.instant())
            .where(TODO_ITEMS.ID.eq(id), visibleItem(user))
            .returning()
            .fetchOne()
            ?.toItem()

    // the day a task is to be done by, null for none (TODO-4), also of one another user added to a
    // shared list (LIST-2); null when the user does not see it
    fun setDueDate(
        user: String,
        id: Int,
        dueDate: LocalDate?,
    ): TodoItem? =
        database.dsl
            .update(TODO_ITEMS)
            .set(TODO_ITEMS.DUE_DATE, dueDate)
            .set(TODO_ITEMS.UPDATED_AT, clock.instant())
            .where(TODO_ITEMS.ID.eq(id), visibleItem(user))
            .returning()
            .fetchOne()
            ?.toItem()

    // deletes the done tasks of the ids given only, the ones the user saw, not one done meanwhile
    // (TODO-6); they stay as deleted. Null when the user does not see the list, else how many went
    fun purge(
        user: String,
        listId: Int,
        ids: Collection<Int>,
    ): Int? =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            if (!lists.sees(dsl, user, listId)) return@transactionResult null
            dsl
                .update(TODO_ITEMS)
                .set(TODO_ITEMS.DELETED_AT, clock.instant())
                .where(TODO_ITEMS.LIST_ID.eq(listId), TODO_ITEMS.ID.`in`(ids), TODO_ITEMS.DELETED_AT.isNull, TODO_ITEMS.DONE_AT.isNotNull)
                .execute()
        }

    // the live tasks, open or done, on the lists the user sees
    private fun visibleItem(user: String): Condition =
        TODO_ITEMS.DELETED_AT.isNull.and(
            TODO_ITEMS.LIST_ID.`in`(DSL.select(TODO_LISTS.ID).from(TODO_LISTS).where(lists.visible(user))),
        )

    private fun TodoItemsRecord.toItem() = TodoItem(id!!, listId!!, owner!!, title!!, createdAt!!, updatedAt, dueDate, TodoPriority.of(priority!!)!!, doneAt)
}
