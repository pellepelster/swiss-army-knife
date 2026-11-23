package io.pelle.sak.model

import org.flywaydb.core.Flyway
import javax.sql.DataSource

// the only code that touches flyway: migrates the database with the sql migrations in db/migration;
// target null migrates to the last one
internal fun migrateSchema(
    dataSource: DataSource,
    target: String? = null,
) {
    val baseline = liquibaseBaseline(dataSource)
    Flyway
        .configure()
        .dataSource(dataSource)
        .locations("classpath:$MIGRATIONS")
        .failOnMissingLocations(true)
        .apply { target?.let { target(it) } }
        .apply {
            if (baseline != null) {
                baselineOnMigrate(true)
                baselineVersion(baseline.toString())
                baselineDescription("liquibase until $baseline")
            }
        }.load()
        .migrate()
}

private const val MIGRATIONS = "db/migration"

// a database liquibase migrated until 0010 knows only its changelog: the change set NNNN-… is the
// migration VNNNN__…, with the same sql. Flyway takes the last change set run as its baseline and runs
// the migrations after it. Null for a new database and for one flyway migrated already
private fun liquibaseBaseline(dataSource: DataSource): Int? =
    dataSource.connection.use { connection ->
        fun exists(table: String) =
            connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? COLLATE NOCASE").use {
                it.setString(1, table)
                it.executeQuery().use { rows -> rows.next() }
            }
        if (exists("flyway_schema_history") || !exists("DATABASECHANGELOG")) return@use null
        val ids =
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM DATABASECHANGELOG").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        val versions = ids.map { CHANGE_SET.matchEntire(it)?.groupValues?.get(1)?.toInt() ?: error("unknown change set $it") }.sorted()
        // the change sets ran in their order; a gap would leave a migration out
        check(versions == (1..versions.size).toList()) { "the change sets $ids are not 0001 to the last one" }
        check(versions.size <= LAST_CHANGE_SET) { "the change sets $ids go beyond $LAST_CHANGE_SET" }
        versions.size
    }

private val CHANGE_SET = Regex("""(\d{4})-[a-z0-9-]+""")

// the last change set of liquibase, 0010-unique-positions
private const val LAST_CHANGE_SET = 10
