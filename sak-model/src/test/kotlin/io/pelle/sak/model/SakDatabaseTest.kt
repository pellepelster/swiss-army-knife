package io.pelle.sak.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.pelle.sak.model.jooq.tables.references.S3_BUCKETS
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.nio.file.attribute.PosixFilePermissions

class SakDatabaseTest {
    @TempDir
    lateinit var tempDir: Path

    private val file get() = tempDir.resolve("data/sak.db")

    private fun dataSource() =
        org.sqlite.SQLiteDataSource(org.sqlite.SQLiteConfig().apply { enforceForeignKeys(true) }).apply {
            Files.createDirectories(file.parent)
            url = "jdbc:sqlite:$file"
        }

    // a database as liquibase left it after the change sets 0001 to the given one: the schema of the
    // migrations until then, and the changelog of liquibase in place of the history of flyway
    private fun liquibaseDatabase(changeSets: List<String>) {
        migrateSchema(dataSource(), target = changeSets.size.toString())
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DROP TABLE flyway_schema_history")
                statement.executeUpdate("CREATE TABLE DATABASECHANGELOG (ID VARCHAR(255) NOT NULL, AUTHOR VARCHAR(255) NOT NULL, FILENAME VARCHAR(255) NOT NULL)")
                changeSets.forEach { statement.executeUpdate("INSERT INTO DATABASECHANGELOG VALUES ('$it', 'sak', 'db/changelog/$it.yaml')") }
            }
        }
    }

    private fun versions() =
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version, type FROM flyway_schema_history WHERE success ORDER BY installed_rank").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
                }
            }
        }

    private fun insert(
        database: SakDatabase,
        name: String,
        accessKeyId: String = "AK$name",
    ) = database.dsl
        .insertInto(S3_BUCKETS)
        .set(S3_BUCKETS.NAME, name)
        .set(S3_BUCKETS.RESOURCE_ID, name.padEnd(12, '0').take(12))
        .set(S3_BUCKETS.ACCESS_KEY_ID, accessKeyId)
        .set(S3_BUCKETS.SECRET_ACCESS_KEY, "secret")
        .set(S3_BUCKETS.CREATED_AT, Instant.parse("2026-10-27T08:00:00Z"))
        .set(S3_BUCKETS.OWNER, "admin@example.org")
        .execute()

    @Test
    fun `creates the schema in a new file only the owner can read`() {
        val database = SakDatabase.open(file)

        insert(database, "bucket1")

        val bucket = database.dsl.selectFrom(S3_BUCKETS).where(S3_BUCKETS.NAME.eq("bucket1")).fetchSingle()
        bucket.accessKeyId shouldBe "AKbucket1"
        bucket.createdAt shouldBe Instant.parse("2026-10-27T08:00:00Z")
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
    }

    @Test
    fun `reports the room its file and the files of its journal take`() {
        val database = SakDatabase.open(file)
        val journal = listOf(file, file.resolveSibling("sak.db-wal"), file.resolveSibling("sak.db-shm")).filter { Files.isRegularFile(it) }
        val before = Instant.now()

        val usage = database.storageUsage()

        usage.items shouldBe journal.size.toLong()
        usage.bytes shouldBe journal.sumOf { Files.size(it) }
        (usage.bytes > 0) shouldBe true
        usage.computedAt.isBefore(before) shouldBe false
    }

    @Test
    fun `a journal file that comes and goes while the room is counted ends no count`() {
        val database = SakDatabase.open(file)
        // the shared memory of sqlite created and deleted all the time, as its checkpoints do
        val shared = file.resolveSibling("sak.db-shm")
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val churn =
            Thread {
                while (running.get()) {
                    runCatching { Files.write(shared, ByteArray(32)) }
                    runCatching { Files.deleteIfExists(shared) }
                }
            }.apply { start() }
        try {
            repeat(5_000) { (database.storageUsage().bytes > 0) shouldBe true }
        } finally {
            running.set(false)
            churn.join()
        }
    }

    @Test
    fun `keeps the data when opened again, the migrations only run once`() {
        insert(SakDatabase.open(file), "bucket1")

        val reopened = SakDatabase.open(file)

        reopened.dsl.fetchCount(S3_BUCKETS) shouldBe 1
    }

    @Test
    fun `names and access keys are unique`() {
        val database = SakDatabase.open(file)
        insert(database, "bucket1", accessKeyId = "AK1")

        shouldThrow<DataAccessException> { insert(database, "bucket1", accessKeyId = "AK2") }
        shouldThrow<DataAccessException> { insert(database, "bucket2", accessKeyId = "AK1") }
    }

    @Test
    fun `a database of the release that copied to the resource ids keeps its files and buckets without the columns of the copies`() {
        Files.createDirectories(file.parent)
        migrateSchema(dataSource(), target = "5")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO files (id, resource_id, sha256, content_type, size, owner, created_at) " +
                        "VALUES (1, 'file00000001', '${"a".repeat(64)}', 'image/jpeg', 7, 'admin@example.org', '2026-11-01 00:00:00.000')",
                )
                statement.executeUpdate(
                    "INSERT INTO s3_buckets (name, resource_id, objects_at_name, access_key_id, secret_access_key, owner, created_at) " +
                        "VALUES ('site', 'site00000001', 0, 'AKsite', 'secret', 'admin@example.org', '2026-11-01 00:00:00.000')",
                )
            }
        }

        val database = SakDatabase.open(file)

        io.pelle.sak.model.files.FileRepository(database).byId("admin@example.org", 1)!!.let {
            it.resourceId shouldBe ResourceId("file00000001")
            it.size shouldBe 7
        }
        io.pelle.sak.model.hosting.S3BucketRepository(database).byName("site")!!.resourceId shouldBe ResourceId("site00000001")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            fun columns(table: String) =
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT name FROM pragma_table_info('$table')").use { rows ->
                        generateSequence { if (rows.next()) rows.getString(1) else null }.toSet()
                    }
                }
            ("sha256" in columns("files")) shouldBe false
            ("objects_at_name" in columns("s3_buckets")) shouldBe false
            ("resource_id" in columns("files") && "resource_id" in columns("s3_buckets")) shouldBe true
        }
    }

    @Test
    fun `the table of the buckets made again keeps the buckets, their domains and the redirects to them`() {
        Files.createDirectories(file.parent)
        migrateSchema(dataSource(), target = "6")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                for ((name, key, deletedAt) in listOf(Triple("site", "AKsite", "NULL"), Triple("gone", "AKgone", "'2026-11-02 00:00:00.000'"))) {
                    statement.executeUpdate(
                        "INSERT INTO s3_buckets (name, resource_id, access_key_id, secret_access_key, owner, created_at, deleted_at) " +
                            "VALUES ('$name', '${name}00000000', '$key', 'secret', 'admin@example.org', '2026-11-01 00:00:00.000', $deletedAt)",
                    )
                }
                statement.executeUpdate(
                    "INSERT INTO s3_buckets_domains (domain, bucket_name, position, owner, created_at) " +
                        "VALUES ('example.org', 'site', 0, 'admin@example.org', '2026-11-01 00:00:00.000')",
                )
                statement.executeUpdate(
                    "INSERT INTO redirects (source_domain, target_type, target_bucket, owner, created_at) " +
                        "VALUES ('www.example.org', 'BUCKET', 'site', 'admin@example.org', '2026-11-01 00:00:00.000')",
                )
            }
        }

        val database = SakDatabase.open(file)
        val buckets = io.pelle.sak.model.hosting.S3BucketRepository(database)

        buckets.byName("site")!!.let {
            it.resourceId shouldBe ResourceId("site00000000")
            it.accessKeyId shouldBe "AKsite"
            it.domains shouldBe listOf("example.org")
        }
        buckets.resourceIds() shouldBe setOf(ResourceId("site00000000"), ResourceId("gone00000000"))
        io.pelle.sak.model.redirects.RedirectRepository(database).sourcesTo("site") shouldBe listOf("www.example.org")
        // the access key id of the deleted bucket is free, its name is not; the one of the live bucket is taken
        buckets.insert("other", "AKgone", "secret", owner = "admin@example.org").let { (it is io.pelle.sak.model.hosting.BucketInsertResult.Inserted) shouldBe true }
        buckets.insert("gone", "AKnew", "secret", owner = "admin@example.org") shouldBe io.pelle.sak.model.hosting.BucketInsertResult.NameTaken
        buckets.insert("third", "AKsite", "secret", owner = "admin@example.org") shouldBe io.pelle.sak.model.hosting.BucketInsertResult.AccessKeyTaken
        // the foreign keys hold as before: no row is without its bucket, and none can be added without one
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA foreign_key_check").use { it.next() shouldBe false }
            }
        }
        shouldThrow<DataAccessException> {
            database.dsl.execute(
                "INSERT INTO s3_buckets_domains (domain, bucket_name, position, owner, created_at) " +
                    "VALUES ('none.example.org', 'none', 0, 'admin@example.org', '2026-11-01 00:00:00.000')",
            )
        }
    }

    @Test
    fun `a list of the notes from before its icon keeps its notes and is found as the first list`() {
        Files.createDirectories(file.parent)
        migrateSchema(dataSource(), target = "7")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO note_lists (id, owner, name, shared, created_at) VALUES (7, 'pelle@pelle.io', 'My Notes', 0, '2026-11-01 00:00:00.000')",
                )
                statement.executeUpdate(
                    "INSERT INTO notes (list_id, owner, text, color, created_at) VALUES (7, 'pelle@pelle.io', 'a note', 'yellow', '2026-11-01 00:00:00.000')",
                )
            }
        }

        val notes = io.pelle.sak.model.notes.NoteRepository(SakDatabase.open(file))

        notes.ownList("pelle@pelle.io") shouldBe 7
        // the owner had a list, so no second first list is made
        notes.ensureList("pelle@pelle.io", "Other")
        notes.ownList("pelle@pelle.io") shouldBe 7
        notes.page("pelle@pelle.io", 0, 10, null).map { it.text } shouldBe listOf("a note")
        // the notes still refer to their table of lists: a table made again through a copy would leave
        // the foreign key at the copy, and no note could be added
        (notes.insert("pelle@pelle.io", 7, "a new note", "yellow", emptySet(), emptyList()) is io.pelle.sak.model.notes.NoteWriteResult.Saved) shouldBe true
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA foreign_key_check").use { it.next() shouldBe false }
            }
        }
    }

    @Test
    fun `positions taken twice before they were unique are numbered again, then each is unique (DM-5)`() {
        Files.createDirectories(file.parent)
        val at = "'2026-11-01 00:00:00.000'"
        migrateSchema(dataSource(), target = "9")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                listOf(
                    "INSERT INTO note_lists (id, owner, name, shared, created_at) VALUES (1, 'a', 'Notes', 0, $at)",
                    "INSERT INTO notes (id, owner, list_id, text, color, created_at) VALUES (1, 'a', 1, 'n', 'yellow', $at)",
                    "INSERT INTO files (id, content_type, size, owner, created_at, resource_id) VALUES (1, 'image/png', 1, 'a', $at, 'r1')",
                    "INSERT INTO files (id, content_type, size, owner, created_at, resource_id) VALUES (2, 'image/png', 1, 'a', $at, 'r2')",
                    "INSERT INTO files (id, content_type, size, owner, created_at, resource_id) VALUES (3, 'image/png', 1, 'a', $at, 'r3')",
                    // two links at 0, one at 5, and a deleted one at 0 that keeps its position
                    "INSERT INTO notes_files (id, note_id, file_id, position, owner, created_at) VALUES (10, 1, 2, 0, 'a', $at)",
                    "INSERT INTO notes_files (id, note_id, file_id, position, owner, created_at) VALUES (11, 1, 1, 0, 'a', $at)",
                    "INSERT INTO notes_files (id, note_id, file_id, position, owner, created_at) VALUES (12, 1, 3, 5, 'a', $at)",
                    "INSERT INTO notes_files (id, note_id, file_id, position, owner, created_at, deleted_at) VALUES (13, 1, 3, 0, 'a', $at, $at)",
                    "INSERT INTO restic_repositories (id, owner, name, token, resource_id, created_at) VALUES (1, 'a', 'r', 't', 'rr', $at)",
                    "INSERT INTO restic_backup_plans (id, owner, name, repository_id, host, created_at) VALUES (1, 'a', 'p', 1, 'h', $at)",
                    "INSERT INTO restic_backup_plan_paths (id, plan_id, owner, path, position, created_at) VALUES (20, 1, 'a', '/b', 1, $at)",
                    "INSERT INTO restic_backup_plan_paths (id, plan_id, owner, path, position, created_at) VALUES (21, 1, 'a', '/a', 1, $at)",
                ).forEach { statement.executeUpdate(it) }
            }
        }

        SakDatabase.open(file)

        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            fun positions(sql: String) =
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows ->
                        buildList { while (rows.next()) add(rows.getInt(1) to rows.getInt(2)) }
                    }
                }
            // by position, then by id: the order the links had stays, the deleted one is untouched
            positions("SELECT id, position FROM notes_files ORDER BY id") shouldBe listOf(10 to 0, 11 to 1, 12 to 2, 13 to 0)
            positions("SELECT id, position FROM restic_backup_plan_paths ORDER BY id") shouldBe listOf(20 to 0, 21 to 1)
            // a second live link at a taken position is refused now
            connection.createStatement().use { statement ->
                runCatching {
                    statement.executeUpdate(
                        "INSERT INTO notes_files (note_id, file_id, position, owner, created_at) VALUES (1, 1, 1, 'a', $at)",
                    )
                }.isFailure shouldBe true
                runCatching {
                    statement.executeUpdate(
                        "INSERT INTO restic_backup_plan_paths (plan_id, owner, path, position, created_at) VALUES (1, 'a', '/c', 0, $at)",
                    )
                }.isFailure shouldBe true
            }
        }
    }

    @Test
    fun `the tables made again with their timestamps as TEXT keep their rows, ids and foreign keys (DM-3)`() {
        Files.createDirectories(file.parent)
        val at = "'2026-11-01 00:00:00.123456789'"
        val rows =
            mapOf(
                "redirects" to "SELECT id, source_domain, target_bucket, created_at, updated_at, deleted_at FROM redirects ORDER BY id",
                "grocery_categories" to "SELECT id, list_id, name, position, created_at, deleted_at FROM grocery_categories ORDER BY id",
                "grocery_items" to "SELECT id, list_id, name, amount, unit, category_id, checked_at, created_at, typeof(checked_at) FROM grocery_items ORDER BY id",
                "todo_items" to "SELECT id, list_id, title, due_date, priority, done_at, created_at, typeof(created_at) FROM todo_items ORDER BY id",
            )

        fun read() =
            java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                rows.mapValues { (_, sql) ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery(sql).use { result ->
                            buildList { while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) }) }
                        }
                    }
                }
            }
        migrateSchema(dataSource(), target = "10")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                listOf(
                    "INSERT INTO s3_buckets (name, resource_id, access_key_id, secret_access_key, owner, created_at) VALUES ('site', 'site00000000', 'AKsite', 'secret', 'a', $at)",
                    "INSERT INTO redirects (id, source_domain, target_type, target_bucket, owner, created_at, updated_at) VALUES (5, 'www.example.org', 'BUCKET', 'site', 'a', $at, $at)",
                    "INSERT INTO redirects (id, source_domain, target_type, target_domain, owner, created_at, deleted_at) VALUES (7, 'old.example.org', 'DOMAIN', 'example.org', 'a', $at, $at)",
                    "INSERT INTO grocery_lists (id, owner, name, shared, created_at) VALUES (1, 'a', 'My Groceries', 0, $at)",
                    "INSERT INTO grocery_categories (id, list_id, owner, name, name_key, position, created_at) VALUES (3, 1, 'a', 'Fruit', 'fruit', 0, $at)",
                    "INSERT INTO grocery_items (id, list_id, owner, name, name_key, amount, unit, category_id, checked_at, created_at) VALUES (8, 1, 'a', 'Apples', 'apples', 1.5, 'kg', 3, $at, $at)",
                    "INSERT INTO todo_lists (id, owner, name, shared, created_at) VALUES (1, 'a', 'My Todos', 0, $at)",
                    "INSERT INTO todo_items (id, list_id, owner, title, due_date, priority, done_at, created_at) VALUES (9, 1, 'a', 'Call', '2026-11-30', 'high', $at, $at)",
                ).forEach { statement.executeUpdate(it) }
            }
        }
        val before = read()

        SakDatabase.open(file)

        read() shouldBe before
        before.values.forEach { (it.size > 0) shouldBe true }
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA foreign_key_check").use { it.next() shouldBe false }
                // the items still refer to their table of categories, not to a copy of it
                statement.executeQuery("SELECT \"table\" FROM pragma_foreign_key_list('grocery_items') WHERE \"from\" = 'category_id'").use {
                    it.next() shouldBe true
                    it.getString(1) shouldBe "grocery_categories"
                }
                // a new row gets the next id
                statement.executeUpdate("INSERT INTO todo_items (list_id, owner, title, created_at) VALUES (1, 'a', 'Next', $at)")
                statement.executeQuery("SELECT max(id) FROM todo_items").use { it.getInt(1) shouldBe 10 }
            }
        }
    }

    @Test
    fun `the repositories made before get a description, the first one "My Backups", the others their name`() {
        Files.createDirectories(file.parent)
        val at = "'2026-11-01 00:00:00.000'"
        migrateSchema(dataSource(), target = "13")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("INSERT INTO restic_repositories (id, owner, name, token, resource_id, created_at) VALUES (1, 'a', 'my-backups', 't1', 'r1', $at)")
                statement.executeUpdate("INSERT INTO restic_repositories (id, owner, name, token, resource_id, created_at) VALUES (2, 'a', 'laptop', 't2', 'r2', $at)")
            }
        }

        SakDatabase.open(file)

        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name, description FROM restic_repositories ORDER BY id").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
                } shouldBe listOf("my-backups" to "My Backups", "laptop" to "laptop")
            }
        }
    }

    @Test
    fun `the column user of the settings is named level, its values and its index stay`() {
        Files.createDirectories(file.parent)
        val at = "'2026-11-01 00:00:00.000'"
        migrateSchema(dataSource(), target = "14")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("INSERT INTO settings (id, name, value, scope, owner, created_at) VALUES (1, 'enabled', 'true', 'ai', 'admin', $at)")
                statement.executeUpdate("INSERT INTO settings (id, name, value, scope, owner, user, created_at) VALUES (2, 'ai', 'true', 'notes', 'anna', 'anna', $at)")
            }
        }

        SakDatabase.open(file)

        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, level FROM settings ORDER BY id").use { rows ->
                    buildList { while (rows.next()) add(rows.getInt(1) to rows.getString(2)) }
                } shouldBe listOf(1 to null, 2 to "anna")
                // still one live value for each scope, name and level
                runCatching {
                    statement.executeUpdate("INSERT INTO settings (name, value, scope, owner, level, created_at) VALUES ('ai', 'false', 'notes', 'anna', 'anna', $at)")
                }.isFailure shouldBe true
                statement.executeUpdate("INSERT INTO settings (name, value, scope, owner, level, created_at) VALUES ('ai', 'false', 'notes', 'bert', 'bert', $at)")
            }
        }
    }

    @Test
    fun `the settings of the instance stay, the AI switches of the admins for an app stay as deleted, users get their own`() {
        Files.createDirectories(file.parent)
        val at = "'2026-11-01 00:00:00.000'"
        migrateSchema(dataSource(), target = "12")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                listOf(
                    "INSERT INTO settings (id, name, value, scope, owner, created_at) VALUES (1, 'enabled', 'true', 'ai', 'admin', $at)",
                    "INSERT INTO settings (id, name, value, scope, owner, created_at) VALUES (2, 'ai', 'true', 'notes', 'admin', $at)",
                    "INSERT INTO settings (id, name, value, scope, owner, created_at) VALUES (3, 'ai', 'false', 'grocery', 'admin', $at)",
                    "INSERT INTO settings (id, name, value, scope, owner, created_at, deleted_at) VALUES (4, 'ai', 'false', 'notes', 'admin', $at, $at)",
                ).forEach { statement.executeUpdate(it) }
            }
        }

        SakDatabase.open(file)

        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, level, deleted_at FROM settings ORDER BY id").use { rows ->
                    buildList { while (rows.next()) add(Triple(rows.getInt(1), rows.getString(2), rows.getString(3))) }
                }.let { rows ->
                    rows.map { it.first to it.second } shouldBe listOf(1 to null, 2 to null, 3 to null, 4 to null)
                    // the one of the instance lives on, the switches of the apps are deleted, the one deleted before keeps its time
                    rows.map { it.third != null } shouldBe listOf(false, true, true, true)
                    rows.last().third shouldBe "2026-11-01 00:00:00.000"
                }
                // each user has their own value of a setting, once, and so has the instance
                statement.executeUpdate("INSERT INTO settings (name, value, scope, owner, level, created_at) VALUES ('ai', 'true', 'notes', 'anna', 'anna', $at)")
                statement.executeUpdate("INSERT INTO settings (name, value, scope, owner, level, created_at) VALUES ('ai', 'true', 'notes', 'bert', 'bert', $at)")
                statement.executeUpdate("INSERT INTO settings (name, value, scope, owner, created_at) VALUES ('ai', 'true', 'notes', 'admin', $at)")
                runCatching {
                    statement.executeUpdate("INSERT INTO settings (name, value, scope, owner, level, created_at) VALUES ('ai', 'false', 'notes', 'anna', 'anna', $at)")
                }.isFailure shouldBe true
                runCatching {
                    statement.executeUpdate("INSERT INTO settings (name, value, scope, owner, created_at) VALUES ('ai', 'false', 'notes', 'admin', $at)")
                }.isFailure shouldBe true
            }
        }
    }

    @Test
    fun `a database liquibase migrated to the last change set is taken as it is, no migration runs again`() {
        liquibaseDatabase(LIQUIBASE_CHANGE_SETS)
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO s3_buckets (name, resource_id, access_key_id, secret_access_key, owner, created_at) " +
                        "VALUES ('site', 'site00000000', 'AKsite', 'secret', 'admin@example.org', '2026-11-01 00:00:00.000')",
                )
            }
        }

        val database = SakDatabase.open(file)

        versions() shouldBe listOf("10" to "BASELINE", "11" to "SQL", "12" to "SQL", "13" to "SQL", "14" to "SQL", "15" to "SQL", "16" to "SQL")
        io.pelle.sak.model.hosting.S3BucketRepository(database).byName("site")!!.resourceId shouldBe ResourceId("site00000000")
    }

    @Test
    fun `a database liquibase migrated to an older change set gets the migrations after it`() {
        liquibaseDatabase(LIQUIBASE_CHANGE_SETS.take(7))

        SakDatabase.open(file)

        versions() shouldBe listOf("7" to "BASELINE", "8" to "SQL", "9" to "SQL", "10" to "SQL", "11" to "SQL", "12" to "SQL", "13" to "SQL", "14" to "SQL", "15" to "SQL", "16" to "SQL")
    }

    @Test
    fun `a changelog of liquibase with a gap is refused, no migration runs`() {
        liquibaseDatabase(LIQUIBASE_CHANGE_SETS.take(3))
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { it.executeUpdate("DELETE FROM DATABASECHANGELOG WHERE ID = '0002-todo'") }
        }

        shouldThrow<IllegalStateException> { SakDatabase.open(file) }
    }

    @Test
    fun `a new database gets every migration`() {
        SakDatabase.open(file)

        versions() shouldBe (1..16).map { it.toString() to "SQL" }
    }

    private companion object {
        // the ids of the change sets of liquibase, in their order (db/changelog until V10)
        val LIQUIBASE_CHANGE_SETS =
            listOf(
                "0001-initial",
                "0002-todo",
                "0003-restic",
                "0004-files-resource-id",
                "0005-s3-buckets-resource-id",
                "0006-drop-layout-migration-columns",
                "0007-s3-buckets-access-key-live",
                "0008-note-lists-icon",
                "0009-restic-plan-excludes",
                "0010-unique-positions",
            )
    }
}
