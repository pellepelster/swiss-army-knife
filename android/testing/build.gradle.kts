// what the tests of every app share: the fake backend, the english texts and, on robolectric, the
// activities and the screenshots
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
    // the QR code of FakeQrCamera
    implementation(libs.zxing.core)
}
