package io.pelle.sak.model.files

import org.jooq.DSLContext
import org.jooq.Condition
import io.pelle.sak.model.ResourceIds
import io.pelle.sak.model.changesTo
import io.pelle.sak.model.changedAt
import io.pelle.sak.model.ResourceId
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.FilesRecord
import io.pelle.sak.model.jooq.tables.references.FILES
import org.jooq.impl.DSL
import java.security.SecureRandom
import java.time.Clock

// the files, for FileService only; reads the live files of their uploader only
class FileRepository(
    private val database: SakDatabase,
    // the created, updated and deleted times are taken from it, never from the callers
    private val clock: Clock = Clock.systemUTC(),
    // whether a resource id may be given out beside the database, e.g. no directory of it is left from
    // a restore of the instance (MODEL-8)
    free: (ResourceId) -> Boolean = { true },
    random: SecureRandom = SecureRandom(),
) {
    private val resourceIds = ResourceIds(FILES, FILES.RESOURCE_ID, free, random)

    fun byId(
        owner: String,
        id: Int,
    ): StoredFile? =
        database.dsl
            .selectFrom(FILES)
            .where(FILES.ID.eq(id), FILES.OWNER.eq(owner), FILES.DELETED_AT.isNull)
            .fetchOne()
            ?.toFile()

    fun byIds(
        owner: String,
        ids: Collection<Int>,
    ): Map<Int, StoredFile> =
        database.dsl
            .selectFrom(FILES)
            .where(FILES.ID.`in`(ids), FILES.OWNER.eq(owner), FILES.DELETED_AT.isNull)
            .fetch()
            .map { it.toFile() }
            .associateBy { it.id }

    // the file with the text, null when there is none; the backend finds the text on its own, so
    // there is no user to check (MODEL-6)
    fun updateDiscovered(
        id: Int,
        text: String,
    ): StoredFile? =
        database.dsl
            .update(FILES)
            .set(FILES.DISCOVERED, text)
            .set(FILES.UPDATED_AT, changedAt(FILES.UPDATED_AT, clock.instant(), FILES.DISCOVERED.changesTo(text)))
            .where(FILES.ID.eq(id), FILES.DELETED_AT.isNull)
            .returning()
            .fetchOne()
            ?.toFile()

    // a new file with a new resource id; place puts its content at the resource id before the row is
    // committed, so no other reader sees a file without its content. When place throws, there is no row
    fun insert(
        contentType: String,
        size: Long,
        owner: String,
        place: (ResourceId) -> Unit = {},
    ): StoredFile =
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            val file =
                dsl
                    .insertInto(FILES)
                    .set(FILES.RESOURCE_ID, resourceIds.new(dsl).value)
                    .set(FILES.CONTENT_TYPE, contentType)
                    .set(FILES.SIZE, size)
                    .set(FILES.OWNER, owner)
                    .set(FILES.CREATED_AT, clock.instant())
                    .returning()
                    .fetchOne()!!
                    .toFile()
            place(file.resourceId)
            file
        }

    // the resource ids of all files, the deleted ones included: their directories are no leftovers. The
    // backend looks for leftovers on its own, so there is no user to check (MODEL-6)
    fun resourceIds(): Set<ResourceId> = resourceIds.all(database.dsl)
}

// a row of files as the file storage has it
// a live file that something of another repository shows, whoever uploaded it, e.g. the photo of an
// item on a shared list (LIST-2). `shown` is the check of that repository, who sees the thing that
// shows the file; it is a part of this one sql, so the access is checked with the read (MODEL-6).
// The table of the files is read here only
internal fun shownFile(
    dsl: DSLContext,
    id: Int,
    shown: Condition,
): StoredFile? =
    dsl
        .selectFrom(FILES)
        .where(FILES.ID.eq(id), FILES.DELETED_AT.isNull, shown)
        .fetchOne()
        ?.toFile()

internal fun FilesRecord.toFile() =
    StoredFile(
        id = id!!,
        resourceId = ResourceId(resourceId!!),
        contentType = contentType!!,
        size = size!!,
        owner = owner!!,
        createdAt = createdAt!!,
        updatedAt = updatedAt,
        discovered = discovered,
    )
