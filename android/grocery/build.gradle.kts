// the app of the grocery lists: kotlin (built into the android gradle plugin), jetpack compose, ktor
// with okhttp, on top of core; sdks, version, signing and build types come from sak.android.app
// (build-logic)
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("sak.android.app")
}

android {
    namespace = "io.pelle.sak.grocery"

    defaultConfig {
        // also the scheme of the signin link (sak-frontend/src/app/signin/signin.ts)
        applicationId = "io.pelle.sak.grocery"
    }
}

dependencies {
    // the deployment, the sign-in, the session, the api, compose and ktor come with core (api)
    implementation(project(":core"))
    // the picker of the icon of a list, the emoji of the system (androidx)
    implementation(libs.emoji2.emojipicker)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    // the activity the screenshots show the app in (testing, Screenshots.kt), in the debug build only
    debugImplementation(libs.compose.ui.test.manifest)

    // the fake backend and the english texts of the tests (testing)
    testImplementation(project(":testing"))
}
