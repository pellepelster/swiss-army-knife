package io.pelle.sak.model.files

import io.pelle.sak.model.ResourceId
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.records.FilesRecord
import io.pelle.sak.model.jooq.tables.references.FILES
import org.jooq.DSLContext
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
    private val free: (ResourceId) -> Boolean = { true },
    private val random: SecureRandom = SecureRandom(),
) {
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
            .set(FILES.UPDATED_AT, clock.instant())
            .where(FILES.ID.eq(id))
            .returning()
            .fetchOne()
            ?.toFile()

    // a new file with a new resource id; place puts its content at the resource id before the row is
    // committed, so no other reader sees a file without its content. When place throws, there is no row
    fun insert(
        sha256: String,
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
                    .set(FILES.RESOURCE_ID, newResourceId(dsl).value)
                    .set(FILES.SHA256, sha256)
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
    fun resourceIds(): Set<ResourceId> =
        database.dsl
            .select(FILES.RESOURCE_ID)
            .from(FILES)
            .fetch(FILES.RESOURCE_ID)
            .map { ResourceId(it!!) }
            .toSet()

    // where the content of every file is and was, the deleted ones included, for the migration to the
    // resource ids the backend runs on its own (MODEL-6)
    fun locations(): List<FileLocation> =
        database.dsl
            .select(FILES.RESOURCE_ID, FILES.SHA256)
            .from(FILES)
            .orderBy(FILES.ID)
            .fetch()
            .map { FileLocation(ResourceId(it.value1()!!), it.value2()!!) }

    // a resource id no file had, deleted ones included, and free beside the database; drawn again when
    // taken, which 36^12 ids make next to impossible
    private fun newResourceId(dsl: DSLContext): ResourceId =
        generateSequence { ResourceId.random(random) }
            .take(MAX_DRAWS)
            .firstOrNull { !dsl.fetchExists(FILES, FILES.RESOURCE_ID.eq(it.value)) && free(it) }
            ?: error("no free resource id after $MAX_DRAWS draws")

    private companion object {
        const val MAX_DRAWS = 3
    }
}

// a row of files as the file storage has it
internal fun FilesRecord.toFile() =
    StoredFile(
        id = id!!,
        resourceId = ResourceId(resourceId!!),
        sha256 = sha256!!,
        contentType = contentType!!,
        size = size!!,
        owner = owner!!,
        createdAt = createdAt!!,
        updatedAt = updatedAt,
        discovered = discovered,
    )
