package io.pelle.sak.model

import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import java.time.Instant

// what an update sets updated_at to (MODEL-4): the time when one of the changes is one, else the value
// it has. A save of the values a row has already is no change, the row keeps its time. Used as
// .set(TABLE.UPDATED_AT, changedAt(TABLE.UPDATED_AT, now, TABLE.NAME.changesTo(name), …)); the
// conditions see the row as it is before the update
internal fun changedAt(
    updatedAt: Field<Instant?>,
    now: Instant,
    vararg changes: Condition,
): Field<Instant?> = DSL.`when`(DSL.or(*changes), DSL.`val`(now, updatedAt)).otherwise(updatedAt)

// whether the value is another one than the column has, null being a value like any other
internal fun <T> Field<T>.changesTo(value: T): Condition = isDistinctFrom(value)
