package io.pelle.sak.model.secrets

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.SecretsRecord
import io.pelle.sak.model.jooq.tables.references.SECRETS
import org.jooq.exception.IntegrityConstraintViolationException
import java.time.Clock
import java.time.Instant

// a secret as it is stored, encrypted under the secret key of keyVersion; nonce and ciphertext are
// base64, only SecretService (sak-backend) can read them
data class SecretRow(
    val id: Int,
    val type: String,
    val scope: String,
    val keyVersion: Int,
    val nonce: String,
    val ciphertext: String,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

// the database access of the secrets, only SecretService (sak-backend) uses it; it reads the
// secrets that are not deleted only and never sees one decrypted
class SecretRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
) {
    fun byTypeAndScope(
        type: String,
        scope: String,
    ): SecretRow? =
        database.dsl
            .selectFrom(SECRETS)
            .where(SECRETS.TYPE.eq(type), SECRETS.SCOPE.eq(scope), SECRETS.DELETED_AT.isNull)
            .fetchOne()
            ?.toRow()

    // the ones encrypted under an older key than the version, to encrypt them anew
    fun belowKeyVersion(version: Int): List<SecretRow> =
        database.dsl
            .selectFrom(SECRETS)
            .where(SECRETS.KEY_VERSION.lt(version), SECRETS.DELETED_AT.isNull)
            .orderBy(SECRETS.ID)
            .fetch()
            .map { it.toRow() }

    // null when the type and scope have a secret already
    fun insert(
        type: String,
        scope: String,
        keyVersion: Int,
        nonce: String,
        ciphertext: String,
    ): SecretRow? =
        try {
            database.dsl
                .insertInto(SECRETS)
                .set(SECRETS.TYPE, type)
                .set(SECRETS.SCOPE, scope)
                .set(SECRETS.KEY_VERSION, keyVersion)
                .set(SECRETS.NONCE, nonce)
                .set(SECRETS.CIPHERTEXT, ciphertext)
                .set(SECRETS.CREATED_AT, clock.instant())
                .returning()
                .fetchOne()
                ?.toRow()
        } catch (_: IntegrityConstraintViolationException) {
            null
        }

    // the same value encrypted anew, e.g. under the newest key; false when the secret is gone
    fun reencrypt(
        id: Int,
        keyVersion: Int,
        nonce: String,
        ciphertext: String,
    ): Boolean =
        database.dsl
            .update(SECRETS)
            .set(SECRETS.KEY_VERSION, keyVersion)
            .set(SECRETS.NONCE, nonce)
            .set(SECRETS.CIPHERTEXT, ciphertext)
            .set(SECRETS.UPDATED_AT, clock.instant())
            .where(SECRETS.ID.eq(id), SECRETS.DELETED_AT.isNull)
            .execute() > 0

    // the secret stays as deleted; false when there was none
    fun delete(
        type: String,
        scope: String,
    ): Boolean =
        database.dsl
            .update(SECRETS)
            .set(SECRETS.DELETED_AT, clock.instant())
            .where(SECRETS.TYPE.eq(type), SECRETS.SCOPE.eq(scope), SECRETS.DELETED_AT.isNull)
            .execute() > 0

    private fun SecretsRecord.toRow() =
        SecretRow(
            id = id!!,
            type = type!!,
            scope = scope!!,
            keyVersion = keyVersion!!,
            nonce = nonce!!,
            ciphertext = ciphertext!!,
            createdAt = createdAt!!,
            updatedAt = updatedAt,
        )
}
