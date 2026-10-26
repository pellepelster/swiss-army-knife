plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

dependencies {
    api(platform(libs.ktor.bom))
    api(libs.ktor.server.core)
    // the routes describe themselves for the openapi document
    api(libs.ktor.server.routing.openapi)
    api(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.angus.mail)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.greenmail.junit5)
}
