// what every android app of SAK shares: the deployment, the sign-in by link or password, the session
// kept encrypted, the base of the api client, the update check, the theme and the texts of no app
// (sdks and java 17 from sak.android.library, build-logic)
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("sak.android.library")
}

android {
    namespace = "io.pelle.sak.core"

    // the icons of every app, kept in sak-theme/images/android (sak.android.app adds them to each app)
    sourceSets.getByName("main").res.srcDir(rootProject.file("../sak-theme/images/android/icons"))

    buildFeatures {
        compose = true
    }
}

dependencies {
    // what the apps build on: compose, the client of the api and the coroutines come with core
    api(platform(libs.compose.bom))
    api(libs.compose.material3)
    api(libs.compose.ui)
    api(libs.activity.compose)
    api(libs.lifecycle.viewmodel.compose)
    api(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    api(platform(libs.ktor.bom))
    api(libs.ktor.client.core)
    api(libs.ktor.client.okhttp)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.kotlinx.json)
    api(libs.kotlinx.serialization.json)
    // the bodies of the api, generated from openapi.json
    api(project(":api"))

    testImplementation(libs.junit)
}
