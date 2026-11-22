package io.pelle.sak.model

import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import java.security.SecureRandom

// the resource ids of the rows of one table (MODEL-8), for the repository of that table: the ones its
// rows have, and a new one for a new row
internal class ResourceIds<R : Record>(
    private val table: Table<R>,
    private val column: TableField<R, String?>,
    // whether a resource id may be given out beside the database, e.g. no directory of it is left from
    // a restore of the instance
    private val free: (ResourceId) -> Boolean,
    private val random: SecureRandom,
) {
    // of all rows, the deleted ones included: their data is no leftover
    fun all(dsl: DSLContext): Set<ResourceId> =
        dsl
            .select(column)
            .from(table)
            .fetch(column)
            .map { ResourceId(it!!) }
            .toSet()

    // one no row had, deleted ones included, and free beside the database; drawn again when taken,
    // which 36^12 ids make next to impossible
    fun new(dsl: DSLContext): ResourceId =
        generateSequence { ResourceId.random(random) }
            .take(MAX_DRAWS)
            .firstOrNull { !dsl.fetchExists(table, column.eq(it.value)) && free(it) }
            ?: error("no free resource id after $MAX_DRAWS draws")

    private companion object {
        const val MAX_DRAWS = 3
    }
}
