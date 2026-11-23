import org.flywaydb.core.Flyway
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files

// an empty sqlite file migrated with the sql migrations of flyway, the schema jooq generates its
// classes from (sak-model/build.gradle.kts): the same database the backend runs, no other one in between
@CacheableTask
abstract class MigrateSqlite : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val migrations: DirectoryProperty

    @get:OutputFile
    abstract val database: RegularFileProperty

    @TaskAction
    fun migrate() {
        val file = database.get().asFile.toPath()
        Files.createDirectories(file.parent)
        Files.deleteIfExists(file)
        val config = SQLiteConfig().apply { enforceForeignKeys(true) }
        val dataSource = SQLiteDataSource(config).apply { url = "jdbc:sqlite:$file" }
        Flyway
            .configure(Flyway::class.java.classLoader)
            .dataSource(dataSource)
            .locations("filesystem:${migrations.get().asFile.absolutePath}")
            .failOnMissingLocations(true)
            .load()
            .migrate()
    }
}
