plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

dependencies {
    api(platform(libs.ktor.bom))
    api(libs.ktor.server.core)
    // the errors and pages every route answers alike
    api(project(":sak-api"))
    // the routes describe themselves for the openapi document
    api(libs.ktor.server.routing.openapi)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.ktor.server.test.host)
    // the test engine only hands out a streaming response once its handler ended
    testImplementation(libs.ktor.server.netty)
}
