// what the tests of every app share, without android: a fake backend of one deployment for the client
// of the api, and the english texts of core and the app; and, with android on the jvm (robolectric),
// the screenshots of the apps (Screenshots.kt)
// (sdks and java 17 from sak.android.library, build-logic)
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("sak.android.library")
}

android {
    namespace = "io.pelle.sak.testing"

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":core"))
    api(platform(libs.ktor.bom))
    api(libs.ktor.client.mock)
    api(libs.kotlinx.coroutines.test)
    api(libs.junit)
    // the screenshots: compose rendered by robolectric, captured by roborazzi
    api(libs.robolectric)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
    api(libs.roborazzi.junit.rule)
    api(libs.compose.ui.test.junit4)
    // the QR code the fake camera of the scan shows (FakeQrCamera)
    implementation(libs.zxing.core)
}
