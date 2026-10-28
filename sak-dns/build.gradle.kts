// checks that domains point to the addresses of the server, asking several name servers directly
plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

dependencies {
    api(project(":sak-api"))
    implementation(libs.dnsjava)
    implementation(libs.kotlin.logging)

    testImplementation(testFixtures(project(":sak-api")))
}
