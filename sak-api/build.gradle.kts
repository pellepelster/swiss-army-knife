plugins {
    id("sak.kotlin-conventions")
    `java-library`
    // what the tests of every module share, e.g. reading json
    `java-test-fixtures`
}

// what every route of the api answers alike: the errors of a bad request and the pages of a list
dependencies {
    api(platform(libs.ktor.bom))
    api(libs.ktor.server.core)
    api(libs.ktor.server.routing.openapi)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testFixturesApi(libs.kotlinx.serialization.json)

    testImplementation(libs.ktor.server.test.host)
}
