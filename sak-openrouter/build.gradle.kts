// the client of openrouter, which reads the text in images and writes down recordings
plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

dependencies {
    // the files sent along are blobs of the binary storage (MODEL-7)
    api(project(":sak-storage"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":sak-api")))
}
