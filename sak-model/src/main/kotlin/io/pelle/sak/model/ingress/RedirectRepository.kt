package io.pelle.sak.model.ingress

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.RedirectsRecord
import io.pelle.sak.model.jooq.tables.references.REDIRECTS
import org.jooq.exception.IntegrityConstraintViolationException
import java.time.Clock

// the database access of the redirects, only RedirectService (sak-backend) uses it
class RedirectRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
) {
    fun all(): List<Redirect> =
        database.dsl
            .selectFrom(REDIRECTS)
            .orderBy(REDIRECTS.SOURCE_DOMAIN)
            .fetch()
            .map { it.toRedirect() }

    fun byId(sourceDomain: String): Redirect? =
        database.dsl
            .selectFrom(REDIRECTS)
            .where(REDIRECTS.SOURCE_DOMAIN.eq(sourceDomain))
            .fetchOne()
            ?.toRedirect()

    // null when the source has a redirect already
    fun insert(
        sourceDomain: String,
        target: RedirectTarget,
    ): Redirect? =
        try {
            val now = clock.instant()
            database.dsl
                .insertInto(REDIRECTS)
                .set(REDIRECTS.SOURCE_DOMAIN, sourceDomain)
                .set(REDIRECTS.TARGET_TYPE, target.type())
                .set(REDIRECTS.TARGET_DOMAIN, (target as? RedirectTarget.Domain)?.domain)
                .set(REDIRECTS.TARGET_BUCKET, (target as? RedirectTarget.Bucket)?.bucket)
                .set(REDIRECTS.CREATED_AT, now)
                .set(REDIRECTS.UPDATED_AT, now)
                .returning()
                .fetchOne()
                ?.toRedirect()
        } catch (_: IntegrityConstraintViolationException) {
            null
        }

    // the redirect of the source with the target, null when there is none
    fun update(
        sourceDomain: String,
        target: RedirectTarget,
    ): Redirect? =
        database.dsl
            .update(REDIRECTS)
            .set(REDIRECTS.TARGET_TYPE, target.type())
            .set(REDIRECTS.TARGET_DOMAIN, (target as? RedirectTarget.Domain)?.domain)
            .set(REDIRECTS.TARGET_BUCKET, (target as? RedirectTarget.Bucket)?.bucket)
            .set(REDIRECTS.UPDATED_AT, clock.instant())
            .where(REDIRECTS.SOURCE_DOMAIN.eq(sourceDomain))
            .returning()
            .fetchOne()
            ?.toRedirect()

    // false when there was none
    fun delete(sourceDomain: String): Boolean =
        database.dsl
            .deleteFrom(REDIRECTS)
            .where(REDIRECTS.SOURCE_DOMAIN.eq(sourceDomain))
            .execute() > 0

    private fun RedirectTarget.type() =
        when (this) {
            is RedirectTarget.Domain -> DOMAIN
            is RedirectTarget.Bucket -> BUCKET
        }

    private fun RedirectsRecord.toRedirect() =
        Redirect(
            sourceDomain = sourceDomain!!,
            target =
                when (targetType) {
                    DOMAIN -> RedirectTarget.Domain(targetDomain!!)
                    BUCKET -> RedirectTarget.Bucket(targetBucket!!)
                    else -> error("redirect of '$sourceDomain' has the unknown target type '$targetType'")
                },
            createdAt = createdAt!!,
            updatedAt = updatedAt!!,
        )

    companion object {
        private const val DOMAIN = "DOMAIN"

        private const val BUCKET = "BUCKET"
    }
}
