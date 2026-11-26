package io.pelle.sak.model.users

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.references.SIGN_OUTS
import java.time.Clock
import java.time.Instant

// the sign outs everywhere of the addresses (SEC-4), for UserService only: a row for each, none is
// ever changed
class SignOutRepository(
    private val database: SakDatabase,
    // the time of a sign out is taken from it, never from the callers (MODEL-9)
    private val clock: Clock = Clock.systemUTC(),
) {
    // signs the address out everywhere now, by the owner; when it was
    fun insert(
        owner: String,
        email: String,
    ): Instant {
        val now = clock.instant()
        database.dsl
            .insertInto(SIGN_OUTS)
            .set(SIGN_OUTS.OWNER, owner)
            .set(SIGN_OUTS.EMAIL, email)
            .set(SIGN_OUTS.CREATED_AT, now)
            .execute()
        return now
    }

    // when the address was signed out everywhere last, null when never
    fun latest(email: String): Instant? =
        database.dsl
            .select(SIGN_OUTS.CREATED_AT)
            .from(SIGN_OUTS)
            .where(SIGN_OUTS.EMAIL.eq(email), SIGN_OUTS.DELETED_AT.isNull)
            .fetch(SIGN_OUTS.CREATED_AT)
            .filterNotNull()
            .maxOrNull()
}
