package io.pelle.sak.model

import liquibase.Scope
import liquibase.command.CommandScope
import liquibase.command.core.UpdateCommandStep
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import java.sql.Connection

// the only code that touches liquibase; loaded by MigrationClassLoader only, so liquibase is
// unloaded after the migration. The changelog comes from the class loader of the application
fun migrate(
    connection: Connection,
    changelog: String,
    resources: ClassLoader,
) {
    Scope.child(mapOf(Scope.Attr.resourceAccessor.name to ClassLoaderResourceAccessor(resources))) {
        val database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
        CommandScope(*UpdateCommandStep.COMMAND_NAME)
            .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database)
            .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, changelog)
            .execute()
    }
}
