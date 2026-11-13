// the notes app on top of core; sdks, version, signing and build types come from sak.android.app
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("sak.android.app")
}

android {
    namespace = "io.pelle.sak.notes"

    defaultConfig {
        // also the scheme of the signin link (sak-frontend/src/app/signin/signin.ts)
        applicationId = "io.pelle.sak.notes"
    }
}

dependencies {
    // compose, ktor and the api come with core
    implementation(project(":core"))
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    // the activity of the screenshots (testing, Screenshots.kt)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(project(":testing"))
}
