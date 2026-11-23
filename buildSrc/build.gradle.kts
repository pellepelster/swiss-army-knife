plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    // MigrateSqlite, the schema of sak-model that jooq generates its classes from
    implementation(libs.flyway.core)
    implementation(libs.sqlite.jdbc)
}
