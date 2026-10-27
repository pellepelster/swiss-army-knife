package io.pelle.sak.model.ingress

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.RedirectsRecord
import io.pelle.sak.model.jooq.tables.references.REDIRECTS
import org.jooq.exception.IntegrityConstraintViolationException
import java.time.Instant

// the database access of the redirects, only RedirectService (sak-backend) uses it
class RedirectRepository(
    private val database: SakDatabase,
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

    // false when the source has a redirect already
    fun insert(redirect: Redirect): Boolean =
        try {
            database.dsl
                .insertInto(REDIRECTS)
                .set(REDIRECTS.SOURCE_DOMAIN, redirect.sourceDomain)
                .set(REDIRECTS.TARGET_TYPE, redirect.target.type())
                .set(REDIRECTS.TARGET_DOMAIN, (redirect.target as? RedirectTarget.Domain)?.domain)
                .set(REDIRECTS.TARGET_BUCKET, (redirect.target as? RedirectTarget.Bucket)?.bucket)
                .set(REDIRECTS.CREATED_AT, redirect.createdAt)
                .set(REDIRECTS.UPDATED_AT, redirect.updatedAt)
                .execute()
            true
        } catch (_: IntegrityConstraintViolationException) {
            false
        }

    // the target of the redirect of the source, false when there is none
    fun update(
        sourceDomain: String,
        target: RedirectTarget,
        now: Instant,
    ): Boolean =
        database.dsl
            .update(REDIRECTS)
            .set(REDIRECTS.TARGET_TYPE, target.type())
            .set(REDIRECTS.TARGET_DOMAIN, (target as? RedirectTarget.Domain)?.domain)
            .set(REDIRECTS.TARGET_BUCKET, (target as? RedirectTarget.Bucket)?.bucket)
            .set(REDIRECTS.UPDATED_AT, now)
            .where(REDIRECTS.SOURCE_DOMAIN.eq(sourceDomain))
            .execute() > 0

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
