package io.pelle.sak.model.users

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.UsersRecord
import io.pelle.sak.model.jooq.tables.references.USERS
import org.jooq.exception.IntegrityConstraintViolationException
import java.time.Clock
import java.time.Instant

// a user an admin added: the address they sign in with, normalized (trimmed, lower case), and an
// optional name the apps show instead of it
data class User(
    val id: Int,
    // the admin who added the user
    val owner: String,
    val email: String,
    val name: String?,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

// the database access of the users, only UserService (sak-backend) uses it; the users are data of
// the whole deployment, only the role admin reaches them (MODEL-6). It reads the users that are not
// deleted only
class UserRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
) {
    // by address
    fun all(): List<User> =
        database.dsl
            .selectFrom(USERS)
            .where(USERS.DELETED_AT.isNull)
            .orderBy(USERS.EMAIL)
            .fetch()
            .map { it.toUser() }

    fun byEmail(email: String): User? =
        database.dsl
            .selectFrom(USERS)
            .where(USERS.EMAIL.eq(email), USERS.DELETED_AT.isNull)
            .fetchOne()
            ?.toUser()

    // null when a user of the address exists
    fun insert(
        owner: String,
        email: String,
        name: String?,
    ): User? =
        try {
            database.dsl
                .insertInto(USERS)
                .set(USERS.OWNER, owner)
                .set(USERS.EMAIL, email)
                .set(USERS.NAME, name)
                .set(USERS.CREATED_AT, clock.instant())
                .returning()
                .fetchOne()
                ?.toUser()
        } catch (_: IntegrityConstraintViolationException) {
            null
        }

    // the row stays as deleted (MODEL-3); false when there is no such user
    fun delete(id: Int): Boolean =
        database.dsl
            .update(USERS)
            .set(USERS.DELETED_AT, clock.instant())
            .where(USERS.ID.eq(id), USERS.DELETED_AT.isNull)
            .execute() > 0

    private fun UsersRecord.toUser() = User(id!!, owner!!, email!!, name, createdAt!!, updatedAt)
}
