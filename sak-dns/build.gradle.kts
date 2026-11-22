// checks that domains point to the addresses of the server, asking several name servers directly
plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

dependencies {
    api(project(":sak-core"))
    implementation(libs.dnsjava)
    implementation(libs.kotlin.logging)
    // dnsjava alone asks for an old slf4j; this is the one the backend runs with
    implementation(libs.slf4j.api)

    testImplementation(testFixtures(project(":sak-core")))
}
