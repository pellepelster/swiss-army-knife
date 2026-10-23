plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

dependencies {
    api(platform(libs.ktor.bom))
    api(libs.ktor.server.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.ktor.server.config.yaml)
}
