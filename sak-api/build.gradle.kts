plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

// what every route of the api answers alike: the errors of a bad request and the pages of a list
dependencies {
    api(platform(libs.ktor.bom))
    api(libs.ktor.server.core)
    api(libs.ktor.server.routing.openapi)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
}
