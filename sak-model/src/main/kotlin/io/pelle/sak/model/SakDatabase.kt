package io.pelle.sak.model

import liquibase.command.CommandScope
import liquibase.command.core.UpdateCommandStep
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.RenderNameCase
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

// the controller's database, a sqlite file only the controller uses; the schema is migrated to
// the changelog when it is opened
class SakDatabase private constructor(
    private val dataSource: SQLiteDataSource,
) {
    // the generated classes name everything in upper case, the changelog in lower case; only the
    // repositories of this module use it
    internal val dsl: DSLContext = DSL.using(dataSource, SQLDialect.SQLITE, Settings().withRenderNameCase(RenderNameCase.LOWER))

    private fun migrate() {
        dataSource.connection.use { connection ->
            val database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
            CommandScope(*UpdateCommandStep.COMMAND_NAME)
                .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database)
                .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, CHANGELOG)
                .execute()
        }
    }

    companion object {
        const val CHANGELOG = "db/changelog/db.changelog-master.yaml"

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
