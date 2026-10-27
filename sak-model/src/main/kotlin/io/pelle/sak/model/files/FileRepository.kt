package io.pelle.sak.model.files

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.FilesRecord
import io.pelle.sak.model.jooq.tables.references.FILES
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

// the database access of the files, only FileService (sak-backend) uses it; it reads the files that are not
// deleted only
class FileRepository(
    private val database: SakDatabase,
) {
    fun byId(id: Int): StoredFile? =
        database.dsl
            .selectFrom(FILES)
            .where(FILES.ID.eq(id), FILES.DELETED_AT.isNull)
            .fetchOne()
            ?.toFile()

    fun byIds(ids: Collection<Int>): Map<Int, StoredFile> =
        database.dsl
            .selectFrom(FILES)
            .where(FILES.ID.`in`(ids), FILES.DELETED_AT.isNull)
            .fetch()
            .map { it.toFile() }
            .associateBy { it.id }

    fun updateDiscovered(
        id: Int,
        text: String,
        now: Instant,
    ) {
        database.dsl
            .update(FILES)
            .set(FILES.DISCOVERED, text)
            .set(FILES.UPDATED_AT, now.utc())
            .where(FILES.ID.eq(id))
            .execute()
    }

    fun insert(
        sha256: String,
        contentType: String,
        size: Long,
        uploadedBy: String,
        now: Instant,
    ): StoredFile =
        database.dsl
            .insertInto(FILES)
            .set(FILES.SHA256, sha256)
            .set(FILES.CONTENT_TYPE, contentType)
            .set(FILES.SIZE, size)
            .set(FILES.UPLOADED_BY, uploadedBy)
            .set(FILES.CREATED_AT, now.utc())
            .set(FILES.UPDATED_AT, now.utc())
            .returning()
            .fetchOne()!!
            .toFile()

    private fun Instant.utc() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun FilesRecord.toFile() =
        StoredFile(
            id = id!!,
            sha256 = sha256!!,
            contentType = contentType!!,
            size = size!!,
            uploadedBy = uploadedBy!!,
            createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
            updatedAt = updatedAt!!.toInstant(ZoneOffset.UTC),
            discovered = discovered,
        )
}
