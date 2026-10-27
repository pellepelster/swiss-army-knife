// the controller's database: the liquibase changelog in src/main/resources/db/changelog is the
// schema, jooq generates its classes from it without a database at build time
plugins {
    id("sak.kotlin-conventions")
    `java-library`
    alias(libs.plugins.jooq.codegen)
}

val changelog = layout.projectDirectory.dir("src/main/resources/db/changelog")
val generated = layout.buildDirectory.dir("generated-sources/jooq")

dependencies {
    api(libs.jooq)
    implementation(libs.liquibase.core)
    implementation(libs.sqlite.jdbc)

    jooqCodegen(libs.jooq.meta.extensions.liquibase)
}

jooq {
    configuration {
        generator {
            name = "org.jooq.codegen.KotlinGenerator"
            database {
                name = "org.jooq.meta.extensions.liquibase.LiquibaseDatabase"
                properties {
                    property {
                        key = "rootPath"
                        value = changelog.asFile.absolutePath
                    }
                    property {
                        key = "scripts"
                        value = "db.changelog-master.yaml"
                    }
                    property {
                        key = "includeLiquibaseTables"
                        value = "false"
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
    inputs.dir(changelog)
    outputs.dir(generated)
}

sourceSets.main {
    kotlin.srcDir(tasks.named("jooqCodegen").map { generated })
}

tasks.compileKotlin {
    dependsOn(tasks.named("jooqCodegen"))
}
