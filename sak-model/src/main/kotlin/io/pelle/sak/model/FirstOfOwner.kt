package io.pelle.sak.model

import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL

// the first one of an owner who never had one in the table, deleted ones included (LIST-1, BACKUP-7):
// insert runs only then, in one transaction with the look. Null when the owner had one, and when
// another was made at the same time, e.g. by a second sign-in: the unique name of the owner refuses
// the later one
internal fun <T : Any> firstOfOwner(
    database: SakDatabase,
    table: Table<*>,
    ownerField: Field<String?>,
    owner: String,
    insert: (DSLContext) -> T,
): T? =
    try {
        database.dsl.transactionResult { configuration ->
            val dsl = DSL.using(configuration)
            if (dsl.fetchExists(table, ownerField.eq(owner))) null else insert(dsl)
        }
    } catch (_: IntegrityConstraintViolationException) {
        null
    }
