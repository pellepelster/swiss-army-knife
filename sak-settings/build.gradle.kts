// the settings of the instance and of each user (SHARED-4): their definitions, how they are stored and
// checked, and their api; the settings of a part or an app are defined where it is (sak-backend)
plugins {
    id("sak.kotlin-conventions")
    `java-library`
}

dependencies {
    // SettingRepository and SakDatabase; sak-api and sak-core come with it
    api(project(":sak-model"))
    // the user of a request (call.owner) in the routes
    implementation(project(":sak-auth"))

    testImplementation(testFixtures(project(":sak-api")))
    testImplementation(testFixtures(project(":sak-core")))
    testImplementation(testFixtures(project(":sak-model")))
}
