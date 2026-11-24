plugins {
    id("sak.kotlin-conventions")
    `java-library`
    // what the tests of every module share, e.g. reading json
    `java-test-fixtures`
}

// what every route of the api shares: the error bodies and the pages of a list
dependencies {
    api(project(":sak-core"))
    api(platform(libs.ktor.bom))
    api(libs.ktor.server.core)
    api(libs.ktor.server.routing.openapi)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testFixturesApi(libs.kotlinx.serialization.json)
    // the contract every BlobStore keeps, run by the tests of each store
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.kotest.assertions.core)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(testFixtures(project(":sak-core")))
}
