package io.pelle.sak.model

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.pelle.sak.model.jooq.DefaultSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

// the rules every table follows: created_at, updated_at null until changed, deleted_at null until
// deleted, no row deleted for real (AGENT.md); owner records who created the row (MODEL-5); a resource
// id names the data of a row outside the database (MODEL-8); a timestamp is TEXT and a primary key the
// constraint PK_<table> (DM-3); a foreign key has an index (DM-4)
class SchemaTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `every table has created, updated and deleted times`() {
        val tables = DefaultSchema.DEFAULT_SCHEMA.tables
        tables.map { it.name }.toSet().size shouldBe tables.size
        tables.forEach { table ->
            fun column(name: String) = table.field(name) ?: error("${table.name} has no $name")
            listOf("created_at", "updated_at", "deleted_at").forEach { name ->
                (table.name to column(name).type) shouldBe (table.name to Instant::class.java)
            }
            (table.name to column("created_at").dataType.nullable()) shouldBe (table.name to false)
            (table.name to column("updated_at").dataType.nullable()) shouldBe (table.name to true)
            (table.name to column("deleted_at").dataType.nullable()) shouldBe (table.name to true)
        }
    }

    @Test
    fun `every timestamp is declared TEXT and every primary key is the constraint PK_ of its table (DM-3)`() {
        val database = SakDatabase.open(tempDir.resolve("sak.db"))
        DefaultSchema.DEFAULT_SCHEMA.tables.forEach { table ->
            val types =
                database.dsl
                    .fetch("SELECT name, type FROM pragma_table_info(?)", table.name)
                    .associate { it.get(0, String::class.java) to it.get(1, String::class.java) }
            types.filterKeys { it.endsWith("_at") }.forEach { (column, type) ->
                "${table.name}.$column $type" shouldBe "${table.name}.$column TEXT"
            }
            val sql = database.dsl.fetchValue("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", table.name) as String
            (table.name to sql.contains("CONSTRAINT PK_${table.name.uppercase()} PRIMARY KEY")) shouldBe (table.name to true)
        }
    }

    @Test
    fun `every foreign key has an index that starts with its column (DM-4)`() {
        val database = SakDatabase.open(tempDir.resolve("sak.db"))
        DefaultSchema.DEFAULT_SCHEMA.tables.forEach { table ->
            val firstColumns =
                database.dsl
                    .fetch("SELECT name FROM pragma_index_list(?)", table.name)
                    .map { it.get(0, String::class.java) }
                    .mapNotNull { index -> database.dsl.fetchValue("SELECT name FROM pragma_index_info(?) WHERE seqno = 0", index) as String? }
                    .toSet()
            database.dsl
                .fetch("SELECT \"from\" FROM pragma_foreign_key_list(?)", table.name)
                .map { it.get(0, String::class.java) }
                .forEach { column -> "${table.name}.$column ${column in firstColumns}" shouldBe "${table.name}.$column true" }
        }
    }

    @Test
    fun `every table records the owner of its rows`() {
        DefaultSchema.DEFAULT_SCHEMA.tables.forEach { table ->
            val owner = table.field("owner") ?: error("${table.name} has no owner")
            (table.name to owner.type) shouldBe (table.name to String::class.java)
            (table.name to owner.dataType.nullable()) shouldBe (table.name to false)
        }
    }

    @Test
    fun `a resource id is set on every row, unique also among the deleted rows (MODEL-8)`() {
        val tables = DefaultSchema.DEFAULT_SCHEMA.tables.filter { it.field("resource_id") != null }
        tables.shouldNotBeEmpty()
        val database = SakDatabase.open(tempDir.resolve("sak.db"))
        tables.forEach { table ->
            val column = table.field("resource_id")!!
            (table.name to column.dataType.nullable()) shouldBe (table.name to false)
            (table.name to column.dataType.length()) shouldBe (table.name to ResourceId.LENGTH)
            val indexes =
                database.dsl
                    .fetch("SELECT sql FROM sqlite_master WHERE type = 'index' AND tbl_name = ?", table.name)
                    .map { it.get(0, String::class.java).orEmpty() }
            (table.name to indexes.count { it.startsWith("CREATE UNIQUE INDEX") && it.endsWith("(resource_id)") }) shouldBe (table.name to 1)
        }
    }
}
