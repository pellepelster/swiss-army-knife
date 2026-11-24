// binary data on disk (MODEL-7): blobs at paths, each written at once; it knows nothing of what it keeps
plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

// the interface it implements, LocalStorage, and its blob paths are in sak-api
dependencies {
    api(project(":sak-api"))

    testImplementation(testFixtures(project(":sak-api")))
}
