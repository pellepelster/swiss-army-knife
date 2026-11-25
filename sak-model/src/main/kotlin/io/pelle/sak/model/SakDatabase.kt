package io.pelle.sak.model

import io.pelle.sak.api.storage.StorageUsage
import io.pelle.sak.api.storage.StorageUsageReporter
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

// the sqlite file of the backend, only it uses it; migrated to the last migration (db/migration) when
// opened. It reports what it keeps on the data volume
class SakDatabase private constructor(
    private val dataSource: SQLiteDataSource,
    private val file: Path,
) : StorageUsageReporter {
    // for the repositories of this module only
    internal val dsl: DSLContext = DSL.using(dataSource, SQLDialect.SQLITE)

    private fun migrate() {
        migrateSchema(dataSource)
    }

    // its file and the files of its journal (-wal, -shm), as far as they are there: the room they take,
    // freed pages and soft deleted rows included
    override fun storageUsage(): StorageUsage {
        val files = listOf(file, file.resolveSibling("${file.fileName}-wal"), file.resolveSibling("${file.fileName}-shm")).filter { Files.isRegularFile(it) }
        return StorageUsage(files.sumOf { Files.size(it) }, files.size.toLong(), Instant.now())
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
            return SakDatabase(dataSource, file.toAbsolutePath()).also { it.migrate() }
        }
    }
}
