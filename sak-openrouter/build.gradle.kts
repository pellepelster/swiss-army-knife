// the client of openrouter, which reads the text in images and writes down recordings
plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

dependencies {
    // the files sent along are blobs of the local storage (MODEL-7)
    api(project(":sak-storage-local"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":sak-api")))
}
