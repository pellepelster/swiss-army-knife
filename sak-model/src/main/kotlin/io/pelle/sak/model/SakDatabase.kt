package io.pelle.sak.model

import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

// the sqlite file of the backend, only it uses it; migrated to the last migration (db/migration) when
// opened
class SakDatabase private constructor(
    private val dataSource: SQLiteDataSource,
) {
    // for the repositories of this module only
    internal val dsl: DSLContext = DSL.using(dataSource, SQLDialect.SQLITE)

    private fun migrate() {
        migrateSchema(dataSource)
    }

    companion object {
        fun open(file: Path): SakDatabase {
            Files.createDirectories(file.toAbsolutePath().parent)
            if (!Files.exists(file)) {
                // the secrets of the buckets and the settings are in it
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            }

            val config =
                SQLiteConfig().apply {
                    setJournalMode(SQLiteConfig.JournalMode.WAL)
                    enforceForeignKeys(true)
                    setBusyTimeout(5_000)
                }
            val dataSource = SQLiteDataSource(config).apply { url = "jdbc:sqlite:${file.toAbsolutePath()}" }
            return SakDatabase(dataSource).also { it.migrate() }
        }
    }
}
