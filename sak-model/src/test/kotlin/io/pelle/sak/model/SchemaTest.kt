package io.pelle.sak.model

import io.kotest.matchers.shouldBe
import io.pelle.sak.model.jooq.DefaultSchema
import org.junit.jupiter.api.Test
import java.time.Instant

// the rules every table follows: created_at is set when a row is inserted, updated_at is null
// until the row is changed, deleted_at is null until the row is deleted, no row is deleted for
// real (AGENT.md)
class SchemaTest {
    @Test
    fun `every table has created, updated and deleted times`() {
        val tables = DefaultSchema.DEFAULT_SCHEMA.tables
        tables.map { it.name.lowercase() }.toSet().size shouldBe tables.size
        tables.forEach { table ->
            fun column(name: String) = table.field(name) ?: error("${table.name} has no $name")
            listOf("CREATED_AT", "UPDATED_AT", "DELETED_AT").forEach { name ->
                (table.name to column(name).type) shouldBe (table.name to Instant::class.java)
            }
            (table.name to column("CREATED_AT").dataType.nullable()) shouldBe (table.name to false)
            (table.name to column("UPDATED_AT").dataType.nullable()) shouldBe (table.name to true)
            (table.name to column("DELETED_AT").dataType.nullable()) shouldBe (table.name to true)
        }
    }
}
