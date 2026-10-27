package io.pelle.sak.model.files

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.FilesRecord
import io.pelle.sak.model.jooq.tables.references.FILES
import java.time.Clock

// the database access of the files, only FileService (sak-backend) uses it; it reads the files that are not
// deleted only, and only the ones of the address that uploaded them
class FileRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
) {
    fun byId(
        owner: String,
        id: Int,
    ): StoredFile? =
        database.dsl
            .selectFrom(FILES)
            .where(FILES.ID.eq(id), FILES.UPLOADED_BY.eq(owner), FILES.DELETED_AT.isNull)
            .fetchOne()
            ?.toFile()

    fun byIds(
        owner: String,
        ids: Collection<Int>,
    ): Map<Int, StoredFile> =
        database.dsl
            .selectFrom(FILES)
            .where(FILES.ID.`in`(ids), FILES.UPLOADED_BY.eq(owner), FILES.DELETED_AT.isNull)
            .fetch()
            .map { it.toFile() }
            .associateBy { it.id }

    // the file with the text, null when there is none
    fun updateDiscovered(
        id: Int,
        text: String,
    ): StoredFile? =
        database.dsl
            .update(FILES)
            .set(FILES.DISCOVERED, text)
            .set(FILES.UPDATED_AT, clock.instant())
            .where(FILES.ID.eq(id))
            .returning()
            .fetchOne()
            ?.toFile()

    fun insert(
        sha256: String,
        contentType: String,
        size: Long,
        uploadedBy: String,
    ): StoredFile {
        val now = clock.instant()
        return database.dsl
            .insertInto(FILES)
            .set(FILES.SHA256, sha256)
            .set(FILES.CONTENT_TYPE, contentType)
            .set(FILES.SIZE, size)
            .set(FILES.UPLOADED_BY, uploadedBy)
            .set(FILES.CREATED_AT, now)
            .set(FILES.UPDATED_AT, now)
            .returning()
            .fetchOne()!!
            .toFile()
    }

    private fun FilesRecord.toFile() =
        StoredFile(
            id = id!!,
            sha256 = sha256!!,
            contentType = contentType!!,
            size = size!!,
            uploadedBy = uploadedBy!!,
            createdAt = createdAt!!,
            updatedAt = updatedAt!!,
            discovered = discovered,
        )
}
