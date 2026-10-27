package io.pelle.sak.model.files

import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.FilesRecord
import io.pelle.sak.model.jooq.tables.references.FILES
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

// the database access of the files, only FileService uses it; it reads the files that are not
// deleted only
internal class FileRepository(
    private val database: SakDatabase,
) {
    fun bySha256(sha256: String): StoredFile? =
        database.dsl
            .selectFrom(FILES)
            .where(FILES.SHA256.eq(sha256), FILES.DELETED_AT.isNull)
            .fetchOne()
            ?.toFile()

    fun bySha256s(sha256s: Collection<String>): Map<String, StoredFile> =
        database.dsl
            .selectFrom(FILES)
            .where(FILES.SHA256.`in`(sha256s), FILES.DELETED_AT.isNull)
            .fetch()
            .map { it.toFile() }
            .associateBy { it.sha256 }

    fun existing(sha256s: Collection<String>): Set<String> =
        database.dsl
            .select(FILES.SHA256)
            .from(FILES)
            .where(FILES.SHA256.`in`(sha256s), FILES.DELETED_AT.isNull)
            .fetch(FILES.SHA256)
            .filterNotNull()
            .toSet()

    fun updateDiscovered(
        sha256: String,
        text: String,
        now: Instant,
    ) {
        database.dsl
            .update(FILES)
            .set(FILES.DISCOVERED, text)
            .set(FILES.UPDATED_AT, now.utc())
            .where(FILES.SHA256.eq(sha256))
            .execute()
    }

    // the file as it is stored, the one uploaded first when the content is there already
    fun insertIfMissing(file: StoredFile): StoredFile {
        database.dsl
            .insertInto(FILES)
            .set(FILES.SHA256, file.sha256)
            .set(FILES.CONTENT_TYPE, file.contentType)
            .set(FILES.SIZE, file.size)
            .set(FILES.UPLOADED_BY, file.uploadedBy)
            .set(FILES.CREATED_AT, file.createdAt.utc())
            .set(FILES.UPDATED_AT, file.updatedAt.utc())
            .onConflictDoNothing()
            .execute()
        return database.dsl
            .selectFrom(FILES)
            .where(FILES.SHA256.eq(file.sha256))
            .fetchOne()!!
            .toFile()
    }

    private fun Instant.utc() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun FilesRecord.toFile() =
        StoredFile(
            sha256 = sha256!!,
            contentType = contentType!!,
            size = size!!,
            uploadedBy = uploadedBy!!,
            createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
            updatedAt = updatedAt!!.toInstant(ZoneOffset.UTC),
            discovered = discovered,
        )
}
