plugins {
    id("sak.java-conventions")
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(libs.findLibrary("kotlin-logging").get())

    testImplementation(libs.findLibrary("kotest-assertions-core").get())
    testRuntimeOnly(libs.findLibrary("logback-classic").get())
}
