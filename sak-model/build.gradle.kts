// the database of the backend: the sql migrations of flyway in src/main/resources/db/migration are the
// schema. jooq generates its classes from a sqlite file migrated with them at build time, the
// database the backend runs
plugins {
    id("sak.kotlin-conventions")
    `java-library`
    // the images and recordings the tests of the services store
    `java-test-fixtures`
    alias(libs.plugins.jooq.codegen)
}

val migrationsDir = layout.projectDirectory.dir("src/main/resources/db/migration")
val generated = layout.buildDirectory.dir("generated-sources/jooq")
val codegenDatabase = layout.buildDirectory.file("codegen/sak.db")

val migrateCodegenDatabase by tasks.registering(MigrateSqlite::class) {
    migrations = migrationsDir
    database = codegenDatabase
}

dependencies {
    // not api: no other module compiles against jOOQ (MODEL-2)
    implementation(libs.jooq)
    implementation(libs.flyway.core)
    implementation(libs.sqlite.jdbc)

    jooqCodegen(libs.sqlite.jdbc)
}

jooq {
    configuration {
        jdbc {
            driver = "org.sqlite.JDBC"
            url = "jdbc:sqlite:${codegenDatabase.get().asFile.absolutePath}"
        }
        generator {
            name = "org.jooq.codegen.KotlinGenerator"
            database {
                name = "org.jooq.meta.sqlite.SQLiteDatabase"
                excludes = "flyway_schema_history|sqlite_.*"
                // the timestamps are instants, InstantConverter turns them into the utc they are
                // stored in. They are found by name; each is a TEXT column (V11, SchemaTest)
                forcedTypes {
                    forcedType {
                        name = "TIMESTAMP(6)"
                        userType = "java.time.Instant"
                        converter = "io.pelle.sak.model.InstantConverter"
                        includeExpression = ".*\\.[a-z_]+_at"
                    }
                }
            }
            target {
                packageName = "io.pelle.sak.model.jooq"
                directory = generated.get().asFile.absolutePath
            }
        }
    }
}

tasks.named("jooqCodegen") {
    dependsOn(migrateCodegenDatabase)
    inputs.file(codegenDatabase)
    outputs.dir(generated)
}

sourceSets.main {
    kotlin.srcDir(tasks.named("jooqCodegen").map { generated })
}

tasks.compileKotlin {
    dependsOn(tasks.named("jooqCodegen"))
}
